#!/usr/bin/env node
/**
 * Teapot Phone-Agent — 标准 MCP Stdio 桥接器
 *
 * 作用：
 * 将手机端运行的 `127.0.0.1:8765/mcp` (通过 ADB 转发到电脑 `127.0.0.1:18765/mcp`)
 * 包装为标准 stdio MCP Server，供 Antigravity、Claude Code、Codex、Cursor、Chatbox 等直接挂载调用。
 *
 * 环境变量：
 * - PHONE_AGENT_URL: 可选，默认 http://127.0.0.1:18765/mcp
 * - PHONE_AGENT_TOKEN: 必填，手机端「复制连接码」获取的本机配对令牌
 * - ADB_SERIAL: 可选，指定设备序列号（默认自动选择当前连接设备）
 */

import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

const DEFAULT_PORT = 18765;
const ENDPOINT = process.env.PHONE_AGENT_URL || `http://127.0.0.1:${DEFAULT_PORT}/mcp`;

function resolveLocalForwardPort(endpointUrl) {
  try {
    const u = new URL(endpointUrl);
    if (u.protocol === 'http:' && (u.hostname === '127.0.0.1' || u.hostname === 'localhost')) {
      return Number(u.port || 80);
    }
  } catch {
    // 忽略非法 URL，交给后续 fetch 报错
  }
  return null;
}

const LOCAL_FORWARD_PORT = resolveLocalForwardPort(ENDPOINT);

function findAdb() {
  if (process.env.ADB_PATH && existsSync(process.env.ADB_PATH)) return process.env.ADB_PATH;
  const localAppData = process.env.LOCALAPPDATA || '';
  const candidate = path.join(localAppData, 'Android', 'Sdk', 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  if (existsSync(candidate)) return candidate;
  return 'adb';
}

function adbArgs(extra) {
  return process.env.ADB_SERIAL ? ['-s', process.env.ADB_SERIAL, ...extra] : extra;
}

function ensureForwardAndToken() {
  const token = (process.env.PHONE_AGENT_TOKEN || '').trim();
  if (LOCAL_FORWARD_PORT) {
    const adb = findAdb();
    try {
      execFileSync(adb, adbArgs(['forward', `tcp:${LOCAL_FORWARD_PORT}`, 'tcp:8765']), { stdio: 'ignore', timeout: 5000 });
    } catch {
      // 忽略：若本机已有其他转发或 adb 未安装，由后续 HTTP 请求返回真实错误
    }
  }
  return token;
}

let cachedToken = ensureForwardAndToken();

async function forwardRpc(message) {
  if (!cachedToken) cachedToken = ensureForwardAndToken();
  if (!cachedToken) {
    if (message.id === undefined) return null;
    return {
      jsonrpc: '2.0',
      id: message.id ?? null,
      error: {
        code: -32002,
        message: '未配置环境变量 PHONE_AGENT_TOKEN：请在手机 Teapot Phone-Agent 设置面板点击「复制连接码」并填入 MCP 配置。',
      },
    };
  }
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'Authorization': `Bearer ${cachedToken}`,
  };
  const body = JSON.stringify(message);
  let res;
  try {
    res = await fetch(ENDPOINT, { method: 'POST', headers, body, signal: AbortSignal.timeout(25000) });
  } catch (err) {
    // 若使用本地回环地址，尝试重新执行一次 adb forward 后重试
    if (!LOCAL_FORWARD_PORT) throw err;
    cachedToken = ensureForwardAndToken();
    headers['Authorization'] = `Bearer ${cachedToken}`;
    res = await fetch(ENDPOINT, { method: 'POST', headers, body, signal: AbortSignal.timeout(25000) });
  }
  if (res.status === 202 || message.id === undefined) return null;
  const text = await res.text();
  if (!res.ok) {
    return {
      jsonrpc: '2.0',
      id: message.id ?? null,
      error: {
        code: -32000,
        message: `Teapot Phone-Agent HTTP ${res.status}: 请确认手机端已点击「启动 / 重连服务」或「仅启动本机服务 (127.0.0.1:8765)」，且 PHONE_AGENT_TOKEN 正确。`,
      },
    };
  }
  try {
    return JSON.parse(text);
  } catch {
    return {
      jsonrpc: '2.0',
      id: message.id ?? null,
      error: {
        code: -32003,
        message: `Teapot Phone-Agent 返回非 JSON 响应 (HTTP ${res.status})`,
      },
    };
  }
}

const rl = readline.createInterface({ input: process.stdin, terminal: false });

let queue = Promise.resolve();

rl.on('line', (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let msg;
  try {
    msg = JSON.parse(trimmed);
  } catch {
    return;
  }
  queue = queue.then(async () => {
    if (msg.id === undefined) {
      try { await forwardRpc(msg); } catch { /* ignore notification errors */ }
      return;
    }
    try {
      const reply = await forwardRpc(msg);
      if (reply) process.stdout.write(JSON.stringify(reply) + '\n');
    } catch (err) {
      process.stdout.write(JSON.stringify({
        jsonrpc: '2.0',
        id: msg.id,
        error: {
          code: -32001,
          message: `无法连接 Teapot Phone-Agent (${ENDPOINT}): ${err.message}。请检查手机 USB/ADB 连接并确保本机服务处于运行中。`,
        },
      }) + '\n');
    }
  });
});
