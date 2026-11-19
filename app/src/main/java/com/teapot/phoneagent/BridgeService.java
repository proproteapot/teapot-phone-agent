package com.teapot.phoneagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;

/** An explicitly started, loopback-only prototype. It is not a public server. */
public final class BridgeService extends Service {
    private LocalServer server;
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) { ScreenApprovalActivity.end(context, "屏幕关闭，控制已结束"); }
    };
    static volatile boolean running;
    private static final String CHANNEL = "bridge_service";
    static final String PREFS = "bridge_private";
    static final String TOKEN = "token";

    @Override public void onCreate() {
        super.onCreate();
        android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, filter);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "手机助手桥运行状态", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("手机助手桥正在运行").setContentText("仅监听手机本机；打开应用可停止").setContentIntent(open).setOngoing(true).build();
        startForeground(1, notification);
        OAuthManager oauth = new OAuthManager(this);
        oauth.getOrCreatePairingToken();
        ShizukuBridge.init(this);
        TermuxCommandManager.ensureLoaded(this);
        try {
            server = new LocalServer(oauth);
            server.start(5000, false);
            running = true;
            if (TunnelManager.isAutoStartEnabled(this) && !TunnelManager.running) {
                new Thread(() -> TunnelManager.start(BridgeService.this)).start();
            }
        } catch (IOException e) { stopSelf(); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    @Override public void onDestroy() { running = false; TunnelManager.stop(); ShizukuBridge.cleanupAllVirtualDisplays(); ScreenApprovalActivity.end(this, "服务停止，控制已结束"); if (server != null) server.stop(); unregisterReceiver(screenOff); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    private final class LocalServer extends NanoHTTPD {
        private static final long MAX_OAUTH_BODY_BYTES = 16384L;
        private static final long MAX_MCP_BODY_BYTES = 262144L;
        private final OAuthManager oauth;
        LocalServer(OAuthManager oauth) { super("127.0.0.1", 8765); this.oauth = oauth; }
        @Override public Response serve(IHTTPSession session) {
            if (session.getMethod() == Method.OPTIONS) {
                return response(Response.Status.METHOD_NOT_ALLOWED, "{}");
            }
            String reqPath = session.getUri();
            Method httpMethod = session.getMethod();
            String baseUrl = resolveBaseUrl(session);
            try {
                if (httpMethod == Method.GET && ("/.well-known/oauth-protected-resource".equals(reqPath) || "/.well-known/oauth-protected-resource/mcp".equals(reqPath))) {
                    return response(Response.Status.OK, OAuthManager.buildProtectedResourceMeta(baseUrl).toString());
                }
                if (httpMethod == Method.GET && ("/.well-known/oauth-authorization-server".equals(reqPath) || "/.well-known/openid-configuration".equals(reqPath))) {
                    return response(Response.Status.OK, OAuthManager.buildAuthServerMeta(baseUrl).toString());
                }
                if (httpMethod == Method.POST && "/oauth/register".equals(reqPath)) {
                    if (!checkContentLength(session, MAX_OAUTH_BODY_BYTES)) return response(Response.Status.BAD_REQUEST, "{}");
                    Map<String, String> body = new java.util.HashMap<>();
                    session.parseBody(body);
                    return response(Response.Status.CREATED, oauth.registerClient(body.get("postData")).toString());
                }
                if (httpMethod == Method.GET && "/oauth/authorize".equals(reqPath)) {
                    Map<String, String> q = new java.util.HashMap<>(session.getParms());
                    if (!OAuthManager.isAllowedCallback(q.get("redirect_uri"))
                            || !OAuthManager.isValidS256Challenge(q.get("code_challenge"), q.get("code_challenge_method"))) {
                        return response(Response.Status.BAD_REQUEST, "{\"error\":\"invalid_request\"}");
                    }
                    Response r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", oauth.renderAuthorizeHtml(q));
                    addHtmlSecurityHeaders(r);
                    return r;
                }
                if (httpMethod == Method.POST && "/oauth/authorize".equals(reqPath)) {
                    if (!checkContentLength(session, MAX_OAUTH_BODY_BYTES)) return response(Response.Status.BAD_REQUEST, "{}");
                    String origin = session.getHeaders().get("origin");
                    if (origin != null && !origin.isEmpty() && !isAllowedLocalOrigin(origin, baseUrl)) {
                        return response(Response.Status.FORBIDDEN, "{\"error\":\"invalid_origin\"}");
                    }
                    if (OAuthManager.isLocalAuthLocked()) {
                        Response locked = newFixedLengthResponse(status429(), "text/html; charset=utf-8",
                                "<!doctype html><meta charset='utf-8'><h3>尝试次数过多，请 15 分钟后再试或在手机应用中重置连接码。</h3>");
                        addHtmlSecurityHeaders(locked);
                        return locked;
                    }
                    Map<String, String> params = parseAllParams(session);
                    String secret = params.get("secret");
                    String pairing = oauth.getOrCreatePairingToken();
                    boolean csrfOk = oauth.consumeCsrfNonce(params.get("csrf"));
                    boolean secretOk = secret != null && MessageDigest.isEqual(
                            pairing.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            secret.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    OAuthManager.recordLocalAuthAttempt(csrfOk && secretOk);
                    if (!csrfOk || !secretOk) {
                        Response denied = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/html; charset=utf-8",
                                "<!doctype html><meta charset='utf-8'><h3>授权失败：本机连接码不匹配或页面已过期，请返回重试。</h3>");
                        addHtmlSecurityHeaders(denied);
                        return denied;
                    }
                    String redirectUri = params.get("redirect_uri");
                    if (!OAuthManager.isAllowedCallback(redirectUri)) return response(Response.Status.BAD_REQUEST, "{}");
                    String code = oauth.issueAuthCode(redirectUri, params.get("code_challenge"), params.get("code_challenge_method"));
                    android.net.Uri.Builder loc = android.net.Uri.parse(redirectUri).buildUpon().appendQueryParameter("code", code);
                    String state = params.get("state");
                    if (state != null && !state.isEmpty()) loc.appendQueryParameter("state", state);
                    Response redirect = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
                    redirect.addHeader("Location", loc.build().toString());
                    redirect.addHeader("Cache-Control", "no-store");
                    return redirect;
                }
                if (httpMethod == Method.POST && "/oauth/token".equals(reqPath)) {
                    if (!checkContentLength(session, MAX_OAUTH_BODY_BYTES)) return response(Response.Status.BAD_REQUEST, "{}");
                    try {
                        Map<String, String> params = parseAllParams(session);
                        return response(Response.Status.OK, oauth.exchangeToken(params).toString());
                    } catch (Exception e) {
                        return response(Response.Status.BAD_REQUEST, new JSONObject()
                                .put("error", "invalid_grant")
                                .put("error_description", e.getMessage() == null ? "token exchange failed" : e.getMessage()).toString());
                    }
                }
            } catch (Exception e) {
                return response(Response.Status.BAD_REQUEST, "{}");
            }
            if (!"/mcp".equals(reqPath)) return response(Response.Status.NOT_FOUND, "{}");
            String auth = session.getHeaders().get("authorization");
            if (!oauth.verifyBearerHeader(auth)) {
                Response unauth = response(Response.Status.UNAUTHORIZED, "{}");
                unauth.addHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + baseUrl + "/.well-known/oauth-protected-resource\"");
                return unauth;
            }
            if (httpMethod == Method.DELETE) return response(Response.Status.OK, "{}");
            if (httpMethod != Method.POST) return response(Response.Status.METHOD_NOT_ALLOWED, "{}");
            try {
                if (!checkContentLength(session, MAX_MCP_BODY_BYTES)) return response(Response.Status.BAD_REQUEST, "{}");
                Map<String, String> body = new java.util.HashMap<>(); session.parseBody(body);
                JSONObject req = new JSONObject(body.get("postData"));
                String method = req.getString("method");
                if (!req.has("id")) return response(Response.Status.ACCEPTED, "");
                JSONObject result;
                if ("initialize".equals(method)) {
                    String harnessInstructions = "你已接入 Teapot Phone-Agent 自主闭环执行框架 (Autonomous Phone Agent Harness)。\n"
                            + "【核心工作准则：多步自主闭环 + 离屏副屏优先，禁止无故抢占主屏】\n"
                            + "1. 【后台/不打扰主屏操作普通 App】：当用户要求「在后台」「不切主屏」「趁我用手机时」查看或操作任意 App（如 QQ、浏览器等），禁止直接用主屏 open_app 或只看通知栏就放弃！请优先走离屏虚拟屏链路：\n"
                            + "   - 先调用 `virtual_display_create` 创建隐形离屏虚拟屏（拿到 `display_id`）；\n"
                            + "   - 调用 `open_app_on_display(app, display_id)` 在副屏启动目标 App；\n"
                            + "   - 使用带 `display_id` 和 `expected_package` 守卫的 `screen_observe` / `screen_click_text` / `screen_tap(view_id=...)` / `screen_swipe` / `screen_set_text` / `screen_back` / `screen_screenshot` 完成多步闭环读取与操作；\n"
                            + "   - 任务完成后务必调用 `virtual_display_destroy(display_id)` 销毁副屏。若副屏操作报错，直接回报失败原因，严禁自动切回主屏。\n"
                            + "2. 【后台命令与本地服务】：需要运行脚本或管理本地服务时，优先使用 `termux_run` / `termux_status` / `termux_logs` / `termux_stop`（如提示未开启则请用户在手机设置中开启开关），通过 `probe_port`/`probe_url` 确认就绪。\n"
                            + "3. 【多步闭环与诊断】：可先调用 `phone_health` 排查无障碍/Shizuku/屏幕状态；交互工具会返回最新 `screen` 快照、`action_delivered`、`ui_changed`、`goal_reached` 与 `feedback`，请按 Observe → Act → Verify 连续执行直到完成用户目标。\n"
                            + "4. 节点操作优先使用 `view_id`（控件 ID）或 `screen_click_text`，坐标与 `screen_screenshot` 作为补充；遇到密码/支付或需人工批准时请暂停提示用户。";
                    result = new JSONObject().put("protocolVersion", "2025-03-26")
                            .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                            .put("instructions", harnessInstructions)
                            .put("serverInfo", new JSONObject().put("name", "phone-agent-harness").put("version", "0.2.0"));
                } else if ("ping".equals(method)) result = new JSONObject();
                else if ("tools/list".equals(method)) {
                    JSONObject schema = new JSONObject().put("type", "object").put("properties", new JSONObject());
                    JSONObject eventProperties = new JSONObject().put("calendar_id", field("integer", "目标日历 ID"))
                            .put("title", field("string", "日程标题"))
                            .put("start_ms", field("integer", "开始时间，Unix 毫秒"))
                            .put("end_ms", field("integer", "结束时间，Unix 毫秒"))
                            .put("timezone", field("string", "IANA 时区，例如 Asia/Shanghai"));
                    JSONObject eventSchema = new JSONObject().put("type", "object").put("properties", eventProperties)
                            .put("required", new JSONArray().put("calendar_id").put("title").put("start_ms").put("end_ms").put("timezone"));
                    JSONObject querySchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject().put("start_ms", field("integer", "开始时间，Unix 毫秒"))
                                    .put("end_ms", field("integer", "结束时间，Unix 毫秒，范围不超过 90 天")))
                            .put("required", new JSONArray().put("start_ms").put("end_ms"));
                    JSONObject fileSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject().put("root_uri", field("string", "从 list_file_roots 获得的授权目录 URI")))
                            .put("required", new JSONArray().put("root_uri"));
                    JSONObject readSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject().put("root_uri", field("string", "授权目录 URI"))
                                    .put("document_id", field("string", "从目录列表获得的文档 ID")))
                            .put("required", new JSONArray().put("root_uri").put("document_id"));
                    JSONObject alarmSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject().put("hour", field("integer", "小时 0-23"))
                                    .put("minute", field("integer", "分钟 0-59"))
                                    .put("label", field("string", "闹钟名称")))
                            .put("required", new JSONArray().put("hour").put("minute"));
                    JSONObject listAppsSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject().put("query", field("string", "可选：应用中文名或包名搜索关键字，如 '微信'、'美团'、'时钟'")));
                    JSONObject openAppSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("app", field("string", "要启动的应用名称（如 '微信'、'美团'、'设置'、'时钟'、'相册'）或包名"))
                                    .put("display_id", field("integer", "目标屏幕 ID（默认 0 为主屏；传虚拟屏 ID 可在副屏启动）"))
                                    .put("url", field("string", "可选：要直接打开的网页 URL（适用于浏览器）"))
                                    .put("wait_ms", field("integer", "启动后等待界面稳定的毫秒数，默认 1200")))
                            .put("required", new JSONArray().put("app"));
                    JSONObject observeSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("display_id", field("integer", "目标屏幕 ID，默认 0（主屏）")));
                    JSONObject clickTextSchema = new JSONObject().put("type", "object")
                            .put("properties", withDisplayGuard(new JSONObject()
                                    .put("text", field("string", "要点击的屏幕文字、按钮描述 (desc) 或控件 id"))
                                    .put("match_mode", field("string", "匹配模式：contains（默认，包含即可）或 exact（精确相等）"))
                                    .put("occurrence", field("integer", "若有多个同名元素，点击第几个（从 0 开始，默认 0）"))
                                    .put("wait_ms", field("integer", "点击后等待界面更新的毫秒数，默认 700"))))
                            .put("required", new JSONArray().put("text"));
                    JSONObject tapSchema = new JSONObject().put("type", "object")
                            .put("properties", withDisplayGuard(new JSONObject()
                                    .put("view_id", field("string", "【最高优先级】控件 resource-id（如 'url' 或完整 id），直接精准定位并点击"))
                                    .put("node_index", field("integer", "次选：传入最近一次 screen.interactive_nodes 中的 index 序号"))
                                    .put("x", field("integer", "补充：屏幕像素横坐标（未传 view_id / node_index 时使用）"))
                                    .put("y", field("integer", "补充：屏幕像素纵坐标（未传 view_id / node_index 时使用）"))
                                    .put("duration_ms", field("integer", "按压毫秒数，默认 80；传 600-1000 可执行长按"))
                                    .put("wait_ms", field("integer", "点击后等待界面稳定的毫秒数，默认 700"))));
                    JSONObject typeSchema = new JSONObject().put("type", "object")
                            .put("properties", withDisplayGuard(new JSONObject()
                                    .put("view_id", field("string", "【最高优先级】目标输入框的控件 resource-id（如 'url'）"))
                                    .put("text", field("string", "要输入的文本内容（若当前无焦点输入框，会自动定位屏幕上的输入框点击后再填入）"))
                                    .put("target_hint", field("string", "可选：指定目标输入框的提示词/占位文字，用于多输入框页面"))
                                    .put("submit_enter", field("boolean", "输入后是否自动触发输入法搜索/回车提交，默认 false；返回会严格区分 text_filled / enter_submitted / executed"))
                                    .put("wait_ms", field("integer", "输入后等待界面更新的毫秒数，默认 650"))))
                            .put("required", new JSONArray().put("text"));
                    JSONObject swipeSchema = new JSONObject().put("type", "object")
                            .put("properties", withDisplayGuard(new JSONObject()
                                    .put("direction", field("string", "推荐：滑动方向 'up'（向上滑以浏览下方内容）、'down'（向下滑）、'left'（左滑）、'right'（右滑）"))
                                    .put("x1", field("integer", "可选自定义起点横坐标"))
                                    .put("y1", field("integer", "可选自定义起点纵坐标"))
                                    .put("x2", field("integer", "可选自定义终点横坐标"))
                                    .put("y2", field("integer", "可选自定义终点纵坐标"))
                                    .put("duration_ms", field("integer", "滑动耗时毫秒，默认 350"))
                                    .put("wait_ms", field("integer", "滑动后等待界面稳定的毫秒数，默认 700"))));
                    JSONObject backSchema = new JSONObject().put("type", "object")
                            .put("properties", withDisplayGuard(new JSONObject()
                                    .put("action", field("string", "系统导航动作：'back'（默认，返回上一级）、'home'（回到桌面）、'enter'（副屏回车）、'recents'、'notifications'"))
                                    .put("wait_ms", field("integer", "执行后等待界面稳定的毫秒数，默认 600"))));
                    JSONObject waitSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("display_id", field("integer", "目标屏幕 ID，默认 0"))
                                    .put("until_text", field("string", "可选：轮询等待直到屏幕上出现该文字"))
                                    .put("wait_ms", field("integer", "未指定 until_text 时的固定等待毫秒数，默认 1500"))
                                    .put("timeout_ms", field("integer", "等待 until_text 出现的最大超时毫秒数，默认 5000")));
                    JSONObject screenshotSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("display_id", field("integer", "目标屏幕 ID，默认 0"))
                                    .put("max_width", field("integer", "截图缩放宽度像素，默认 540（兼顾清晰度与速度）"))
                                    .put("quality", field("integer", "JPEG 质量 30-90，默认 65")));
                    JSONObject vdCreateSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("width", field("integer", "虚拟屏宽度像素，默认 1080"))
                                    .put("height", field("integer", "虚拟屏高度像素，默认 2400"))
                                    .put("density_dpi", field("integer", "虚拟屏 DPI，默认 420")));
                    JSONObject vdDestroySchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("display_id", field("integer", "要销毁的虚拟屏 ID（必须大于 0）")))
                            .put("required", new JSONArray().put("display_id"));
                    JSONObject openOnDisplaySchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("app", field("string", "应用名称或包名"))
                                    .put("display_id", field("integer", "目标屏幕 ID"))
                                    .put("url", field("string", "可选：打开的网页 URL"))
                                    .put("wait_ms", field("integer", "启动后等待稳定的毫秒数，默认 1200")))
                            .put("required", new JSONArray().put("app").put("display_id"));
                    JSONObject termuxRunSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("command", field("string", "要在 Termux 中后台执行的 bash 命令"))
                                    .put("workdir", field("string", "工作目录，默认 /data/data/com.termux/files/home"))
                                    .put("mode", field("string", "任务模式：'oneshot'（默认，一次性命令）或 'service'（长期服务）"))
                                    .put("service_name", field("string", "可选：长期服务名称，如 'my-web-server'"))
                                    .put("probe_url", field("string", "可选：本机 HTTP 就绪探测地址，如 'http://127.0.0.1:8000/'"))
                                    .put("probe_port", field("integer", "可选：本机端口探测，如 8000"))
                                    .put("wait_ms", field("integer", "同步等待启动/完成的毫秒数，默认 4000")))
                            .put("required", new JSONArray().put("command"));
                    JSONObject termuxTaskIdSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("task_id", field("string", "后台任务 ID（termux_status 可留空以列出全部最近任务）"))
                                    .put("tail_lines", field("integer", "读取日志尾部行数（默认 80）"))
                                    .put("wait_ms", field("integer", "停止服务时等待端口释放的毫秒数，默认 4000")));
                    JSONObject fsListSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("path", field("string", "相对共享存储根目录的路径，如 ''、'DCIM/Camera'、'Pictures'、'Download'、'压缩用。'"))
                                    .put("filter_type", field("string", "可选过滤：all / image / video / media / archive / document / dir"))
                                    .put("sort_by", field("string", "可选排序：mtime_desc（默认）/ mtime_asc / size_desc / name_asc"))
                                    .put("limit", field("integer", "返回条数上限（默认 100，最大 500）")));
                    JSONObject fsSearchSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("path", field("string", "搜索起始目录，默认 '' 表示整个共享存储"))
                                    .put("query", field("string", "文件名关键字（不区分大小写，可为空）"))
                                    .put("filter_type", field("string", "可选过滤：all / image / video / media / archive / document / dir"))
                                    .put("min_size_bytes", field("integer", "最小文件体积（字节）"))
                                    .put("max_depth", field("integer", "最大递归深度（默认 4，最大 8）"))
                                    .put("limit", field("integer", "返回条数上限（默认 100，最大 500）")));
                    JSONObject fsPathSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject().put("path", field("string", "相对共享存储根目录的路径")))
                            .put("required", new JSONArray().put("path"));
                    JSONObject stringArrayField = new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))
                            .put("description", "相对共享存储根目录的路径数组");
                    JSONObject fsMoveSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("sources", stringArrayField)
                                    .put("destination_dir", field("string", "目标目录路径（支持批量移入，不存在时自动创建）"))
                                    .put("destination_path", field("string", "单个文件重命名/移动的完整目标路径（与 destination_dir 二选一）"))
                                    .put("overwrite", field("boolean", "若目标已存在是否覆盖，默认 false")))
                            .put("required", new JSONArray().put("sources"));
                    JSONObject fsCopySchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("source", field("string", "源文件路径"))
                                    .put("destination", field("string", "目标目录或目标文件路径"))
                                    .put("overwrite", field("boolean", "是否覆盖同名文件，默认 false")))
                            .put("required", new JSONArray().put("source").put("destination"));
                    JSONObject fsDeleteSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("paths", stringArrayField)
                                    .put("permanent", field("boolean", "是否永久删除。默认 false 会移入 Download/.PhoneAgentTrash 回收站防误删")))
                            .put("required", new JSONArray().put("paths"));
                    JSONObject fsWriteSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("path", field("string", "目标文本文件路径"))
                                    .put("content", field("string", "UTF-8 文本内容"))
                                    .put("append", field("boolean", "是否追加写入，默认 false")))
                            .put("required", new JSONArray().put("path").put("content"));
                    JSONObject fsZipSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("sources", stringArrayField)
                                    .put("zip_path", field("string", "输出的 .zip 文件路径，例如 '压缩用。/整理打包.zip'"))
                                    .put("compression_level", field("integer", "压缩级别 0-9，默认 1（兼顾速度与体积）")))
                            .put("required", new JSONArray().put("sources").put("zip_path"));
                    JSONObject fsUnzipSchema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject()
                                    .put("zip_path", field("string", ".zip 压缩包路径"))
                                    .put("destination_dir", field("string", "解压目标目录（不填则默认解压到同名文件夹）"))
                                    .put("list_only", field("boolean", "为 true 时仅预览压缩包内文件列表而不解压，默认 false")))
                            .put("required", new JSONArray().put("zip_path"));
                    JSONArray available = new JSONArray().put(tool("list_calendars", "列出手机系统日历及其可写状态", schema))
                            .put(tool("list_calendar_events", "读取指定时间范围内的系统日程，最多 200 条", querySchema))
                            .put(tool("create_calendar_event", "在指定系统日历创建日程；仅在用户明确要求时调用", eventSchema))
                            .put(tool("list_file_roots", "列出用户授权的 SAF 文件目录", schema))
                            .put(tool("list_root_files", "列出已授权 SAF 目录的直接子项", fileSchema))
                            .put(tool("read_text_file", "读取 SAF 授权目录下不超过 1 MiB 的 UTF-8 文本文件", readSchema))
                            .put(tool("album_summary", "扫描手机相册与媒体文件夹（DCIM、Pictures、Movies、Download、微信等），统计各相册照片/视频数量与占用空间", schema))
                            .put(tool("fs_list_dir", "列出手机存储指定目录下的文件与子文件夹，支持按类型过滤和按时间/大小排序", fsListSchema))
                            .put(tool("fs_search", "在手机存储中递归搜索文件（按关键字、图片/视频/压缩包/文档类型、最小体积）", fsSearchSchema))
                            .put(tool("fs_mkdir", "在手机存储中创建目录（自动创建多级父目录）", fsPathSchema))
                            .put(tool("fs_move", "批量或单个移动、归档、重命名文件/文件夹（自动刷新系统相册图库）", fsMoveSchema))
                            .put(tool("fs_copy", "复制手机存储中的文件并刷新系统媒体库", fsCopySchema))
                            .put(tool("fs_delete", "删除手机文件（默认移入 Download/.PhoneAgentTrash 回收站防误删；传 permanent=true 永久删除）", fsDeleteSchema))
                            .put(tool("fs_read_text_path", "按手机存储路径直接读取不超过 1 MiB 的 UTF-8 文本文件", fsPathSchema))
                            .put(tool("fs_write_text_path", "按手机存储路径创建或写入 UTF-8 文本文件", fsWriteSchema))
                            .put(tool("fs_zip", "将一个或多个文件/文件夹打包压缩为 .zip 压缩包", fsZipSchema))
                            .put(tool("fs_unzip", "预览或解压 .zip 压缩包到指定目录（带路径穿越安全校验）", fsUnzipSchema))
                            .put(tool("open_file_on_phone", "在手机屏幕上调用系统应用直接打开指定文件，并自动返回打开后的屏幕状态快照", fsPathSchema))
                            .put(tool("request_alarm", "直接设置手机系统闹钟，并自动读取系统 AlarmManager 核验真实生效的下一响铃时间（闭环验证）", alarmSchema))
                            .put(tool("list_installed_apps", "列出或搜索手机上已安装的应用名称与包名", listAppsSchema))
                            .put(tool("open_app", "按应用中文名（如'微信'、'美团'、'设置'、'时钟'）或包名直接启动应用，等待加载后自动返回新页面快照", openAppSchema))
                            .put(tool("request_screen_control", "请求开启或续期临时屏幕控制会话（默认需手机本机人工确认；若在设置中开启自动续期则自动激活）并返回当前屏幕快照", schema))
                            .put(tool("screen_observe", "获取目标屏幕的结构化快照（包含前台应用、窗口 ID、可见文本摘要、带 index 和 view_id 的可交互节点列表）", observeSchema))
                            .put(tool("screen_click_text", "【首选点击工具】按屏幕上的文字、按钮描述(desc)或控件id智能查找并点击，自动等待并返回点击后的最新页面快照与 ui_changed 状态", clickTextSchema))
                            .put(tool("screen_tap", "按 interactive_nodes 的 node_index 序号或 (x,y) 坐标点击/长按屏幕，自动等待并返回点击后的最新页面快照与 ui_changed 状态", tapSchema))
                            .put(tool("screen_swipe", "按方向 (up/down/left/right) 或坐标滑动屏幕，自动等待并返回滑动后的最新页面快照与 ui_changed 状态", swipeSchema))
                            .put(tool("screen_set_text", "在输入框中输入文本（若无焦点会自动寻找输入框点击聚焦后再输入，支持自动回车提交），并返回输入后的最新页面快照", typeSchema))
                            .put(tool("screen_back", "执行系统导航按键（back 返回 / home 桌面 / recents 最近任务 / notifications 通知栏），并返回动作后的最新页面快照", backSchema))
                            .put(tool("screen_wait", "等待页面加载或轮询直到指定文字出现，返回最新页面快照", waitSchema))
                            .put(tool("screen_screenshot", "获取当前手机屏幕的真实视觉截图（JPEG 图像 + 结构化节点快照），用于小程序/网页/图片界面或当节点树信息不足时视觉观察", screenshotSchema))
                            .put(tool("end_screen_control", "任务全部完成时结束临时屏幕控制会话", schema))
                            .put(tool("phone_health", "轻量诊断：返回手机端无障碍、Shizuku、Termux 权限、可用屏幕列表与后台任务状态", schema))
                            .put(tool("list_displays", "列出当前手机所有可用屏幕及各屏幕前台应用包名", schema))
                            .put(tool("shizuku_status", "查询 Shizuku 服务运行状态、授权状态与当前权限级别", schema))
                            .put(tool("virtual_display_create", "通过 Shizuku 创建独立虚拟副屏，返回 display_id 与创建后端", vdCreateSchema))
                            .put(tool("virtual_display_destroy", "销毁指定的虚拟副屏（禁止销毁主屏 0）", vdDestroySchema))
                            .put(tool("open_app_on_display", "在指定 display_id 屏幕上启动应用（虚拟屏失败时直接报错，禁止回退主屏）", openOnDisplaySchema))
                            .put(tool("termux_run", "通过 Termux RUN_COMMAND 后台运行一次性命令或长期服务（支持本机 HTTP 探测）", termuxRunSchema))
                            .put(tool("termux_status", "查询 Termux 后台任务运行阶段、PID、退出码与本机 HTTP 就绪状态", termuxTaskIdSchema))
                            .put(tool("termux_logs", "读取 Termux 后台任务的实时日志尾部", termuxTaskIdSchema))
                            .put(tool("termux_stop", "停止指定的 Termux 后台长期服务并验证端口释放", termuxTaskIdSchema));
                    result = new JSONObject().put("tools", available);
                } else if ("tools/call".equals(method) && "list_calendars".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        result = new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text")
                                .put("text", new SystemTools(BridgeService.this).calendars().toString())));
                    } catch (Exception e) {
                        result = new JSONObject().put("isError", true).put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "无法读取日历：请检查权限")));
                    }
                } else if ("tools/call".equals(method) && "list_file_roots".equals(req.getJSONObject("params").getString("name"))) {
                    JSONArray roots = new JSONArray();
                    for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                        if (p.isReadPermission()) roots.put(new JSONObject().put("uri", p.getUri().toString()).put("writable", p.isWritePermission()));
                    }
                    result = new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", roots.toString())));
                } else if ("tools/call".equals(method) && "list_root_files".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        String uri = req.getJSONObject("params").getJSONObject("arguments").getString("root_uri");
                        result = textResult(new SystemTools(BridgeService.this).listRootFiles(android.net.Uri.parse(uri)).toString(), false);
                    } catch (Exception e) { result = textResult("目录不可访问或未授权", true); }
                } else if ("tools/call".equals(method) && "create_calendar_event".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        long id = new SystemTools(BridgeService.this).createEvent(a.getLong("calendar_id"), a.getString("title"),
                                a.getLong("start_ms"), a.getLong("end_ms"), a.getString("timezone"));
                        result = textResult(new JSONObject().put("event_id", id).toString(), false);
                    } catch (Exception e) { result = textResult("创建失败：检查日历权限、目标日历和事件参数", true); }
                } else if ("tools/call".equals(method) && "list_calendar_events".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(new SystemTools(BridgeService.this).listEvents(a.getLong("start_ms"), a.getLong("end_ms")).toString(), false);
                    } catch (Exception e) { result = textResult("无法读取日程：检查权限和时间范围", true); }
                } else if ("tools/call".equals(method) && "read_text_file".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        String text = new SystemTools(BridgeService.this).readTextFile(android.net.Uri.parse(a.getString("root_uri")), a.getString("document_id"));
                        result = textResult(text, false);
                    } catch (Exception e) { result = textResult("文件无法读取、未授权、超出大小限制或不是 UTF-8 文本", true); }
                } else if ("tools/call".equals(method) && "request_alarm".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        int hour = a.getInt("hour"), minute = a.getInt("minute");
                        String label = a.optString("label", "");
                        JSONObject verify = new SystemTools(BridgeService.this).setAlarmAndVerify(hour, minute, label);
                        result = textResult(verify.toString(), false);
                    } catch (Exception e) { result = textResult("设置闹钟失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "list_installed_apps".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").optJSONObject("arguments");
                        String q = a != null ? a.optString("query", "") : "";
                        result = textResult(new SystemTools(BridgeService.this).listInstalledApps(q).toString(), false);
                    } catch (Exception e) { result = textResult("列出应用失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "open_app".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        ScreenControlService.authorized();
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        String app = a.optString("app", a.optString("query", a.optString("package_name", "")));
                        int waitMs = a.optInt("wait_ms", 1200);
                        int displayId = a.optInt("display_id", 0);
                        String url = a.optString("url", "");
                        JSONObject resObj = new SystemTools(BridgeService.this).openApp(app, waitMs, displayId, url);
                        result = textResult(resObj.toString(), !resObj.optBoolean("ok", true));
                    } catch (Exception e) { result = classifiedErrorResult("open_app", e); }
                } else if ("tools/call".equals(method) && "open_app_on_display".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        ScreenControlService.authorized();
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        String app = a.optString("app", a.optString("package_name", ""));
                        int displayId = a.getInt("display_id");
                        int waitMs = a.optInt("wait_ms", 1200);
                        String url = a.optString("url", "");
                        JSONObject resObj = new SystemTools(BridgeService.this).openApp(app, waitMs, displayId, url);
                        result = textResult(resObj.toString(), !resObj.optBoolean("ok", true));
                    } catch (Exception e) { result = classifiedErrorResult("open_app_on_display", e); }
                } else if ("tools/call".equals(method) && "request_screen_control".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        ScreenControlService svc = ScreenControlService.authorized();
                        JSONObject snap = svc.captureSnapshot(0);
                        JSONObject out = new JSONObject()
                                .put("action", "request_screen_control")
                                .put("active", true)
                                .put("feedback", "✅ 临时屏幕控制会话已激活（无操作 2 分钟结束，单次上限 10 分钟），已返回当前屏幕快照。")
                                .put("screen", snap);
                        result = textResult(out.toString(), false);
                    } catch (Exception e) { result = classifiedErrorResult("request_screen_control", e); }
                } else if ("tools/call".equals(method) && "screen_observe".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").optJSONObject("arguments");
                        int displayId = a != null ? a.optInt("display_id", 0) : 0;
                        result = textResult(ScreenControlService.authorized().captureSnapshot(displayId).toString(), false);
                    } catch (Exception e) { result = classifiedErrorResult("screen_observe", e); }
                } else if ("tools/call".equals(method) && "screen_click_text".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        int displayId = a.optInt("display_id", 0);
                        String expPkg = a.optString("expected_package", "");
                        String expWin = a.optString("expected_window", "");
                        String text = a.getString("text");
                        String mode = a.optString("match_mode", "contains");
                        int occ = a.optInt("occurrence", 0);
                        int waitMs = a.optInt("wait_ms", 700);
                        JSONObject out = ScreenControlService.authorized().clickTextClosedLoop(displayId, expPkg, expWin, text, mode, occ, waitMs);
                        result = textResult(out.toString(), !out.optBoolean("ok", out.optBoolean("action_submitted", false)));
                    } catch (Exception e) { result = classifiedErrorResult("screen_click_text", e); }
                } else if ("tools/call".equals(method) && "screen_tap".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        int displayId = a.optInt("display_id", 0);
                        String expPkg = a.optString("expected_package", "");
                        String expWin = a.optString("expected_window", "");
                        String viewId = a.optString("view_id", "");
                        Integer nodeIdx = a.has("node_index") && !a.isNull("node_index") ? a.getInt("node_index") : null;
                        Integer x = a.has("x") && !a.isNull("x") ? a.getInt("x") : null;
                        Integer y = a.has("y") && !a.isNull("y") ? a.getInt("y") : null;
                        int dur = a.optInt("duration_ms", 80);
                        int waitMs = a.optInt("wait_ms", 700);
                        JSONObject out = ScreenControlService.authorized().tapClosedLoop(displayId, expPkg, expWin, viewId, x, y, nodeIdx, dur, waitMs);
                        result = textResult(out.toString(), !out.optBoolean("ok", out.optBoolean("action_submitted", false)));
                    } catch (Exception e) { result = classifiedErrorResult("screen_tap", e); }
                } else if ("tools/call".equals(method) && "screen_back".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").optJSONObject("arguments");
                        int displayId = a != null ? a.optInt("display_id", 0) : 0;
                        String expPkg = a != null ? a.optString("expected_package", "") : "";
                        String expWin = a != null ? a.optString("expected_window", "") : "";
                        String act = a != null ? a.optString("action", "back") : "back";
                        int waitMs = a != null ? a.optInt("wait_ms", 600) : 600;
                        JSONObject out = ScreenControlService.authorized().pressSystemKeyClosedLoop(displayId, expPkg, expWin, act, waitMs);
                        result = textResult(out.toString(), !out.optBoolean("ok", out.optBoolean("action_submitted", false)));
                    } catch (Exception e) { result = classifiedErrorResult("screen_back", e); }
                } else if ("tools/call".equals(method) && "screen_set_text".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        int displayId = a.optInt("display_id", 0);
                        String expPkg = a.optString("expected_package", "");
                        String expWin = a.optString("expected_window", "");
                        String viewId = a.optString("view_id", "");
                        String value = a.getString("text");
                        String hint = a.optString("target_hint", "");
                        boolean enter = a.optBoolean("submit_enter", false);
                        int waitMs = a.optInt("wait_ms", 650);
                        JSONObject out = ScreenControlService.authorized().setTextClosedLoop(displayId, expPkg, expWin, viewId, value, hint, enter, waitMs);
                        result = textResult(out.toString(), !out.optBoolean("ok", out.optBoolean("action_submitted", false)));
                    } catch (Exception e) { result = classifiedErrorResult("screen_set_text", e); }
                } else if ("tools/call".equals(method) && "screen_swipe".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        int displayId = a.optInt("display_id", 0);
                        String expPkg = a.optString("expected_package", "");
                        String expWin = a.optString("expected_window", "");
                        String dir = a.optString("direction", "");
                        Integer x1 = a.has("x1") && !a.isNull("x1") ? a.getInt("x1") : null;
                        Integer y1 = a.has("y1") && !a.isNull("y1") ? a.getInt("y1") : null;
                        Integer x2 = a.has("x2") && !a.isNull("x2") ? a.getInt("x2") : null;
                        Integer y2 = a.has("y2") && !a.isNull("y2") ? a.getInt("y2") : null;
                        int dur = a.optInt("duration_ms", 350);
                        int waitMs = a.optInt("wait_ms", 700);
                        JSONObject out = ScreenControlService.authorized().swipeClosedLoop(displayId, expPkg, expWin, dir, x1, y1, x2, y2, dur, waitMs);
                        result = textResult(out.toString(), !out.optBoolean("ok", out.optBoolean("action_submitted", false)));
                    } catch (Exception e) { result = classifiedErrorResult("screen_swipe", e); }
                } else if ("tools/call".equals(method) && "screen_wait".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").optJSONObject("arguments");
                        int displayId = a != null ? a.optInt("display_id", 0) : 0;
                        String until = a != null ? a.optString("until_text", "") : "";
                        int waitMs = a != null ? a.optInt("wait_ms", 1500) : 1500;
                        int timeoutMs = a != null ? a.optInt("timeout_ms", 5000) : 5000;
                        result = textResult(ScreenControlService.authorized().waitForScreen(displayId, until, waitMs, timeoutMs).toString(), false);
                    } catch (Exception e) { result = classifiedErrorResult("screen_wait", e); }
                } else if ("tools/call".equals(method) && "screen_screenshot".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").optJSONObject("arguments");
                        int displayId = a != null ? a.optInt("display_id", 0) : 0;
                        int maxW = a != null ? a.optInt("max_width", 540) : 540;
                        int q = a != null ? a.optInt("quality", 65) : 65;
                        ScreenControlService svc = ScreenControlService.authorized();
                        String b64 = svc.captureScreenshotBase64(displayId, maxW, q);
                        JSONObject snap = svc.captureSnapshot(displayId);
                        JSONArray content = new JSONArray()
                                .put(new JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", b64))
                                .put(new JSONObject().put("type", "text").put("text", snap.toString()));
                        result = new JSONObject().put("isError", false).put("content", content);
                    } catch (Exception e) { result = classifiedErrorResult("screen_screenshot", e); }
                } else if ("tools/call".equals(method) && "end_screen_control".equals(req.getJSONObject("params").getString("name"))) {
                    ScreenApprovalActivity.end(BridgeService.this, "客户端已结束控制"); result = textResult("临时控制已结束", false);
                } else if ("tools/call".equals(method) && "phone_health".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject health = new JSONObject()
                                .put("ok", true)
                                .put("bridge_running", BridgeService.running)
                                .put("tunnel_status", TunnelManager.statusText)
                                .put("accessibility_enabled", ScreenControlService.instance != null)
                                .put("shizuku", ShizukuBridge.getStatusJson(BridgeService.this))
                                .put("termux", TermuxCommandManager.getPermissionStatus(BridgeService.this))
                                .put("active_bg_tasks", TermuxCommandManager.activeTaskCount(BridgeService.this))
                                .put("displays", ShizukuBridge.queryDisplays(BridgeService.this).optJSONArray("displays"));
                        result = textResult(health.toString(), false);
                    } catch (Exception e) { result = classifiedErrorResult("phone_health", e); }
                } else if ("tools/call".equals(method) && "list_displays".equals(req.getJSONObject("params").getString("name"))) {
                    try { result = textResult(ShizukuBridge.queryDisplays(BridgeService.this).toString(), false); }
                    catch (Exception e) { result = classifiedErrorResult("list_displays", e); }
                } else if ("tools/call".equals(method) && "shizuku_status".equals(req.getJSONObject("params").getString("name"))) {
                    try { result = textResult(ShizukuBridge.getStatusJson(BridgeService.this).toString(), false); }
                    catch (Exception e) { result = classifiedErrorResult("shizuku_status", e); }
                } else if ("tools/call".equals(method) && "virtual_display_create".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        ScreenControlService.authorized();
                        JSONObject a = req.getJSONObject("params").optJSONObject("arguments");
                        int w = a != null ? a.optInt("width", 1080) : 1080;
                        int h = a != null ? a.optInt("height", 2400) : 2400;
                        int dpi = a != null ? a.optInt("density_dpi", 420) : 420;
                        JSONObject resObj = ShizukuBridge.createVirtualDisplay(w, h, dpi);
                        result = textResult(resObj.toString(), !resObj.optBoolean("ok", false));
                    } catch (Exception e) { result = classifiedErrorResult("virtual_display_create", e); }
                } else if ("tools/call".equals(method) && "virtual_display_destroy".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        JSONObject resObj = ShizukuBridge.destroyVirtualDisplay(a.getInt("display_id"));
                        result = textResult(resObj.toString(), !resObj.optBoolean("ok", false));
                    } catch (Exception e) { result = classifiedErrorResult("virtual_display_destroy", e); }
                } else if ("tools/call".equals(method) && "termux_run".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        JSONObject resObj = TermuxCommandManager.run(
                                BridgeService.this,
                                a.getString("command"),
                                a.optString("workdir", ""),
                                a.optString("mode", "oneshot"),
                                a.optString("service_name", ""),
                                a.optString("probe_url", ""),
                                a.optInt("probe_port", 0),
                                a.optInt("wait_ms", 4000)
                        );
                        result = textResult(resObj.toString(), !resObj.optBoolean("ok", false));
                    } catch (Exception e) { result = classifiedErrorResult("termux_run", e); }
                } else if ("tools/call".equals(method) && "termux_status".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").optJSONObject("arguments");
                        String tid = a != null ? a.optString("task_id", "") : "";
                        result = textResult(TermuxCommandManager.status(BridgeService.this, tid).toString(), false);
                    } catch (Exception e) { result = classifiedErrorResult("termux_status", e); }
                } else if ("tools/call".equals(method) && "termux_logs".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(TermuxCommandManager.logs(BridgeService.this, a.getString("task_id"), a.optInt("tail_lines", 80)).toString(), false);
                    } catch (Exception e) { result = classifiedErrorResult("termux_logs", e); }
                } else if ("tools/call".equals(method) && "termux_stop".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(TermuxCommandManager.stop(BridgeService.this, a.getString("task_id"), a.optInt("wait_ms", 4000)).toString(), false);
                    } catch (Exception e) { result = classifiedErrorResult("termux_stop", e); }
                } else if ("tools/call".equals(method) && "album_summary".equals(req.getJSONObject("params").getString("name"))) {
                    try { result = textResult(new SystemTools(BridgeService.this).albumSummary().toString(), false); }
                    catch (Exception e) { result = textResult("读取相册概览失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "fs_list_dir".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").optJSONObject("arguments");
                        if (a == null) a = new JSONObject();
                        result = textResult(new SystemTools(BridgeService.this).fsListDir(
                                a.optString("path", ""), a.optString("filter_type", "all"),
                                a.optString("sort_by", "mtime_desc"), a.optInt("limit", 100)).toString(), false);
                    } catch (Exception e) { result = textResult("列出目录失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "fs_search".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").optJSONObject("arguments");
                        if (a == null) a = new JSONObject();
                        result = textResult(new SystemTools(BridgeService.this).fsSearch(
                                a.optString("path", ""), a.optString("query", ""),
                                a.optString("filter_type", "all"), a.optLong("min_size_bytes", 0L),
                                a.optInt("max_depth", 4), a.optInt("limit", 100)).toString(), false);
                    } catch (Exception e) { result = textResult("搜索文件失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "fs_mkdir".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(new SystemTools(BridgeService.this).fsMkdir(a.getString("path")).toString(), false);
                    } catch (Exception e) { result = textResult("创建目录失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "fs_move".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(new SystemTools(BridgeService.this).fsMove(
                                a.getJSONArray("sources"), a.optString("destination_dir", ""),
                                a.optString("destination_path", ""), a.optBoolean("overwrite", false)).toString(), false);
                    } catch (Exception e) { result = textResult("移动/重命名失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "fs_copy".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(new SystemTools(BridgeService.this).fsCopy(
                                a.getString("source"), a.getString("destination"), a.optBoolean("overwrite", false)).toString(), false);
                    } catch (Exception e) { result = textResult("复制文件失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "fs_delete".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(new SystemTools(BridgeService.this).fsDelete(
                                a.getJSONArray("paths"), a.optBoolean("permanent", false)).toString(), false);
                    } catch (Exception e) { result = textResult("删除/移入回收站失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "fs_read_text_path".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(new SystemTools(BridgeService.this).fsReadTextPath(a.getString("path")), false);
                    } catch (Exception e) { result = textResult("读取文本失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "fs_write_text_path".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(new SystemTools(BridgeService.this).fsWriteTextPath(
                                a.getString("path"), a.getString("content"), a.optBoolean("append", false)).toString(), false);
                    } catch (Exception e) { result = textResult("写入文本失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "fs_zip".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(new SystemTools(BridgeService.this).fsZip(
                                a.getJSONArray("sources"), a.getString("zip_path"), a.optInt("compression_level", 1)).toString(), false);
                    } catch (Exception e) { result = textResult("压缩失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "fs_unzip".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(new SystemTools(BridgeService.this).fsUnzip(
                                a.getString("zip_path"), a.optString("destination_dir", ""), a.optBoolean("list_only", false)).toString(), false);
                    } catch (Exception e) { result = textResult("解压/读取压缩包失败：" + e.getMessage(), true); }
                } else if ("tools/call".equals(method) && "open_file_on_phone".equals(req.getJSONObject("params").getString("name"))) {
                    try {
                        ScreenControlService.authorized();
                        JSONObject a = req.getJSONObject("params").getJSONObject("arguments");
                        result = textResult(new SystemTools(BridgeService.this).openFileOnPhone(a.getString("path")).toString(), false);
                    } catch (Exception e) { result = classifiedErrorResult("open_file_on_phone", e); }
                } else {
                    JSONObject error = new JSONObject().put("jsonrpc", "2.0").put("id", req.get("id"))
                            .put("error", new JSONObject().put("code", -32601).put("message", "Method not available"));
                    return response(Response.Status.OK, error.toString());
                }
                return response(Response.Status.OK, new JSONObject().put("jsonrpc", "2.0").put("id", req.get("id")).put("result", result).toString());
            } catch (Exception e) { return response(Response.Status.BAD_REQUEST, "{}"); }
        }
        private Response response(Response.Status status, String text) {
            Response r = newFixedLengthResponse(status, "application/json; charset=utf-8", text);
            r.addHeader("Cache-Control", "no-store");
            r.addHeader("X-Content-Type-Options", "nosniff");
            return r;
        }
        private void addHtmlSecurityHeaders(Response r) {
            r.addHeader("Cache-Control", "no-store");
            r.addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
            r.addHeader("X-Frame-Options", "DENY");
            r.addHeader("X-Content-Type-Options", "nosniff");
            r.addHeader("Referrer-Policy", "no-referrer");
        }
        private boolean checkContentLength(IHTTPSession session, long maxBytes) {
            String len = session.getHeaders().get("content-length");
            if (len == null) return false;
            try {
                long n = Long.parseLong(len.trim());
                return n >= 0 && n <= maxBytes;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        private boolean isAllowedLocalOrigin(String origin, String baseUrl) {
            String o = origin.trim();
            return o.equals("http://127.0.0.1:8765")
                    || o.equals("http://localhost:8765");
        }
        private Response.IStatus status429() {
            return new Response.IStatus() {
                @Override public String getDescription() { return "429 Too Many Requests"; }
                @Override public int getRequestStatus() { return 429; }
            };
        }
        private String resolveBaseUrl(IHTTPSession session) {
            Map<String, String> h = session.getHeaders();
            String host = h.get("x-forwarded-host");
            if (host == null || host.isEmpty()) host = h.get("host");
            String proto = h.get("x-forwarded-proto");
            if (proto == null || proto.isEmpty()) {
                proto = (host != null && !host.startsWith("127.0.0.1") && !host.startsWith("localhost")) ? "https" : "http";
            }
            if (host != null && !host.isEmpty()) return proto + "://" + host;
            if (TunnelManager.publicUrl != null && !TunnelManager.publicUrl.isEmpty()) return TunnelManager.publicUrl;
            return "http://127.0.0.1:8765";
        }
        private Map<String, String> parseAllParams(IHTTPSession session) throws Exception {
            Map<String, String> body = new java.util.HashMap<>();
            session.parseBody(body);
            Map<String, String> params = new java.util.HashMap<>(session.getParms());
            parseUrlEncoded(session.getQueryParameterString(), params);
            String postData = body.get("postData");
            if (postData != null) {
                String trimmed = postData.trim();
                if (trimmed.startsWith("{")) {
                    JSONObject obj = new JSONObject(trimmed);
                    java.util.Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        params.put(k, obj.optString(k, ""));
                    }
                } else {
                    parseUrlEncoded(trimmed, params);
                }
            }
            return params;
        }
        private void parseUrlEncoded(String raw, Map<String, String> out) {
            if (raw == null || raw.isEmpty()) return;
            for (String pair : raw.split("&")) {
                if (pair.isEmpty()) continue;
                int idx = pair.indexOf('=');
                try {
                    if (idx >= 0) {
                        String k = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                        String v = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                        out.put(k, v);
                    } else {
                        out.put(java.net.URLDecoder.decode(pair, "UTF-8"), "");
                    }
                } catch (Exception ignored) { }
            }
        }
        private JSONObject tool(String name, String description, JSONObject schema) throws org.json.JSONException {
            boolean readOnly = java.util.Arrays.asList("list_calendars", "list_calendar_events", "list_file_roots", "list_root_files", "read_text_file", "album_summary", "fs_list_dir", "fs_search", "fs_read_text_path", "screen_observe", "phone_health", "list_displays", "shizuku_status", "termux_status", "termux_logs").contains(name);
            boolean destructive = java.util.Arrays.asList("fs_delete", "fs_move", "fs_copy", "fs_write_text_path", "fs_zip", "fs_unzip", "screen_tap", "screen_swipe", "screen_set_text", "screen_back", "virtual_display_destroy", "termux_run", "termux_stop").contains(name);
            JSONObject annotations = new JSONObject()
                    .put("readOnlyHint", readOnly)
                    .put("destructiveHint", destructive)
                    .put("openWorldHint", false);
            return new JSONObject().put("name", name).put("description", description)
                    .put("inputSchema", schema).put("annotations", annotations);
        }
        private JSONObject withDisplayGuard(JSONObject props) throws org.json.JSONException {
            return props.put("display_id", field("integer", "目标屏幕 ID（默认 0 为主屏；传虚拟屏 ID 可在副屏操作）"))
                    .put("expected_package", field("string", "可选防误触守卫：期望当前目标屏幕前台包名，若已变化则中止动作"))
                    .put("expected_window", field("string", "可选防误触守卫：期望当前目标窗口 ID 或标题关键字，若已变化则中止动作"));
        }
        private JSONObject classifiedErrorResult(String stage, Throwable e) throws org.json.JSONException {
            String msg = e != null && e.getMessage() != null ? e.getMessage() : "未知错误";
            String errorCode;
            boolean retryable = false;
            if (e instanceof SecurityException || msg.contains("权限") || msg.contains("授权") || msg.contains("无障碍")) {
                errorCode = "permission_denied";
            } else if (msg.contains("变化") || msg.contains("target_window_changed")) {
                errorCode = "target_window_changed";
            } else if (msg.contains("超时") || msg.contains("timeout")) {
                errorCode = "request_timeout";
                retryable = true;
            } else if (msg.contains("拒绝") || msg.contains("禁止")) {
                errorCode = "system_rejected";
            } else {
                errorCode = "action_failed";
                retryable = true;
            }
            JSONObject err = new JSONObject()
                    .put("ok", false)
                    .put("error_code", errorCode)
                    .put("stage", stage)
                    .put("retryable", retryable)
                    .put("message", msg);
            return textResult(err.toString(), true);
        }
        private JSONObject field(String type, String description) throws org.json.JSONException {
            return new JSONObject().put("type", type).put("description", description);
        }
        private JSONObject textResult(String text, boolean error) throws org.json.JSONException {
            return new JSONObject().put("isError", error).put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)));
        }
    }
}
