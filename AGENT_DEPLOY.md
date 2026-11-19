# 🤖 Agent 一键部署指南 / One-Click Agent Deployment Guide

> [简体中文](#简体中文) | [English](#english)
>
> **给人类用户**：直接把下面这句提示词发给你的 AI Coding Agent（Claude Code / Codex / Antigravity / Cursor 等），把手机用 USB 插上电脑并打开 USB 调试即可：
> ```text
> 请阅读这个仓库的 AGENT_DEPLOY.md，按里面的步骤帮我把 Teapot Phone-Agent 部署到我的 Cloudflare 账号和已连接的 Android 手机上，并完成端到端验收。涉及登录授权或手机本机确认步骤时请暂停提示我操作。
> ```

---

## 简体中文

> 本章节直接面向执行部署任务的 **AI Agent** 编写。请按顺序执行，每一步均有明确验收标准。

### ⚠️ Agent 执行红线（必须遵守）
1. **严禁提交或打印密钥**：不得将用户的 `PHONE_SECRET`、配对令牌、Cloudflare API Token、OAuth Token 或私有 `cloud/wrangler.local.jsonc` 写入日志、回复正文或 git 提交。
2. **不得绕过人工授权边界**：Cloudflare 登录（`wrangler login`）、手机端无障碍开启、Shizuku 授权弹窗、手机端安全开关开启及 ChatGPT OAuth 授权必须由用户本人完成，Agent 只负责引导与验收。
3. **不得修改安全边界**：不要放宽 `cloud/worker.js` 的 PKCE/Origin/redirect_uri/IP 限流校验，不要关闭 `network_security_config.xml`，不要在匿名 `/health` 暴露设备详情或请求日志。

### Step 0：环境预检
在仓库根目录执行只读探测，确认以下工具可用：
- `node -v`（要求 **>= 22.13**，因为 `cloud/worker.test.cjs` 使用 Node 内置 `node:sqlite`）
- `java -version`（要求 JDK 17+）
- `adb devices`（确认目标 **Android 11+ / API 30+** 真机处于 `device` 状态）
- `npx wrangler whoami`（若未登录，暂停请用户手动执行 `npx wrangler login`；免费 Cloudflare 账号即可，**无需购买域名**）
- 确认 `ANDROID_HOME` 指向已安装 Android SDK（API 35）的目录。

### Step 1：运行 Worker 单测并部署 Cloudflare 网关（免费 `*.workers.dev`，0 域名费用）
1. 运行单元测试（必须全部通过）：
   ```bash
   node --test cloud/worker.test.cjs
   ```
2. 确定配置文件：
   - **无自定义域名（默认，$0 免费套餐）**：直接使用仓库内置的 `cloud/wrangler.jsonc`（启用了 `workers_dev: true` 和 SQLite Durable Object）；
   - **有自定义域名（可选）**：复制 `cloud/wrangler.jsonc.example` 为 `cloud/wrangler.local.jsonc`（已被 `.gitignore` 忽略），填入自定义域名 `routes`。
3. 部署 Worker：
   ```bash
   cd cloud && npm install
   npx wrangler deploy -c wrangler.jsonc
   ```
   记录部署输出中的公网 HTTPS URL（如 `https://teapot-phone-agent.<subdomain>.workers.dev`，记为 `<WORKER_URL>`）。

### Step 2：编译并安装 Android App
1. 编译 Debug APK：
   - Linux / macOS：`./gradlew assembleDebug --no-daemon`
   - Windows PowerShell：`.\gradlew.bat assembleDebug --no-daemon`
2. 安装到已连接的真机：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.teapot.phoneagent/.MainActivity
   ```
3. （可选，仅当用户需要 Termux 后台任务且手机已装 Termux）：
   ```bash
   adb shell pm grant com.teapot.phoneagent com.termux.permission.RUN_COMMAND
   ```
   **必须同时在手机 Termux 中开启外部应用调用**（否则所有 `termux_run` 都会被 Termux 拒绝）：
   ```bash
   mkdir -p ~/.termux
   grep -q '^allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties
   termux-reload-settings
   ```

### Step 3：Shizuku 环境准备（用于离屏虚拟屏）
> 说明：非 Root 模式下，**手机重启后 Shizuku 服务会停止**，需要再次通过无线调试或下方 ADB 命令重新拉起。
1. 检查手机是否已安装 Shizuku：
   - Linux / macOS：`adb shell pm list packages | grep shizuku`
   - Windows PowerShell：`adb shell pm list packages | Select-String shizuku`
   - 若未安装，征得用户同意后从 `RikkaApps/Shizuku` 官方 Release 下载 APK 并 `adb install -r`。
2. 通过 ADB 启动 Shizuku 服务（Shizuku v13.x）：
   - Linux / macOS：
     ```bash
     APK_DIR=$(dirname "$(adb shell pm path moe.shizuku.privileged.api | sed 's/package://')")
     adb shell "$APK_DIR/lib/arm64/libshizuku.so"
     adb shell ps -A | grep shizuku_server
     ```
   - Windows PowerShell：
     ```powershell
     $apkPath = ((adb shell pm path moe.shizuku.privileged.api) -replace '^package:','').Trim()
     $apkDir = $apkPath.Substring(0, $apkPath.LastIndexOf('/'))
     adb shell "$apkDir/lib/arm64/libshizuku.so"
     adb shell ps -A | Select-String shizuku_server
     ```

### Step 4：引导用户完成手机本机配对与授权（暂停等待用户确认）
请向用户清晰列出以下本机操作：
1. 在手机打开 **Teapot Phone-Agent** → 展开「设置与权限管理」：
   - 点击 **「配置自托管 Cloudflare Worker 域名」**，填入 `<WORKER_URL>` 并保存；
   - 点击 **「复制连接码」**，并告知 Agent（或用户自己在电脑终端运行 `npx wrangler secret put PHONE_SECRET -c cloud/wrangler.jsonc` 粘贴该连接码，**推荐由用户直接在终端输入，不经对话暴露**）；
   - 在手机端开启无障碍服务：**「Teapot Phone-Agent 屏幕控制」**（若通过手机侧载而非 `adb install` 安装，Android 13+ 需先在系统「应用信息」右上角点「允许受限制的设置」；另按需开启完整文件/日历/通知权限）；
   - 如有 Shizuku，点击主页 **「★ 授权 Shizuku 离屏虚拟屏权限」** 并选择「始终允许」；
   - 按需决定是否开启高危安全开关（默认均关闭）：屏幕控制免确认自动续期、允许后台 Termux 命令执行、Shizuku 自动恢复被误杀的无障碍。
2. **先完成 `PHONE_SECRET` 设置，再点击主页「启动 / 重连服务」**（若先启动再设密钥，连续 401 达 3 次手机端会自动暂停重试，核对密钥后再次点击即可）。

### Step 5：端到端验收
1. **公网心跳验收**：
   - 匿名存活探测（仅返回基础标识）：
     ```bash
     curl -sL "<WORKER_URL>/health"
     ```
   - 鉴权诊断探测（由用户在本地终端执行，勿将密钥打印到对话日志）：
     ```bash
     curl -sL -H "Authorization: Bearer <PHONE_SECRET>" "<WORKER_URL>/health"
     ```
     确认返回 `phone_online: true`、`direct_online: true`、`poll_waiters >= 1`、`phone.accessibility_enabled: true`。
2. **远程 MCP 接入（ChatGPT / Claude / 其他 OAuth 客户端）**：
   - 在 ChatGPT 网页端 → Settings → Apps / Connectors → Create：
     - MCP URL：`<WORKER_URL>/mcp`
     - Auth：`OAuth`
   - 如需接入除 ChatGPT / Claude 以外的其他远程 OAuth MCP 客户端（如 Google Gemini 自定义连接器），在 `cloud/wrangler.local.jsonc` 的 `vars.ALLOWED_REDIRECT_HOSTS` 中显式追加目标回调域名并重新部署（示例见 `cloud/wrangler.jsonc.example`）。
   - 在弹出的授权页粘贴手机端复制的连接码完成绑定。
3. **本地 Stdio MCP 接入（可选）**：
   参考 `mcp-bridge/mcp_config.example.json`，设置环境变量 `PHONE_AGENT_TOKEN` 后挂载 `mcp-bridge/phone-mcp-stdio.mjs`。

---

## English

> This section is written directly for **AI Coding Agents** (Claude Code, Codex, Antigravity, Cursor, etc.) executing deployment on behalf of a user.

### ⚠️ Agent Guardrails (Mandatory)
1. **Never log, print, or commit secrets**: Never echo `PHONE_SECRET`, pairing tokens, OAuth tokens, or private `cloud/wrangler.local.jsonc` files into logs, chat responses, or git commits.
2. **Respect human-in-the-loop boundaries**: `wrangler login`, Android Accessibility opt-in, Shizuku permission grant, opting into high-privilege toggles, and OAuth browser approval must be performed by the user.
3. **Do not weaken security controls**: Keep S256 PKCE, `Origin` checks, `redirect_uri` allowlisting, `network_security_config.xml`, and minimal `/health` output intact.

### Step 0: Preflight Checks
Verify locally:
- `node -v` (**>= 22.13**, required for built-in `node:sqlite` in `cloud/worker.test.cjs`), `java -version` (JDK 17+), `ANDROID_HOME` (Android SDK API 35)
- `adb devices` shows target **Android 11+ (API 30+)** device as `device`
- `npx wrangler whoami` (if unauthenticated, pause and ask the user to run `npx wrangler login`)

### Step 1: Test & Deploy Cloudflare Worker Gateway (Free `*.workers.dev`, $0)
1. Run Worker unit tests:
   ```bash
   node --test cloud/worker.test.cjs
   ```
2. Choose config:
   - **No custom domain (default, $0 Free Plan)**: Use the built-in `cloud/wrangler.jsonc` directly (`workers_dev: true` + SQLite Durable Object).
   - **Custom domain (optional)**: Copy `cloud/wrangler.jsonc.example` to `cloud/wrangler.local.jsonc` (git-ignored) and add your `routes`.
3. Deploy:
   ```bash
   cd cloud && npm install
   npx wrangler deploy -c wrangler.jsonc
   ```
   Record the deployed HTTPS `<WORKER_URL>`.

### Step 2: Build & Install Android App
```bash
./gradlew assembleDebug --no-daemon   # or .\gradlew.bat assembleDebug --no-daemon on Windows
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.teapot.phoneagent/.MainActivity
```
Optional (if user uses Termux background tasks):
```bash
adb shell pm grant com.teapot.phoneagent com.termux.permission.RUN_COMMAND
```
And inside Termux on the phone, enable external app execution (required by Termux, otherwise `termux_run` is rejected):
```bash
mkdir -p ~/.termux
grep -q '^allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings
```

### Step 3: Set Up Shizuku (For Offscreen Virtual Display)
> Note: Without root, Shizuku stops after a phone reboot and must be started again via Wireless Debugging or ADB.
1. Check if Shizuku is installed (`adb shell pm list packages | grep shizuku` on Linux/macOS, or `Select-String shizuku` on Windows PowerShell). If missing, ask user permission to install official Shizuku APK from `RikkaApps/Shizuku`.
2. Start Shizuku server via ADB:
   - Linux / macOS:
     ```bash
     APK_DIR=$(dirname "$(adb shell pm path moe.shizuku.privileged.api | sed 's/package://')")
     adb shell "$APK_DIR/lib/arm64/libshizuku.so"
     adb shell ps -A | grep shizuku_server
     ```
   - Windows PowerShell:
     ```powershell
     $apkPath = ((adb shell pm path moe.shizuku.privileged.api) -replace '^package:','').Trim()
     $apkDir = $apkPath.Substring(0, $apkPath.LastIndexOf('/'))
     adb shell "$apkDir/lib/arm64/libshizuku.so"
     adb shell ps -A | Select-String shizuku_server
     ```

### Step 4: Human-in-the-Loop On-Device Pairing (Pause for User)
Guide the user to:
1. Open **Teapot Phone-Agent** on Android → expand Settings (note: on-device UI labels are currently in Simplified Chinese):
   - Configure the Cloudflare Worker HTTPS domain (`<WORKER_URL>`).
   - Tap **Copy Pairing Token** (`复制连接码`) and run `npx wrangler secret put PHONE_SECRET -c cloud/wrangler.jsonc` directly in their terminal **before** starting the tunnel.
   - Enable Accessibility Service (**Teapot Phone-Agent 屏幕控制**; on Android 13+ sideloaded APKs, allow Restricted Settings in App Info first) and optional Storage/Calendar/Notification permissions.
   - Grant Shizuku permission via the on-screen button if using offscreen virtual display.
   - Optionally opt into high-privilege toggles (disabled by default): screen auto-renewal, Termux background execution, Shizuku accessibility auto-restore.
2. Tap **Start / Reconnect Service** (`启动 / 重连服务`).

### Step 5: End-to-End Verification
1. Verify anonymous `GET <WORKER_URL>/health` returns `{ "ok": true, "service": "teapot-phone-agent" }`, and authenticated `GET <WORKER_URL>/health` (with `Authorization: Bearer <PHONE_SECRET>`) returns `phone_online: true`, `direct_online: true`, `poll_waiters >= 1`.
2. Connect ChatGPT or Claude to `<WORKER_URL>/mcp` via OAuth (for other remote OAuth MCP clients, add their callback hostname to `ALLOWED_REDIRECT_HOSTS` as shown in `cloud/wrangler.jsonc.example`), or mount `mcp-bridge/phone-mcp-stdio.mjs` locally with `PHONE_AGENT_TOKEN`.
