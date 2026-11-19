const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { DatabaseSync } = require('node:sqlite');
const { createHash, randomBytes, timingSafeEqual } = require('node:crypto');

const source = fs.readFileSync(require('node:path').join(__dirname, 'worker.js'), 'utf8')
  .replace("import { DurableObject } from 'cloudflare:workers';", '')
  .replace("import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';", '')
  .replace('export class PhoneHub', 'class PhoneHub')
  .replace('export default {', 'const worker = {');
class DurableObject { constructor(ctx, env) { this.ctx = ctx; this.env = env; } }
const { PhoneHub, worker } = new Function('DurableObject', 'createHash', 'randomBytes', 'timingSafeEqual', `${source}\nreturn { PhoneHub, worker };`)(DurableObject, createHash, randomBytes, timingSafeEqual);

function makeHub() {
  const db = new DatabaseSync(':memory:');
  const sql = { exec(query, ...args) {
    if (/^\s*CREATE TABLE/i.test(query)) { db.exec(query); return []; }
    return db.prepare(query).all(...args);
  }};
  return { hub: new PhoneHub({ storage: { sql } }, {}), db };
}

test('expired queued work is never delivered after reconnect', async () => {
  const { hub } = makeHub();
  assert.equal((await hub.dispatchMcpToPhone('mutation', 10)).error, 'not_delivered');
  assert.deepEqual(await hub.pollTask(10), { has_task: false });
});

test('delivered timeout is reported as uncertain and never falls back', async () => {
  const { hub } = makeHub();
  const poll = hub.pollTask(100);
  const result = hub.dispatchMcpToPhone('mutation', 10);
  assert.equal((await poll).body, 'mutation');
  assert.equal((await result).error, 'outcome_unknown');
  const fakeHub = {
    isAuthLocked: async () => false,
    clearAuthFailures: async () => {},
    verifyAccessToken: async () => true,
    getTunnel: async () => ({ direct_online: true, poll_waiters: 1 }),
    dispatchMcpToPhone: async () => ({ ok: false, error: 'outcome_unknown' }),
  };
  const env = { PHONE_SECRET: 'test-secret', PHONE_HUB: { idFromName: () => 'test', get: () => fakeHub } };
  const response = await worker.fetch(new Request('https://example.test/mcp', { method: 'POST', headers: { Authorization: 'Bearer test-secret' }, body: '{}' }), env);
  assert.equal(response.status, 504);
});

test('OAuth tokens expire, rotate on refresh, and revoke', () => {
  const { hub, db } = makeHub();
  const pair = hub.mintTokens();
  assert.equal(hub.verifyAccessToken(pair.access_token), true);
  const next = hub.exchangeToken({ grant_type: 'refresh_token', refresh_token: pair.refresh_token });
  assert.ok(next.access_token);
  assert.equal(hub.exchangeToken({ grant_type: 'refresh_token', refresh_token: pair.refresh_token }).error, 'invalid_grant');
  db.prepare("UPDATE oauth_tokens SET created_at = ? WHERE kind = 'access'").run(Date.now() - 31 * 86400000);
  assert.equal(hub.verifyAccessToken(next.access_token), false);
  hub.revokeOAuthTokens();
  assert.equal(hub.exchangeToken({ grant_type: 'refresh_token', refresh_token: next.refresh_token }).error, 'invalid_grant');
});

test('authorization code requires matching redirect and S256 PKCE', () => {
  const { hub } = makeHub();
  const redirect = 'https://chatgpt.com/connector_platform_oauth_redirect';
  const verifier = 'x'.repeat(43);
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  const wrong = hub.createAuthCode(redirect, challenge, 'S256');
  assert.equal(hub.exchangeToken({ grant_type: 'authorization_code', code: wrong, redirect_uri: 'https://wrong.example/', code_verifier: verifier }).error, 'invalid_grant');
  const right = hub.createAuthCode(redirect, challenge, 'S256');
  assert.ok(hub.exchangeToken({ grant_type: 'authorization_code', code: right, redirect_uri: redirect, code_verifier: verifier }).access_token);
});

test('authorization endpoint rejects missing PKCE and untrusted redirect', async () => {
  const { hub } = makeHub();
  const env = { PHONE_SECRET: 'test-secret', PHONE_HUB: { idFromName: () => 'test', get: () => hub } };
  const missing = await worker.fetch(new Request('https://example.test/authorize?redirect_uri=https%3A%2F%2Fchatgpt.com%2Fconnector_platform_oauth_redirect'), env);
  assert.equal(missing.status, 400);
  const forged = await worker.fetch(new Request('https://example.test/authorize?redirect_uri=https%3A%2F%2Fevil.example%2F&code_challenge=' + 'A'.repeat(43) + '&code_challenge_method=S256'), env);
  assert.equal(forged.status, 400);
  const spoofed = await worker.fetch(new Request('https://example.test/authorize?redirect_uri=https%3A%2F%2Fchatgpt.com.evil.example%2F&code_challenge=' + 'A'.repeat(43) + '&code_challenge_method=S256'), env);
  assert.equal(spoofed.status, 400);
  const chatgpt = await worker.fetch(new Request('https://example.test/authorize?redirect_uri=https%3A%2F%2Fchatgpt.com%2Fconnector_platform_oauth_redirect&code_challenge=' + 'A'.repeat(43) + '&code_challenge_method=S256'), env);
  assert.equal(chatgpt.status, 200);
  assert.match(chatgpt.headers.get('content-security-policy') || '', /https:\/\/chatgpt\.com/);
  const envWithExtra = { ...env, ALLOWED_REDIRECT_HOSTS: 'mcp.custom.example' };
  const custom = await worker.fetch(new Request('https://example.test/authorize?redirect_uri=https%3A%2F%2Fmcp.custom.example%2Fcb&code_challenge=' + 'A'.repeat(43) + '&code_challenge_method=S256'), envWithExtra);
  assert.equal(custom.status, 200);
  assert.match(custom.headers.get('content-security-policy') || '', /https:\/\/mcp\.custom\.example/);
});

test('offline phone fails fast with structured error_code and stage', async () => {
  const { hub } = makeHub();
  const env = { PHONE_SECRET: 'test-secret', PHONE_HUB: { idFromName: () => 'test', get: () => hub } };
  const start = Date.now();
  const res = await worker.fetch(new Request('https://example.test/mcp', {
    method: 'POST',
    headers: { Authorization: 'Bearer test-secret' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 42, method: 'tools/list' }),
  }), env);
  assert.equal(res.status, 503);
  assert.ok(Date.now() - start < 1000);
  const body = await res.json();
  assert.equal(body.id, 42);
  assert.equal(body.error.data.error_code, 'phone_offline');
  assert.equal(body.error.data.stage, 'cloud_dispatch');
  assert.equal(body.error.data.retryable, true);
});

test('anonymous /health hides device fingerprint while authenticated /health exposes details', async () => {
  const { hub } = makeHub();
  hub.updatePhoneHealth({ accessibility_enabled: true, shizuku_ready: true, available_displays: [0, 3] });
  const env = { PHONE_SECRET: 'test-secret', PHONE_HUB: { idFromName: () => 'test', get: () => hub } };
  const anon = await worker.fetch(new Request('https://example.test/health'), env);
  assert.equal(anon.status, 200);
  const anonBody = await anon.json();
  assert.deepEqual(anonBody, { ok: true, service: 'teapot-phone-agent' });

  const authed = await worker.fetch(new Request('https://example.test/health', {
    headers: { Authorization: 'bearer test-secret' },
  }), env);
  assert.equal(authed.status, 200);
  const authedBody = await authed.json();
  assert.equal(authedBody.phone.accessibility_enabled, true);
  assert.equal(authedBody.phone.shizuku_ready, true);
  assert.deepEqual(authedBody.phone.available_displays, [0, 3]);
});

test('IP-scoped auth lockout prevents cross-IP DoS and blocks brute force', async () => {
  const { hub } = makeHub();
  for (let i = 0; i < 5; i++) {
    hub.checkAndRecordAuthAttempt(false, '198.51.100.1');
  }
  assert.equal(hub.checkAndRecordAuthAttempt(true, '198.51.100.1').locked, true);
  assert.equal(hub.checkAndRecordAuthAttempt(true, '203.0.113.9').locked, false);
});

test('removed /_phone/register returns 404 and oversized /mcp body returns 413', async () => {
  const { hub } = makeHub();
  const env = { PHONE_SECRET: 'test-secret', PHONE_HUB: { idFromName: () => 'test', get: () => hub } };
  const reg = await worker.fetch(new Request('https://example.test/_phone/register', {
    method: 'POST',
    headers: { Authorization: 'Bearer test-secret' },
    body: JSON.stringify({ tunnel_url: 'https://evil.example' }),
  }), env);
  assert.equal(reg.status, 404);

  const big = await worker.fetch(new Request('https://example.test/mcp', {
    method: 'POST',
    headers: { Authorization: 'Bearer test-secret' },
    body: 'x'.repeat(270000),
  }), env);
  assert.equal(big.status, 413);
});

test('explicit /_phone/offline causes immediate phone_offline response', async () => {
  const { hub } = makeHub();
  hub.lastPollAt = Date.now();
  const env = { PHONE_SECRET: 'test-secret', PHONE_HUB: { idFromName: () => 'test', get: () => hub } };
  const off = await worker.fetch(new Request('https://example.test/_phone/offline', {
    method: 'POST',
    headers: { Authorization: 'Bearer test-secret' },
    body: '{}',
  }), env);
  assert.equal(off.status, 200);
  const start = Date.now();
  const res = await worker.fetch(new Request('https://example.test/mcp', {
    method: 'POST',
    headers: { Authorization: 'Bearer test-secret' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 7, method: 'ping' }),
  }), env);
  assert.equal(res.status, 503);
  assert.ok(Date.now() - start < 1000);
  const body = await res.json();
  assert.equal(body.error.data.error_code, 'phone_offline');
});
