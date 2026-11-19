import { DurableObject } from 'cloudflare:workers';
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

const noStore = {
  'Cache-Control': 'no-store',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type, Mcp-Session-Id',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
};

function sha256Base64Url(input) {
  return createHash('sha256').update(String(input)).digest('base64url');
}

function safeEquals(a, b) {
  if (!a || !b || typeof a !== 'string' || typeof b !== 'string') return false;
  const da = createHash('sha256').update(a).digest();
  const db = createHash('sha256').update(b).digest();
  return timingSafeEqual(da, db);
}

function parseHttpsUrl(uri) {
  if (!uri || typeof uri !== 'string') return null;
  try {
    const u = new URL(uri);
    if (u.protocol !== 'https:' || u.username || u.password || u.port) return null;
    return u;
  } catch {
    return null;
  }
}

function extraAllowedHosts(env) {
  const raw = String((env && env.ALLOWED_REDIRECT_HOSTS) || '').trim();
  if (!raw) return [];
  return raw.split(',').map(s => s.trim().toLowerCase()).filter(h => /^[a-z0-9.-]+$/.test(h));
}

function validCallback(uri, env) {
  const u = parseHttpsUrl(uri);
  if (!u) return false;
  const host = u.hostname.toLowerCase();
  if (host === 'chatgpt.com') {
    return uri === 'https://chatgpt.com/connector_platform_oauth_redirect'
      || /^https:\/\/chatgpt\.com\/connector\/oauth\/[\w-]+$/.test(uri);
  }
  if (host === 'chat.openai.com' || host === 'claude.ai') return true;
  return extraAllowedHosts(env).includes(host);
}

export class PhoneHub extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.taskQueue = [];
    this.pollWaiters = new Set();
    this.replyWaiters = new Map();
    this.lastPollAt = 0;
    this.phoneHealth = null;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS state (
        k TEXT PRIMARY KEY,
        v TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS auth_codes (
        code TEXT PRIMARY KEY,
        redirect_uri TEXT NOT NULL,
        code_challenge TEXT NOT NULL,
        code_challenge_method TEXT NOT NULL,
        expires_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS oauth_tokens (
        token_hash TEXT PRIMARY KEY,
        kind TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );
      DELETE FROM state WHERE k = 'recent_requests';
    `);
  }

  setTunnel(tunnelUrl) {
    const clean = String(tunnelUrl || '').trim().replace(/\/+$/, '');
    if (!/^https:\/\/[a-zA-Z0-9.-]+$/.test(clean)) {
      return { ok: false, error: 'Invalid tunnel_url' };
    }
    const now = Date.now();
    this.sql.exec(
      `INSERT INTO state (k, v, updated_at) VALUES ('tunnel_url', ?, ?)
       ON CONFLICT(k) DO UPDATE SET v = excluded.v, updated_at = excluded.updated_at`,
      clean,
      now
    );
    return { ok: true, tunnel_url: clean, updated_at: now };
  }

  updatePhoneHealth(health) {
    if (!health || typeof health !== 'object' || Array.isArray(health)) return;
    this.phoneHealth = {
      accessibility_enabled: Boolean(health.accessibility_enabled),
      shizuku_ready: Boolean(health.shizuku_ready || (health.shizuku && health.shizuku.status_code === 'ready')),
      available_displays: Array.isArray(health.available_displays) ? health.available_displays.slice(0, 16) : [],
      reported_at: Date.now(),
    };
  }

  markOffline() {
    this.lastPollAt = 0;
    this.phoneHealth = null;
    this.sql.exec(`DELETE FROM state WHERE k = 'tunnel_url'`);
    return { ok: true, offline: true };
  }

  getTunnel() {
    const rows = [...this.sql.exec(`SELECT v, updated_at FROM state WHERE k = 'tunnel_url'`)];
    const now = Date.now();
    const rawTunnel = rows[0] ? { tunnel_url: rows[0].v, updated_at: rows[0].updated_at } : { tunnel_url: '', updated_at: 0 };
    // 备用隧道地址超过 10 分钟未刷新视为失效，避免离线时白等死隧道
    const freshTunnelUrl = (rawTunnel.tunnel_url && (now - rawTunnel.updated_at) < 10 * 60 * 1000) ? rawTunnel.tunnel_url : '';
    const lastHeartbeat = Math.max(freshTunnelUrl ? rawTunnel.updated_at : 0, this.lastPollAt || 0);
    return {
      tunnel_url: freshTunnelUrl,
      updated_at: freshTunnelUrl ? rawTunnel.updated_at : 0,
      direct_online: (now - this.lastPollAt) < 45000 || this.pollWaiters.size > 0,
      last_poll_at: this.lastPollAt,
      last_heartbeat_ms: lastHeartbeat,
      heartbeat_age_ms: lastHeartbeat > 0 ? Math.max(0, now - lastHeartbeat) : null,
      poll_waiters: this.pollWaiters.size,
      phone_health: this.phoneHealth,
    };
  }

  async pollTask(timeoutMs = 20000, health = null) {
    this.lastPollAt = Date.now();
    if (health) this.updatePhoneHealth(health);
    if (this.taskQueue.length > 0) {
      const next = this.taskQueue.shift();
      return { has_task: true, id: next.id, body: next.body };
    }
    return new Promise((resolve) => {
      const waiter = (task) => {
        clearTimeout(timer);
        this.pollWaiters.delete(waiter);
        this.lastPollAt = Date.now();
        resolve(task);
      };
      const timer = setTimeout(() => {
        this.pollWaiters.delete(waiter);
        resolve({ has_task: false });
      }, timeoutMs);
      this.pollWaiters.add(waiter);
    });
  }

  async submitReply(id, status, bodyText) {
    this.lastPollAt = Date.now();
    const pending = this.replyWaiters.get(id);
    if (pending) {
      clearTimeout(pending.timer);
      this.replyWaiters.delete(id);
      pending.resolve({ ok: true, status: status || 200, body: bodyText });
      return { ok: true, delivered: true };
    }
    return { ok: true, delivered: false };
  }

  async dispatchMcpToPhone(bodyText, timeoutMs = 25000) {
    if (this.taskQueue.length >= 50 && this.pollWaiters.size === 0) return { ok: false, error: 'queue_full' };
    const id = randomBytes(12).toString('base64url');
    const replyPromise = new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.replyWaiters.delete(id);
        const queued = this.taskQueue.findIndex(task => task.id === id);
        if (queued >= 0) this.taskQueue.splice(queued, 1);
        resolve({ ok: false, error: queued >= 0 ? 'not_delivered' : 'outcome_unknown' });
      }, timeoutMs);
      this.replyWaiters.set(id, { resolve, timer });
    });

    const firstWaiter = this.pollWaiters.values().next().value;
    if (firstWaiter) {
      firstWaiter({ has_task: true, id, body: bodyText });
    } else {
      this.taskQueue.push({ id, body: bodyText });
      // Do not discard another request silently; its own timeout removes it.
    }
    return replyPromise;
  }

  createAuthCode(redirectUri, codeChallenge, codeChallengeMethod) {
    const now = Date.now();
    this.sql.exec(`DELETE FROM auth_codes WHERE expires_at < ?`, now);
    const code = randomBytes(24).toString('base64url');
    this.sql.exec(
      `INSERT INTO auth_codes (code, redirect_uri, code_challenge, code_challenge_method, expires_at)
       VALUES (?, ?, ?, ?, ?)`,
      code,
      redirectUri,
      codeChallenge || '',
      codeChallenge ? (codeChallengeMethod || 'S256') : '',
      now + 5 * 60 * 1000
    );
    return code;
  }

  exchangeToken(params) {
    const grantType = params.grant_type || 'authorization_code';
    if (grantType === 'refresh_token') {
      const refresh = params.refresh_token || '';
      const hash = sha256Base64Url(refresh);
      const rows = [...this.sql.exec(`SELECT token_hash FROM oauth_tokens WHERE token_hash = ? AND kind = 'refresh' AND created_at > ?`, hash, Date.now() - 90 * 86400000)];
      if (!rows.length) return { error: 'invalid_grant', error_description: 'Invalid refresh_token' };
      this.sql.exec(`DELETE FROM oauth_tokens WHERE token_hash = ? AND kind = 'refresh'`, hash);
      return this.mintTokens();
    }
    if (grantType !== 'authorization_code') return { error: 'unsupported_grant_type' };
    const code = params.code || '';
    const rows = [...this.sql.exec(`SELECT * FROM auth_codes WHERE code = ?`, code)];
    this.sql.exec(`DELETE FROM auth_codes WHERE code = ?`, code);
    if (!rows.length || Date.now() > rows[0].expires_at) {
      return { error: 'invalid_grant', error_description: 'Expired or invalid code' };
    }
    const rec = rows[0];
    if (params.redirect_uri !== rec.redirect_uri || !rec.code_challenge || rec.code_challenge_method !== 'S256') {
      return { error: 'invalid_grant', error_description: 'Redirect or PKCE invalid' };
    }
    const verifier = params.code_verifier || '';
    if (!/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) return { error: 'invalid_grant', error_description: 'Invalid PKCE verifier' };
    if (!safeEquals(rec.code_challenge, sha256Base64Url(verifier))) {
      return { error: 'invalid_grant', error_description: 'PKCE verification failed' };
    }
    return this.mintTokens();
  }

  mintTokens() {
    const accessToken = 'pa_' + randomBytes(28).toString('base64url');
    const refreshToken = 'par_' + randomBytes(28).toString('base64url');
    const now = Date.now();
    this.sql.exec(
      `INSERT OR IGNORE INTO oauth_tokens (token_hash, kind, created_at) VALUES (?, 'access', ?), (?, 'refresh', ?)`,
      sha256Base64Url(accessToken),
      now,
      sha256Base64Url(refreshToken),
      now
    );
    return {
      access_token: accessToken,
      token_type: 'Bearer',
      expires_in: 86400 * 30,
      refresh_token: refreshToken,
      scope: 'mcp',
    };
  }

  verifyAccessToken(token) {
    if (!token) return false;
    const hash = sha256Base64Url(token);
    const rows = [...this.sql.exec(`SELECT token_hash FROM oauth_tokens WHERE token_hash = ? AND kind = 'access' AND created_at > ?`, hash, Date.now() - 30 * 86400000)];
    return rows.length > 0;
  }

  revokeOAuthTokens() {
    this.sql.exec(`DELETE FROM oauth_tokens`);
    this.sql.exec(`DELETE FROM auth_codes`);
    return { ok: true };
  }

  checkAndRecordAuthAttempt(success) {
    const now = Date.now();
    const windowMs = 15 * 60 * 1000;
    const rows = [...this.sql.exec(`SELECT v, updated_at FROM state WHERE k = 'failed_auth_count'`)];
    let count = 0;
    if (rows[0] && (now - rows[0].updated_at) < windowMs) {
      count = Number(rows[0].v) || 0;
    }
    if (count >= 5) {
      return { locked: true };
    }
    if (success) {
      this.sql.exec(`DELETE FROM state WHERE k = 'failed_auth_count'`);
      return { locked: false };
    }
    count += 1;
    this.sql.exec(
      `INSERT INTO state (k, v, updated_at) VALUES ('failed_auth_count', ?, ?)
       ON CONFLICT(k) DO UPDATE SET v = excluded.v, updated_at = excluded.updated_at`,
      String(count),
      now
    );
    return { locked: count >= 5 };
  }
}

const PROTECTED_RESOURCE_PATHS = new Set([
  '/.well-known/oauth-protected-resource',
  '/.well-known/oauth-protected-resource/mcp',
  '/mcp/.well-known/oauth-protected-resource',
]);

const AUTH_SERVER_META_PATHS = new Set([
  '/.well-known/oauth-authorization-server',
  '/.well-known/oauth-authorization-server/mcp',
  '/mcp/.well-known/oauth-authorization-server',
  '/.well-known/openid-configuration',
  '/.well-known/openid-configuration/mcp',
  '/mcp/.well-known/openid-configuration',
]);

async function handleRequest(request, env, url, hub) {
  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: noStore });
  }
  const baseUrl = `${url.protocol}//${url.host}`;
  const secret = env.PHONE_SECRET || '';

  // 1. Phone registration & Direct Java Reverse-RPC endpoints
  if (url.pathname === '/_phone/register' && request.method === 'POST') {
    const auth = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '').trim();
    if (!secret || !safeEquals(auth, secret)) {
      return Response.json({ error: 'Unauthorized' }, { status: 401, headers: noStore });
    }
    const body = await request.json().catch(() => ({}));
    const res = await hub.setTunnel(body.tunnel_url);
    return Response.json(res, { status: res.ok ? 200 : 400, headers: noStore });
  }

  if (url.pathname === '/_phone/poll' && request.method === 'POST') {
    const auth = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '').trim();
    if (!secret || !safeEquals(auth, secret)) {
      return Response.json({ error: 'Unauthorized' }, { status: 401, headers: noStore });
    }
    const payload = await request.json().catch(() => ({}));
    const task = await hub.pollTask(20000, payload && payload.health ? payload.health : null);
    return Response.json(task, { headers: noStore });
  }

  if (url.pathname === '/_phone/reply' && request.method === 'POST') {
    const auth = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '').trim();
    if (!secret || !safeEquals(auth, secret)) {
      return Response.json({ error: 'Unauthorized' }, { status: 401, headers: noStore });
    }
    const payload = await request.json().catch(() => ({}));
    const res = await hub.submitReply(payload.id, payload.status, payload.body);
    return Response.json(res, { headers: noStore });
  }

  if (url.pathname === '/_phone/revoke' && request.method === 'POST') {
    const auth = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '').trim();
    if (!secret || !safeEquals(auth, secret)) return Response.json({ error: 'Unauthorized' }, { status: 401, headers: noStore });
    return Response.json(await hub.revokeOAuthTokens(), { headers: noStore });
  }

  if (url.pathname === '/_phone/offline' && request.method === 'POST') {
    const auth = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '').trim();
    if (!secret || !safeEquals(auth, secret)) return Response.json({ error: 'Unauthorized' }, { status: 401, headers: noStore });
    return Response.json(await hub.markOffline(), { headers: noStore });
  }

  if (url.pathname === '/health' && request.method === 'GET') {
    const info = await hub.getTunnel();
    return Response.json({
      ok: true,
      service: 'teapot-phone-agent',
      phone_online: Boolean(info.direct_online),
      tunnel_connected: Boolean(info.direct_online || info.tunnel_url),
      direct_online: Boolean(info.direct_online),
      poll_waiters: info.poll_waiters,
      last_heartbeat_ms: info.last_heartbeat_ms || 0,
      heartbeat_age_ms: info.heartbeat_age_ms ?? null,
      updated_at: Math.max(info.updated_at || 0, info.last_poll_at || 0),
      phone: info.phone_health || null,
    }, { headers: noStore });
  }

  // 2. OAuth 2.0 Discovery & Registration Endpoints
  if (request.method === 'GET' && PROTECTED_RESOURCE_PATHS.has(url.pathname)) {
    return Response.json({
      resource: `${baseUrl}/mcp`,
      authorization_servers: [baseUrl],
      bearer_methods_supported: ['header'],
      scopes_supported: ['mcp'],
    }, { headers: noStore });
  }

  if (request.method === 'GET' && AUTH_SERVER_META_PATHS.has(url.pathname)) {
    return Response.json({
      issuer: baseUrl,
      authorization_endpoint: `${baseUrl}/authorize`,
      token_endpoint: `${baseUrl}/oauth/token`,
      registration_endpoint: `${baseUrl}/oauth/register`,
      response_types_supported: ['code'],
      grant_types_supported: ['authorization_code', 'refresh_token'],
      code_challenge_methods_supported: ['S256'],
      token_endpoint_auth_methods_supported: ['none', 'client_secret_post', 'client_secret_basic'],
      scopes_supported: ['mcp'],
    }, { headers: noStore });
  }

  if (request.method === 'POST' && (url.pathname === '/oauth/register' || url.pathname === '/mcp/oauth/register' || url.pathname === '/register')) {
    const body = await request.json().catch(() => ({}));
    const authMethod = body.token_endpoint_auth_method || 'client_secret_post';
    const resBody = {
      client_id: 'pa_client_' + randomBytes(8).toString('base64url'),
      client_id_issued_at: Math.floor(Date.now() / 1000),
      client_name: body.client_name || 'MCP Client',
      redirect_uris: Array.isArray(body.redirect_uris) ? body.redirect_uris : [],
      grant_types: Array.isArray(body.grant_types) ? body.grant_types : ['authorization_code', 'refresh_token'],
      response_types: Array.isArray(body.response_types) ? body.response_types : ['code'],
      token_endpoint_auth_method: authMethod,
    };
    if (authMethod !== 'none') {
      resBody.client_secret = 'pa_secret_' + randomBytes(16).toString('base64url');
      resBody.client_secret_expires_at = 0;
    }
    return Response.json(resBody, { status: 201, headers: noStore });
  }

  if (url.pathname === '/authorize' || url.pathname === '/oauth/authorize') {
    const redirectUri = url.searchParams.get('redirect_uri') || '';
    const state = url.searchParams.get('state') || '';
    const codeChallenge = url.searchParams.get('code_challenge') || '';
    const codeChallengeMethod = url.searchParams.get('code_challenge_method') || 'S256';
    if (!redirectUri || !validCallback(redirectUri, env)) {
      return new Response('Invalid redirect_uri', { status: 400, headers: noStore });
    }
    if (!/^[A-Za-z0-9_-]{43}$/.test(codeChallenge) || codeChallengeMethod !== 'S256') {
      return new Response('S256 PKCE required', { status: 400, headers: noStore });
    }
    if (request.method === 'GET') {
      const extraCsp = extraAllowedHosts(env).map(h => `https://${h}`).join(' ');
      const formActions = `'self' https://chatgpt.com https://chat.openai.com https://claude.ai${extraCsp ? ' ' + extraCsp : ''}`;
      return new Response(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Teapot Phone-Agent</title>
        <style>body{font-family:system-ui,sans-serif;max-width:420px;margin:40px auto;padding:20px;line-height:1.5;background:#f8f9fa}
        .card{background:#fff;padding:24px;border-radius:12px;box-shadow:0 2px 10px rgba(0,0,0,.08)}
        input,button{width:100%;padding:12px;margin-top:12px;font-size:16px;box-sizing:border-box;border-radius:8px}
        input{border:1px solid #ccc}button{background:#0b57d0;color:#fff;border:0;font-weight:600;cursor:pointer}</style>
        <div class="card"><h2>连接 Teapot Phone-Agent</h2><p>授权你的远程 MCP 客户端通过自托管 Cloudflare 网关访问 Android 手机工具。<br><small>Authorize your remote MCP client to connect via your self-hosted Cloudflare gateway.</small></p>
        <form method="post" autocomplete="off"><label>连接码 / Pairing Token <input name="code" type="password" placeholder="粘贴手机端连接码 / Paste pairing token" required autofocus></label>
        <button type="submit">授权连接 / Authorize</button></form></div>`, {
        headers: {
          ...noStore,
          'Content-Type': 'text/html; charset=utf-8',
          'Content-Security-Policy': `default-src 'none'; style-src 'unsafe-inline'; form-action ${formActions}; frame-ancestors 'none'; base-uri 'none'`,
          'Referrer-Policy': 'same-origin',
        },
      });
    }
    if (request.method === 'POST') {
      if (request.headers.get('Origin') !== baseUrl) {
        return new Response(null, { status: 403, headers: noStore });
      }
      const form = await request.formData().catch(() => null);
      const supplied = String(form?.get('code') || '').trim();
      const ok = Boolean(secret && safeEquals(supplied, secret));
      const attempt = await hub.checkAndRecordAuthAttempt(ok);
      if (attempt.locked) {
        return new Response('<!doctype html><meta charset="utf-8"><h3>尝试次数过多，已临时锁定 15 分钟。</h3>', {
          status: 429,
          headers: { ...noStore, 'Content-Type': 'text/html; charset=utf-8' },
        });
      }
      if (!ok) {
        return new Response('<!doctype html><meta charset="utf-8"><h3>连接码无效，请返回重试。</h3>', {
          status: 403,
          headers: { ...noStore, 'Content-Type': 'text/html; charset=utf-8' },
        });
      }
      const authCode = await hub.createAuthCode(redirectUri, codeChallenge, codeChallengeMethod);
      const redirectTo = new URL(redirectUri);
      redirectTo.searchParams.set('code', authCode);
      if (state) redirectTo.searchParams.set('state', state);
      return new Response(null, { status: 303, headers: { ...noStore, Location: redirectTo.toString() } });
    }
  }

  if (request.method === 'POST' && url.pathname === '/oauth/token') {
    const ct = request.headers.get('content-type') || '';
    let params = {};
    if (ct.includes('application/json')) {
      params = await request.json().catch(() => ({}));
    } else {
      const text = await request.text();
      params = Object.fromEntries(new URLSearchParams(text));
    }
    const res = await hub.exchangeToken(params);
    return Response.json(res, { status: res.error ? 400 : 200, headers: noStore });
  }

  // 3. MCP Endpoint (/mcp — Header Bearer auth only)
  if (url.pathname === '/mcp') {
    const authHeader = request.headers.get('Authorization') || '';
    const presented = authHeader.startsWith('Bearer ') ? authHeader.slice(7).trim() : '';
    const isDirectSecret = secret && safeEquals(presented, secret);
    const isOAuthToken = !isDirectSecret && presented && (await hub.verifyAccessToken(presented));
    if (!isDirectSecret && !isOAuthToken) {
      return new Response('{}', {
        status: 401,
        headers: {
          ...noStore,
          'Content-Type': 'application/json; charset=utf-8',
          'WWW-Authenticate': `Bearer resource_metadata="${baseUrl}/.well-known/oauth-protected-resource"`,
        },
      });
    }
    if (request.method === 'DELETE') return Response.json({}, { headers: noStore });
    if (request.method !== 'POST') return new Response('{}', { status: 405, headers: noStore });

    const bodyText = await request.text();
    let reqId = null;
    try {
      const parsed = JSON.parse(bodyText);
      if (parsed && parsed.id !== undefined) reqId = parsed.id;
    } catch {}
    const mcpError = (status, code, message, errorCode, stage, retryable, extra = {}) => Response.json({
      jsonrpc: '2.0',
      id: reqId,
      error: {
        code,
        message,
        data: { error_code: errorCode, stage, retryable, ...extra },
      },
    }, { status, headers: noStore });

    const info = await hub.getTunnel();
    if (!info.direct_online && !info.tunnel_url) {
      return mcpError(503, -32000, '手机当前未在线，请确认手机助手桥正在运行。', 'phone_offline', 'cloud_dispatch', true, {
        last_heartbeat_ms: info.last_heartbeat_ms || 0,
        heartbeat_age_ms: info.heartbeat_age_ms ?? null,
      });
    }

    // Priority 1: Always attempt Direct Java Reverse-RPC channel first when online or queueable.
    // 若当前无活跃 poll_waiters（可能处于轮询交接窗口或刚异常断网），使用 3.5 秒短超时快速判死。
    if (info.direct_online) {
      const dispatchTimeoutMs = info.poll_waiters > 0 ? 15000 : 3500;
      const directRes = await hub.dispatchMcpToPhone(bodyText, dispatchTimeoutMs);
      if (directRes && directRes.ok) {
        return new Response(directRes.body || '{}', {
          status: directRes.status || 200,
          headers: {
            ...noStore,
            'Content-Type': 'application/json; charset=utf-8',
          },
        });
      }

      if (directRes.error === 'outcome_unknown') {
        return mcpError(504, -32001, '手机可能已执行请求但结果超时；请先检查结果，不要盲目重试。', 'request_timeout', 'phone_execution', false);
      }

      if (directRes.error !== 'not_delivered') {
        return mcpError(503, -32000, '手机请求队列已满或连接异常，请确认手机助手桥状态。', 'phone_offline', 'cloud_queue', true);
      }
    }

    // A request already delivered to the phone may still finish after timeout.
    // Only retry if it was definitely removed before any poller could receive it.
    if (info.tunnel_url) {
      try {
        const upstream = await fetch(`${info.tunnel_url}/mcp`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'Authorization': `Bearer ${secret}`,
          },
          body: bodyText,
          signal: AbortSignal.timeout(8000),
        });
        const respText = await upstream.text();
        return new Response(respText, {
          status: upstream.status,
          headers: {
            ...noStore,
            'Content-Type': upstream.headers.get('Content-Type') || 'application/json; charset=utf-8',
          },
        });
      } catch (err) {
        const isTimeout = err && (err.name === 'TimeoutError' || err.name === 'AbortError');
        return mcpError(
          isTimeout ? 504 : 503,
          isTimeout ? -32001 : -32000,
          isTimeout ? '连接手机隧道超时，请检查手机网络或服务状态。' : '无法连接到手机隧道，手机可能已离线。',
          isTimeout ? 'request_timeout' : 'phone_offline',
          'cloud_to_tunnel',
          true
        );
      }
    }

    return mcpError(503, -32000, '手机当前未在线，请确认手机助手桥正在运行。', 'phone_offline', 'cloud_dispatch', true);
  }

  return new Response('Not Found', { status: 404, headers: noStore });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const hub = env.PHONE_HUB.get(env.PHONE_HUB.idFromName('global'));
    return handleRequest(request, env, url, hub);
  },
};
