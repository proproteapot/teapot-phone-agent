# 安全模型与边界说明 / Security Policy & Threat Model

> [简体中文](#简体中文) | [English](#english)

---

## 简体中文

**Teapot Phone-Agent** 是面向个人自托管（Self-Hosted）的 Android MCP 手机桥。因为项目涉及屏幕无障碍、离屏虚拟屏、文件读写与可选后台命令，项目在默认配置上采取**最小权限与显式确认**原则：

### 1. 认证与传输安全
- **强制 HTTPS 与 `Authorization: Bearer` 校验**：公网通信强制走 HTTPS（Android 端通过 `network_security_config.xml` 仅对 `127.0.0.1` / `localhost` 放行明文 HTTP），严禁通过 URL Query 参数携带任何密钥或令牌。
- **OAuth 2.0 + S256 PKCE + 回调白名单**：Cloudflare Worker 网关强制校验 `code_challenge_method=S256`、`Origin` 同源以及受信任的 `redirect_uri` 白名单（默认 `chatgpt.com` / `chat.openai.com` / `claude.ai`，可通过环境变量 `ALLOWED_REDIRECT_HOSTS` 显式扩展）。
- **防爆破熔断与密文存储**：OAuth 授权页连续 5 次输错连接码将锁定 15 分钟（`HTTP 429`）；访问令牌仅在 Durable Object SQLite 中存 SHA-256 哈希；手机端本机配对码由 `AndroidKeyStore`（AES-256-GCM）硬件加密存储，不再双写明文配置。
- **公开 `/health` 最小暴露**：`GET /health` 仅返回基础在线与心跳状态，绝不记录或暴露请求内容、遥测日志或敏感凭据。

### 2. 高危能力默认关闭与人工确认边界
- **屏幕控制（默认本机人工确认）**：远程 Agent 请求屏幕控制时，默认必须在手机本机点击通知或主页按钮批准后才开启 2 分钟限时会话；控制期间顶部常驻红色停止条，息屏或锁屏立即自动撤销。仅当用户在手机端设置中手动打开「免确认自动续期」开关时，才允许自动续期。
- **离屏虚拟屏防误触与零回退**：所有屏幕操作均支持 `display_id`、`expected_package` 与 `expected_window` 守卫；一旦目标应用/窗口变化立即中止动作；虚拟屏失败直接报错，**严禁自动回退到主屏操作**。
- **Shizuku 权限留在手机端**：云端只能调用固定、参数化的具体工具（如 `virtual_display_create`、`open_app_on_display`），绝不向云端暴露通用任意 ADB Shell 接口。
- **Termux 后台命令（默认关闭）**：`termux_run` / `termux_stop` 默认关闭，必须由用户在手机端「设置与权限管理」中手动开启后方可使用；且需同时在 Termux 中启用 `allow-external-apps = true`。
- **文件系统安全**：删除操作默认移入 `Download/.PhoneAgentTrash/` 回收站；压缩/解压内置路径穿越校验；`LocalFileProvider` 仅支持只读 `ACTION_VIEW` 单次授权且校验 `getCanonicalFile()`。

### 3. 合理使用与漏洞上报
- 本项目仅供个人在**自己拥有且明确授权**的 Android 设备上自托管使用，禁止用于未授权访问、规避平台风控或自动化滥用。
- 如发现安全漏洞，请勿在公开 Issue 中披露利用细节，请通过 GitHub Security Advisory 或仓库维护者私密渠道报告。

---

## English

**Teapot Phone-Agent** is a personal, self-hosted Android MCP bridge. Because it touches accessibility controls, offscreen virtual displays, file management, and optional background execution, it enforces **least privilege and explicit opt-in** by default:

### 1. Authentication & Transport Security
- **HTTPS & Header Bearer Only**: All remote traffic uses HTTPS (`network_security_config.xml` restricts cleartext HTTP strictly to `127.0.0.1` and `localhost`). Credentials or tokens are never accepted in URL query strings.
- **OAuth 2.0 + Mandatory S256 PKCE + Redirect Allowlist**: The Cloudflare Worker gateway enforces `S256` PKCE, strict `Origin` checks, and an explicit HTTPS `redirect_uri` allowlist (`chatgpt.com`, `chat.openai.com`, `claude.ai` by default; extensible via `ALLOWED_REDIRECT_HOSTS`).
- **Brute-Force Lockout & Hardware-Backed Storage**: 5 consecutive wrong pairing codes trigger a 15-minute lockout (`HTTP 429`). OAuth tokens are stored as SHA-256 hashes in Durable Object SQLite. The on-device pairing token is encrypted with `AndroidKeyStore` (AES-256-GCM) without plaintext duplication.
- **Minimal Public `/health` Surface**: `GET /health` exposes only lightweight connectivity/heartbeat state and never logs or returns request payloads, telemetry logs, or secrets.

### 2. Opt-In Boundaries for High-Privilege Features
- **Screen Control (Manual Approval by Default)**: Remote screen control defaults to requiring explicit on-device confirmation via notification or the app UI (2-minute time-boxed session with a persistent red stop bar; immediately revoked on screen-off or lock). Auto-renewal is strictly opt-in.
- **Offscreen Display Guardrails**: Screen actions support `display_id`, `expected_package`, and `expected_window` preconditions. Mismatches abort immediately (`target_window_changed`), and virtual display failures **never** silently fall back to the primary display (`display 0`).
- **Scoped Shizuku Backend**: Shizuku privileges remain on-device behind fixed, parameterized actions (virtual display lifecycle, launching an app on a target display). No generic arbitrary shell tool is exposed to the cloud.
- **Termux Background Commands (Disabled by Default)**: `termux_run` and `termux_stop` are disabled by default and must be explicitly toggled on inside the Android app, in addition to granting `com.termux.permission.RUN_COMMAND` and enabling `allow-external-apps = true` in Termux.
- **Filesystem Safety**: Deletions default to moving files into `Download/.PhoneAgentTrash/`; zip extraction guards against path traversal; `LocalFileProvider` is read-only with canonical path containment checks.

### 3. Responsible Use & Vulnerability Reporting
- This project is intended strictly for personal self-hosting on devices you own and control.
- Please report security vulnerabilities privately via GitHub Security Advisories rather than public issues.
