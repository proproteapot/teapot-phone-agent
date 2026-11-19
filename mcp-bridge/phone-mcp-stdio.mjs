#!/usr/bin/env node
/**
 * 手机助手桥 (PhoneAgent) — 标准 MCP Stdio 桥接器
 *
 * 作用：
 * 将手机端运行的 `127.0.0.1:8765/mcp` (通过 ADB 转发到电脑 `127.0.0.1:18765/mcp`)
 * 包装为标准 stdio MCP Server，供 Antigravity、Claude Code、Codex、Cursor、Chatbox 等直接挂载调用。
 *
 * 环境变量（均可选，默认自动通过 ADB 探测）：
 * - PHONE_AGENT_URL: 默认 http://127.0.0.1:18765/mcp
 * - PHONE_AGENT_TOKEN: 手机端 Bearer Token（未设置时自动通过 adb run-as 读取）
 * - ADB_SERIAL: 指定设备序列号（默认自动选择当前连接设备）
 */

import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

const DEFAULT_PORT = 18765;
const ENDPOINT = process.env.PHONE_AGENT_URL || `http://127.0.0.1:${DEFAULT_PORT}/mcp`;

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
  const adb = findAdb();
  try {
    execFileSync(adb, adbArgs(['forward', `tcp:${DEFAULT_PORT}`, 'tcp:8765']), { stdio: 'ignore', timeout: 5000 });
  } catch {
    // 忽略：如果使用局域网/Tailscale URL 则无需 adb forward
  }
  return token;
}

let cachedToken = ensureForwardAndToken();

async function forwardRpc(message) {
  if (!cachedToken) cachedToken = ensureForwardAndToken();
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'Authorization': `Bearer ${cachedToken}`,
  };
  const body = JSON.stringify(message);
  let res;
  try {
    res = await fetch(ENDPOINT, { method: 'POST', headers, body });
  } catch (err) {
    // 尝试重新执行一次 adb forward 后重试
    cachedToken = ensureForwardAndToken();
    headers['Authorization'] = `Bearer ${cachedToken}`;
    res = await fetch(ENDPOINT, { method: 'POST', headers, body });
  }
  if (res.status === 202 || message.id === undefined) return null;
  const text = await res.text();
  if (!res.ok) {
    return {
      jsonrpc: '2.0',
      id: message.id ?? null,
      error: {
        code: -32000,
        message: `PhoneAgent HTTP ${res.status}: 请确认手机端「手机助手桥」已点击「启动本地测试服务」，且令牌正确。`,
      },
    };
  }
  return JSON.parse(text);
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
          message: `无法连接手机助手桥 (${ENDPOINT}): ${err.message}。请检查手机 USB/ADB 连接并确保「手机助手桥」本地服务处于运行中。`,
        },
      }) + '\n');
    }
  });
});
