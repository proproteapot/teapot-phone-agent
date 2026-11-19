# Teapot Phone-Agent

**免电脑、免 Root 的自托管 Android MCP 手机桥：让 AI Agent 在真离屏虚拟屏里操作 App、在后台运行 Termux 任务，同时主屏完全留给你自己用。**

**Self-hosted Android MCP Bridge (No PC, No Root): Let AI Agents operate Android apps on a true offscreen virtual display and run background Termux services while your primary screen stays untouched.**

> [简体中文](#简体中文) | [English](#english) | [🤖 Agent 一键部署指南](AGENT_DEPLOY.md) | [🔒 安全模型](SECURITY.md)

---

## 🤖 懒得看文档？直接让你的 AI Agent 一键部署

把手机通过 USB 连上电脑（开启 USB 调试），把下面这段话直接发给 **Claude Code / Codex / Antigravity / Cursor**：

```text
请阅读本仓库的 AGENT_DEPLOY.md，按步骤帮我把 Teapot Phone-Agent 部署到我的 Cloudflare 账号和已连接的 Android 手机上，并完成端到端验收。涉及登录授权或手机本机确认步骤时请暂停提示我操作。
```

---

<a id="简体中文"></a>
## 简体中文

### 为什么做 Teapot Phone-Agent？

现有的手机 Agent / MCP 方案通常面临三个痛点：要么必须一直连着电脑跑 ADB，要么一干活就霸占你的手机主屏，要么只支持点屏幕、缺少文件/相册/后台服务等高频能力。

**Teapot Phone-Agent** 将整条链路打通：
- **📱 免电脑公网直连（纯 Java Reverse-RPC）**：手机端直接与你自托管的 Cloudflare Worker + Durable Object 建立长轮询双向通道，**零第三方二进制依赖**，彻底免疫国产 ROM（如 HyperOS Greeze）对子进程的后台冻结；出门在外也能直接通过 ChatGPT 手机 App 等远程 OAuth MCP 客户端连回自己的手机。
- **👻 真离屏虚拟屏（Shizuku，免 Root，主屏 0 遮挡）**：通过 `Shizuku` 拉起独立 `app_process` 离屏守护进程（`VirtualDisplayDaemon`），创建真正的 `type VIRTUAL` 离屏虚拟屏（绕开 Android 16 上 Shizuku `UserService` 的 `LoadedApk` 空指针问题）。你在主屏继续聊天或刷手机，Agent 在隐形副屏里独立操作普通 App，**绝不在主屏弹叠加小窗、失败绝不自动切回主屏**。
- **🛡️ 防误触守卫与精准反馈**：所有屏幕动作支持 `display_id`、`expected_package`、`expected_window` 前置守卫，优先按控件 `view_id` 定位；分离返回 `action_delivered` / `ui_changed` / `goal_reached`；修正终端 `submit_enter` 语义，严格区分 `text_filled` / `enter_submitted` / `executed`。
- **⚙️ Termux 后台命令与服务管理（默认关闭，需手动开启）**：基于 `RUN_COMMAND` + `setsid` 独立进程组管理一次性命令与长期服务，支持实时日志尾部读取、本机 HTTP/TCP 端口就绪探测、按进程组彻底停止、断连重启后按原 `task_id` 续查状态。
- **⚡ 秒级断连诊断**：手机主动停止服务时立即通知云端下线，断连期间 **~200ms** 明确返回 `phone_offline` 与失败阶段（`stage`、`retryable`），告别数十秒盲等和模糊 `Internal error`。
- **🗂️ 完整本机日常工具**：相册概览、共享存储搜索/整理/移动/复制/压缩解压（默认进回收站防误删）、系统日历读写与闭环闹钟核验。

### 系统架构

```text
ChatGPT / 远程 OAuth MCP 客户端                  本地桌面 MCP 客户端 (Claude Code / Codex / Cursor)
              │ HTTPS (OAuth 2.0 + S256 PKCE)                     │ stdio (phone-mcp-stdio.mjs)
              ▼                                                   ▼
   Cloudflare Worker + Durable Object                       adb forward 18765
   (自托管公网网关 · cloud/worker.js)                               │
              ▲ HTTPS Reverse-RPC 长轮询                           │
              └───────────────────────┐                           │
                                      ▼                           ▼
                          ┌───────────────────────────────────────────┐
                          │    Teapot Phone-Agent (Android App)       │
                          │         127.0.0.1:8765 /mcp               │
                          ├──────────────┬───────────────┬────────────┤
                          │ 无障碍引擎    │ Shizuku 后端   │ Termux 桥  │
                          │ (多屏观测/操作)│ (真离屏虚拟屏) │ (进程组服务)│
                          └──────────────┴───────────────┴────────────┘
```

### 核心 MCP 工具一览

| 分类 | 工具 | 说明 |
|---|---|---|
| **连接诊断** | `phone_health`, `list_displays`, `shizuku_status` | 轻量健康检查（无障碍、Shizuku、Termux、可用屏幕列表与前台包名） |
| **真离屏虚拟屏** | `virtual_display_create`, `virtual_display_destroy`, `open_app_on_display` | 创建/销毁 `type VIRTUAL` 离屏副屏并在指定屏启动 App（主屏 0 遮挡） |
| **闭环屏幕控制** | `request_screen_control`, `screen_observe`, `screen_click_text`, `screen_tap`, `screen_swipe`, `screen_set_text`, `screen_back`, `screen_wait`, `screen_screenshot`, `end_screen_control` | 全部支持 `display_id` 与 `expected_package` / `expected_window` 守卫，优先 `view_id` 定位 |
| **后台任务管理** | `termux_run`, `termux_status`, `termux_logs`, `termux_stop` | 需手机端手动开启开关；支持一次性命令与长期服务、本机 HTTP 探测与进程组启停 |
| **文件与相册** | `album_summary`, `fs_list_dir`, `fs_search`, `fs_mkdir`, `fs_move`, `fs_copy`, `fs_delete`, `fs_read_text_path`, `fs_write_text_path`, `fs_zip`, `fs_unzip`, `open_file_on_phone` | 完整共享存储与 SAF 文件管理，默认安全回收站与路径穿越防护 |
| **日历与闹钟** | `list_calendars`, `list_calendar_events`, `create_calendar_event`, `request_alarm`, `list_installed_apps`, `open_app` | 日历读写、应用查询与闹钟设置闭环核验 |

### 快速手动部署（3 分钟，0 费用，无需购买域名）

> 💡 **托管方式三选一**：
> 1. **Cloudflare 免费套餐自托管（推荐，$0 / 无需自购域名）**：Cloudflare Workers Free Plan 已支持 SQLite Durable Object 并赠送免费 `https://<worker>.<subdomain>.workers.dev` 域名。仓库已内置可直接部署的 `cloud/wrangler.jsonc`，执行 1 条命令即可拥有专属加密网关。
> 2. **自定义域名托管（可选）**：复制 `cloud/wrangler.jsonc.example` 为 `cloud/wrangler.local.jsonc`（已被 `.gitignore` 忽略），填入自定义域名 `routes` 后部署。
> 3. **纯本地 0 云端模式**：不部署任何云服务，直接通过 USB / ADB + `mcp-bridge/phone-mcp-stdio.mjs` 连接电脑端 MCP 客户端。
>
> 完整细节与 AI 自动执行步骤见 [AGENT_DEPLOY.md](AGENT_DEPLOY.md)。

1. **部署自托管 Cloudflare 网关（免费 `*.workers.dev`，0 域名成本）**：
   ```bash
   node --test cloud/worker.test.cjs
   cd cloud && npm install
   npx wrangler deploy -c wrangler.jsonc
   ```
   > 也可以直接在仓库页面点击 **Deploy to Cloudflare**（配置 Cloudflare 账号即可从仓库一键拉起 `cloud/wrangler.jsonc`）。
2. **编译并安装 Android APK**（要求 JDK 17 + Android SDK API 35；日用建议打包签名 `assembleRelease`）：
   ```bash
   ./gradlew assembleDebug --no-daemon
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. **配对与授权**：
   - 在手机打开 App → 展开「设置与权限管理」→ 填入你的 Worker HTTPS 域名（如 `https://teapot-phone-agent.<your-subdomain>.workers.dev`）并复制连接码；
   - 在电脑执行 `npx wrangler secret put PHONE_SECRET -c cloud/wrangler.jsonc` 粘贴该连接码；
   - 在手机开启无障碍服务（如需离屏虚拟屏，先安装并激活 Shizuku 后在 App 主页点一键授权）；
   - 点击 **「启动 / 重连服务」**，使用 `curl -H "Authorization: Bearer <你的连接码>" https://<your-worker>/health` 确认 `phone_online: true`（匿名访问 `/health` 仅返回 `{ "ok": true, "service": "teapot-phone-agent" }`）。
4. **接入客户端**：
   - **ChatGPT 远程接入**：在 ChatGPT Settings → Apps / Connectors 中填入 `https://<your-worker>/mcp`，鉴权方式选 `OAuth`，授权页粘贴本机连接码即可。
   - **本地桌面 MCP 接入**：参考 [mcp_config.example.json](mcp-bridge/mcp_config.example.json) 配置 `PHONE_AGENT_TOKEN` 挂载 `mcp-bridge/phone-mcp-stdio.mjs`。

### 真机实测记录
- 已在 **Redmi K80 Ultra（Android 16 / HyperOS OS3.0）** 上通过全链路真机验收：
  - `VirtualDisplayDaemon` 成功创建真离屏 `type VIRTUAL` 副屏（`FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED`），系统浏览器、Chrome、夸克、Termux、QQ（`com.tencent.mobileqq`）均可在副屏独立运行且主屏完全不切换；
  - 副屏截图、控件 `view_id` 输入提交、副屏返回键、窗口变化守卫中止（`target_window_changed`）均通过；
  - Termux 后台长期服务启动 → HTTP 探测就绪 → 进程组停止 → 重启恢复及断连 200ms 快返与重启后按原 `task_id` 续查均通过。

---

<a id="english"></a>
## English

### Why Teapot Phone-Agent?

Most Android MCP / phone-agent tools either require a tethered PC running ADB, hijack your primary screen whenever the agent acts, or expose only bare tap/screenshot primitives.

**Teapot Phone-Agent** unifies the full stack into a self-hosted, security-first bridge:
- **📱 PC-Free Public Access (Pure Java Reverse-RPC)**: Your phone connects directly to your self-hosted Cloudflare Worker + Durable Object over HTTPS long-polling—**zero bundled native tunnel binaries** and resilient against aggressive OEM background process freezers (e.g. HyperOS Greeze). Connect directly from ChatGPT mobile or any OAuth 2.0 + PKCE MCP client on the go.
- **👻 True Offscreen Virtual Display (Shizuku, No Root, Zero Main-Screen Overlap)**: Spawns a dedicated `app_process` daemon (`VirtualDisplayDaemon`) under Shizuku (`uid=2000`) to create a genuine `type VIRTUAL` offscreen display (bypassing Android 16 `LoadedApk` NPE issues in Shizuku's built-in `UserService`). You keep using your main screen normally while the agent drives apps invisibly in the background—no floating overlay clutter, and virtual display errors **never** silently fall back to `display 0`.
- **🛡️ Precondition Guardrails & Honest Action Feedback**: Every screen action accepts `display_id`, `expected_package`, and `expected_window` guards, prefers explicit `view_id` targeting, separates `action_delivered` / `ui_changed` / `goal_reached`, and distinguishes `text_filled` / `enter_submitted` / `executed` for `submit_enter`.
- **⚙️ Background Termux Command & Service Manager (Opt-In, Disabled by Default)**: Uses `RUN_COMMAND` + `setsid` process groups for one-shot commands and long-running local services, with live log tailing, local HTTP/TCP readiness probes, clean process-group teardown, and persistent `task_id` recovery across disconnects.
- **⚡ Sub-Second Disconnect Diagnostics**: Explicit `/_phone/offline` signaling and short handoff timeouts eliminate 16–24s blind hangs and vague `Internal error` responses, returning structured `error_code`, `stage`, and `retryable` metadata.
- **🗂️ Everyday On-Device Tools**: Album summaries, shared-storage search/move/copy/zip/unzip (safe trash by default), calendar read/write, and closed-loop alarm verification.

### One-Click Agent Deployment

Plug your Android phone in with USB debugging enabled and hand [AGENT_DEPLOY.md](AGENT_DEPLOY.md) to **Claude Code / Codex / Antigravity / Cursor**:

```text
Read AGENT_DEPLOY.md in this repo and deploy Teapot Phone-Agent to my Cloudflare account and connected Android device, then run end-to-end verification. Pause whenever manual login or on-device confirmation is required.
```

### Quick Manual Setup (3 Minutes, $0 Hosting, No Custom Domain Needed)

> 💡 **Three Hosting Options**:
> 1. **Cloudflare Free Plan (Recommended, $0 / No Domain Needed)**: Cloudflare Workers Free Plan includes SQLite Durable Objects and a free `https://<worker>.<subdomain>.workers.dev` HTTPS URL. The repo ships a ready-to-deploy `cloud/wrangler.jsonc`.
> 2. **Custom Domain (Optional)**: Copy `cloud/wrangler.jsonc.example` to `cloud/wrangler.local.jsonc` (git-ignored) and add your custom `routes`.
> 3. **Zero-Cloud Local Mode**: Skip cloud deployment completely and connect desktop MCP clients over USB/ADB via `mcp-bridge/phone-mcp-stdio.mjs`.

1. **Test & deploy the Cloudflare Worker gateway**:
   ```bash
   node --test cloud/worker.test.cjs
   cd cloud && npm install
   npx wrangler deploy -c wrangler.jsonc
   ```
2. **Build & install the Android APK** (JDK 17 + Android SDK API 35; use signed `assembleRelease` for daily use):
   ```bash
   ./gradlew assembleDebug --no-daemon
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. **Pair & authorize on-device**:
   - Open **Teapot Phone-Agent**, set your Worker HTTPS URL, and copy the hardware-encrypted pairing token.
   - Run `npx wrangler secret put PHONE_SECRET -c cloud/wrangler.jsonc` and paste the pairing token.
   - Enable Accessibility Service (and install/start Shizuku if using offscreen virtual displays).
   - Tap **Start / Reconnect Service** and verify with `curl -H "Authorization: Bearer <PHONE_SECRET>" https://<your-worker>/health` that `phone_online: true` (anonymous `GET /health` returns only `{ "ok": true, "service": "teapot-phone-agent" }`).
4. **Connect your MCP client**:
   - **Remote OAuth MCP (e.g. ChatGPT)**: Add `https://<your-worker>/mcp` with `OAuth` authentication and enter your pairing token on the authorization page.
   - **Local Stdio MCP**: Configure `mcp-bridge/phone-mcp-stdio.mjs` with `PHONE_AGENT_TOKEN` using [mcp_config.example.json](mcp-bridge/mcp_config.example.json).

### Security & License
- See [SECURITY.md](SECURITY.md) for the complete threat model, OAuth 2.0 + S256 PKCE enforcement, default-off high-privilege switches, and safety guardrails.
- Licensed under the [Apache License 2.0](LICENSE).
