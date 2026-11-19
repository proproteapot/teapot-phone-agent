# 安全模型与边界说明 / Security Policy & Threat Model

> [简体中文](#简体中文) | [English](#english)

---

## 简体中文

**Teapot Phone-Agent** 是面向个人自托管（Self-Hosted）的 Android MCP 手机桥。因为项目涉及屏幕无障碍、离屏虚拟屏、文件读写与可选后台命令，项目在默认配置上采取**最小权限与显式确认**原则：

### 1. 认证与传输安全
- **强制 HTTPS 与 `Authorization: Bearer` 校验**：公网通信强制走 HTTPS（Android 端通过 `network_security_config.xml` 仅对 `127.0.0.1` / `localhost` 放行明文 HTTP），严禁通过 URL Query 参数携带任何密钥或令牌。
- **OAuth 2.0 + S256 PKCE + 回调白名单**：Cloudflare Worker 网关与本机服务均强制校验 `code_challenge_method=S256`、`Origin` 同源以及精确的 HTTPS `redirect_uri` 白名单（默认仅放行 `chatgpt.com`、`chat.openai.com/aip/plugin-oauth/callback` 与 `claude.ai/api/mcp/auth_callback` 指定路径，Worker 端可通过环境变量 `ALLOWED_REDIRECT_HOSTS` 显式扩展）。
- **IP 维度防爆破与限流**：Cloudflare Worker 按 `CF-Connecting-IP` 分桶限流与锁定（避免跨 IP 拒绝服务），单 IP 连续 5 次输错连接码锁定 15 分钟（`HTTP 429`）；`/mcp` 与 `/_phone/*` 控制请求体上限 `256 KiB`、`/_phone/reply` 回传上限 `4 MiB`（允许截图与 1 MiB 文本，超限返回 `413`），OAuth 接口上限 `16 KiB`；本机 `127.0.0.1:8765` 移除了 `CORS: *` 并内置 CSP/X-Frame-Options/Origin/5 次锁定防护与 HTML 转义。
- **硬件密文、零明文落盘与密钥轮换联动吊销**：OAuth 访问令牌在 Durable Object SQLite 中仅存 SHA-256 哈希；一旦轮换 Cloudflare Worker 的 `PHONE_SECRET`，网关会自动检测到密钥指纹变更并立即作废全部既有 OAuth 令牌与授权码；手机端连接码由 `AndroidKeyStore`（AES-256-GCM）硬件加密存储，复制时标记 `EXTRA_IS_SENSITIVE`（Android 13+），并支持在设置中一键「重置连接码并吊销全部授权」。
- **最小暴露 `/health`**：匿名访问 `GET /health` 仅返回 `{ "ok": true, "service": "teapot-phone-agent" }`；仅携带 `Authorization: Bearer <PHONE_SECRET>` 时才返回在线心跳与设备诊断详情。

### 2. 高危能力默认关闭与人工确认边界
- **屏幕控制（默认本机人工确认 + 硬上限 + 防自我授权）**：
  - 远程 Agent 请求屏幕控制、`open_app`、`open_file_on_phone` 或 `virtual_display_*` 时，默认必须在手机本机点击通知或主页按钮批准后才开启临时会话（无操作 2 分钟结束、单次最长 10 分钟硬上限）；控制期间顶部常驻红色停止条，息屏或锁屏立即自动撤销并拒绝一切屏幕操作。
  - **防 Prompt Injection 自授权黑名单**：无障碍引擎强制禁止读取、截图、点击或远程启动本应用（`com.teapot.phoneagent`）、系统权限控制器、应用安装器及 Shizuku 授权界面，杜绝恶意页面诱导 Agent 自行点击「允许」或开启高危开关。
  - 仅当用户在手机端设置中手动打开「免确认自动续期」开关时，才允许宽松自动续期。
- **离屏虚拟屏防误触与零回退**：所有屏幕操作均支持 `display_id`、`expected_package` 与 `expected_window` 守卫；同时最多允许创建 2 个虚拟屏；URL 启动仅允许 `http://` / `https://`；虚拟屏失败直接报错，**严禁自动回退到主屏操作**。
- **Shizuku 权限留在手机端**：云端只能调用固定、参数化的具体工具（如 `virtual_display_create`、`open_app_on_display`），绝不向云端暴露通用任意 ADB Shell 接口或特权文件写入接口。
- **Termux 后台命令（默认关闭）**：`termux_run` / `termux_logs` / `termux_stop` 以及实时刷新均受手机端开关门禁保护（默认关闭），`probe_url` / `probe_port` 严格限定为本机回环 `http://127.0.0.1` / `localhost`（禁止探测 8765 且禁止 HTTP 重定向），防止 SSRF。
- **文件系统安全**：删除操作默认移入 `Download/.PhoneAgentTrash/` 回收站；覆盖写入采用原子临时文件替换；压缩/解压内置路径穿越校验、符号链接跳过、5000 条目与 512 MiB 解压上限；`LocalFileProvider` 独立封锁 `Android/` 目录；`open_file_on_phone` 禁止远程唤起 `.apk` 安装包。
- **构建与默认行为**：首次安装默认不自动开启公网外联（需用户显式点击「启动 / 重连服务」）；`app/build.gradle` 已配置非调试 `release` 构建类型供日用签名安装。

### 3. 已知风险与安全边界（请自托管前阅读）
本项目属于面向高级用户/开发者的**高权限个人自托管工具**，在开启无障碍、Shizuku、全文件访问或 Termux 能力后，请注意以下固有边界：
- **高权限能力与提示词注入（Prompt Injection）风险**：虽已内置受保护包黑名单（拦截本应用、AOSP 及小米/三星/OPPO/vivo/华为权限管理器、安装器与 Shizuku 界面），但远程 LLM 在操作第三方网页、聊天消息、系统设置（`com.android.settings`）或分屏/小窗界面时，仍可能受页面恶意文本诱导执行非预期点击。建议保持默认的「本机人工确认模式」，不要在包含高敏感金融/密码/系统权限界面的场景下保持会话开启。
- **共享存储与日历工具权限边界**：`fs_*` 文件管理（包括读取文本、覆写以及显式传 `permanent=true` 的永久删除）与系统日历读写属于直接系统 API 工具，**不经过屏幕控制本机弹窗确认**。仅在你信任所绑定的远程客户端时授予 `MANAGE_EXTERNAL_STORAGE`（完整文件访问）与日历权限；如果只需要有限目录只读，可优先使用 SAF 单目录授权。
- **离屏虚拟屏特性**：基于 Android `DisplayManagerGlobal` 创建的虚拟屏带有 `FLAG_ALWAYS_UNLOCKED` 标志以支持后台独立运行；任务结束后应及时调用 `virtual_display_destroy`（该工具免审批以便随时清理）或在手机端停止服务释放虚拟屏。
- **宽松开关与 Termux 风险**：若用户在设置中手动开启「屏幕控制免确认自动续期」「允许后台 Termux 命令执行」或「Shizuku 自动恢复无障碍」，代表你完全信任所绑定的远程 MCP 客户端与自托管网关，请妥善保管 `PHONE_SECRET` 并定期使用「重置连接码」轮换凭据。
- **本机 HTTP 服务组件**：Android 端内嵌 `NanoHTTPD 2.3.1` 仅绑定 `127.0.0.1:8765` 回环地址，并已移除 `CORS: *`、限制请求体体积与增加 Origin/CSP/防爆破校验；未来版本计划替换为更现代的轻量 HTTP 实现。
- **单人自托管免责**：本项目不提供多租户公共服务器，请仅在**自己拥有且明确授权**的设备上自托管，切勿在公共或共享设备上运行，亦不得用于未授权访问或自动化滥用。

### 4. 漏洞上报
- 如发现安全漏洞，请勿在公开 Issue 中披露利用细节，请通过 GitHub Security Advisory 或仓库维护者私密渠道报告。

---

## English

**Teapot Phone-Agent** is a personal, self-hosted Android MCP bridge. Because it touches accessibility controls, offscreen virtual displays, file management, and optional background execution, it enforces **least privilege and explicit opt-in** by default:

### 1. Authentication & Transport Security
- **HTTPS & Header Bearer Only**: All remote traffic uses HTTPS (`network_security_config.xml` restricts cleartext HTTP strictly to `127.0.0.1` and `localhost`). Credentials or tokens are never accepted in URL query strings.
- **OAuth 2.0 + Mandatory S256 PKCE + Redirect Allowlist**: Both the Cloudflare Worker gateway and local server enforce `S256` PKCE, strict `Origin` checks, and an explicit HTTPS `redirect_uri` allowlist (exact paths on `chatgpt.com`, `chat.openai.com/aip/plugin-oauth/callback`, and `claude.ai/api/mcp/auth_callback` by default; Worker-extensible via `ALLOWED_REDIRECT_HOSTS`).
- **IP-Scoped Rate Limiting & Brute-Force Lockout**: Cloudflare Worker tracks rate limits and 5-strike 15-minute lockouts per `CF-Connecting-IP` (`HTTP 429`) to prevent cross-IP DoS, caps `/mcp` & `/_phone/*` control bodies at `256 KiB` and `/_phone/reply` responses at `4 MiB` (`413`), and removes legacy `/_phone/register` relay paths. Local `127.0.0.1:8765` removes `CORS: *`, adds CSP/Origin/lockout headers, and HTML-escapes all OAuth form fields.
- **Hardware-Backed Storage & Automatic Revocation on Secret Rotation**: OAuth tokens are stored as SHA-256 hashes in Durable Object SQLite, and rotating `PHONE_SECRET` on the Cloudflare Worker automatically invalidates all previously issued OAuth tokens and authorization codes. The on-device pairing token is encrypted with `AndroidKeyStore` (AES-256-GCM), marked `EXTRA_IS_SENSITIVE` on copy (Android 13+), and rotatable via a dedicated UI action.
- **Minimal Anonymous `/health`**: Unauthenticated `GET /health` returns only `{ "ok": true, "service": "teapot-phone-agent" }`; full phone online/heartbeat details require `Authorization: Bearer <PHONE_SECRET>`.

### 2. Opt-In Boundaries for High-Privilege Features
- **Screen Control (Manual Approval + Hard Cap + Protected Package Guard)**:
  - Remote screen control, `open_app`, `open_file_on_phone`, and `virtual_display_*` require explicit on-device approval by default (2-minute idle timeout, 10-minute absolute hard cap, persistent red stop bar, immediate revocation on screen-off or lock).
  - **Anti-Prompt-Injection Protected Packages**: The accessibility engine refuses to read, screenshot, tap, or launch this app (`com.teapot.phoneagent`), system permission controllers, package installers, or Shizuku UIs.
  - Auto-renewal is strictly opt-in inside app settings.
- **Offscreen Display Guardrails**: Screen actions support `display_id`, `expected_package`, and `expected_window` preconditions; concurrent virtual displays are capped at 2; URL launches allow only `http://` / `https://`; failures **never** silently fall back to the primary display (`display 0`).
- **Scoped Shizuku Backend**: Shizuku privileges remain on-device behind fixed, parameterized actions with no generic shell or privileged file-write command exposed.
- **Termux Background Commands (Disabled by Default)**: All Termux execution/log/refresh IPC paths check the opt-in toggle; `probe_url` / `probe_port` are strictly limited to loopback `http://127.0.0.1` / `localhost` (excluding port `8765`, redirects disabled).
- **Filesystem Safety**: Deletions default to `Download/.PhoneAgentTrash/`; overwrites are atomic; zip/unzip guards against traversal, symlinks, >5000 entries, and >512 MiB extraction; `LocalFileProvider` independently blocks `Android/`; `open_file_on_phone` blocks `.apk` installs.

### 3. Known Risks & Limitations (Read Before Self-Hosting)
This is a **high-privilege personal self-hosted tool** for developers and power users. When enabling Accessibility, Shizuku, All-Files Access, or Termux execution, keep these inherent boundaries in mind:
- **Prompt Injection & Multi-Window Surfaces**: While a built-in protected-package guard blocks this app, AOSP/OEM permission controllers (Xiaomi/Samsung/OPPO/vivo/Huawei), package installers, and Shizuku UIs, an LLM driving third-party apps, web pages, Android Settings (`com.android.settings`), or split-screen/pop-up windows can still be tricked by untrusted content. Keep the default on-device manual approval mode enabled and avoid running sessions near banking/password screens.
- **Shared Storage & Calendar Tool Boundaries**: `fs_*` filesystem tools (including text reads, overwrites, and explicit `fs_delete(permanent=true)`) and calendar read/write tools invoke Android system APIs directly **without** triggering the on-device screen-control approval dialog. Grant `MANAGE_EXTERNAL_STORAGE` and calendar permissions only if you fully trust your bound MCP client; otherwise prefer single-directory SAF grants.
- **Virtual Display Lifecycle**: Offscreen virtual displays use `FLAG_ALWAYS_UNLOCKED` so apps can run independently in the background. Destroy displays promptly via `virtual_display_destroy` (ungated for safe cleanup) or stop the service when finished.
- **Opt-In Relaxed Toggles & Termux**: Enabling auto-renewal, Termux execution, or Shizuku accessibility auto-restore grants broad autonomy to your bound MCP client. Protect your `PHONE_SECRET` and rotate it via the app UI whenever needed.
- **Local Loopback HTTP Server**: The embedded `NanoHTTPD 2.3.1` binds strictly to `127.0.0.1:8765` with `CORS: *` removed and strict Origin/CSP/rate-limit/body-cap checks applied; migrating to a modern lightweight HTTP server is planned for a future release.
- **Personal Self-Hosting Only**: No multi-tenant hosted service is provided. Deploy strictly on devices and accounts you own and control.

### 4. Vulnerability Reporting
- Please report security vulnerabilities privately via GitHub Security Advisories rather than public issues.
