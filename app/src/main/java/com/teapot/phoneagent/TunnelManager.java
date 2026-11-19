package com.teapot.phoneagent;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手机端免电脑公网连接管理器（纯 Java Reverse-RPC）：
 * 直接由本进程与用户自托管的 Cloudflare Worker + Durable Object 网关建立 HTTPS 长轮询双向通道，
 * 无需打包第三方二进制（无 cloudflared 依赖），免疫国产 ROM 对子进程的后台冻结。
 */
final class TunnelManager {
    private static final String PREFS = "bridge_tunnel";
    private static final String KEY_CUSTOM_DOMAIN = "cf_custom_domain";
    private static final String KEY_AUTO_START = "auto_start_tunnel";
    private static final String KEY_LAST_URL = "last_public_url";
    private static final String KEY_LAST_STATUS = "last_status_text";

    static volatile boolean running;
    static volatile String statusText = "未启动";
    static volatile String publicUrl = "";
    private static volatile Context lastAppContext;
    private static volatile HttpURLConnection activePollConn;
    private static final AtomicInteger generation = new AtomicInteger(0);

    private TunnelManager() { }

    /**
     * 规范化公网网关地址：
     * - 允许空字符串（切换为仅本机模式）；
     * - 缺少协议头时自动补全 https://，自动剥离误填的 /mcp 与尾部斜杠；
     * - 显式拒绝 http:// 或非法 URI（返回 null）。
     */
    static String normalizeDomain(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (s.regionMatches(true, 0, "http://", 0, 7)) return null;
        if (!s.contains("://")) s = "https://" + s;
        try {
            java.net.URI u = java.net.URI.create(s);
            if (!"https".equalsIgnoreCase(u.getScheme())) return null;
            if (u.getRawUserInfo() != null) return null;
            String host = u.getHost();
            if (host == null || host.isEmpty()) return null;
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            if ("/mcp".equalsIgnoreCase(path)) path = "";
            if (!path.isEmpty()) return null;
            int port = u.getPort();
            return "https://" + host + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return null;
        }
    }

    private static void disconnectActivePollAsync() {
        final HttpURLConnection conn = activePollConn;
        activePollConn = null;
        if (conn != null) {
            new Thread(() -> {
                try { conn.disconnect(); } catch (Exception ignored) { }
            }, "poll-disconnect").start();
        }
    }

    static boolean revokeCloudOAuthTokens(Context context) throws Exception {
        String token = new OAuthManager(context).getOrCreatePairingToken();
        String base = getSavedCustomDomain(context).trim().replaceAll("/+$", "");
        if (base.isEmpty() || !base.startsWith("https://")) {
            throw new SecurityException("请先配置 HTTPS 公网网关地址");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/_phone/revoke").openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream out = conn.getOutputStream()) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            return conn.getResponseCode() == 200;
        } finally {
            conn.disconnect();
        }
    }

    static boolean isAutoStartEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_START, false);
    }

    static void setAutoStartEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_START, enabled).commit();
    }

    static String getSavedCustomDomain(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_DOMAIN, "");
    }

    static String saveConfig(Context ctx, String customDomain) {
        String clean = normalizeDomain(customDomain);
        if (clean == null) return null;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CUSTOM_DOMAIN, clean)
                .commit();
        publicUrl = clean;
        return clean;
    }

    private static void persistRuntimeState(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_URL, publicUrl == null ? "" : publicUrl)
                .putString(KEY_LAST_STATUS, statusText == null ? "" : statusText)
                .commit();
    }

    static synchronized void start(Context context) {
        final Context app = context.getApplicationContext();
        lastAppContext = app;
        final int gen = generation.incrementAndGet();
        disconnectActivePollAsync();
        String hubBase = normalizeDomain(getSavedCustomDomain(app));
        if (hubBase == null || hubBase.isEmpty()) {
            running = false;
            publicUrl = "";
            statusText = "仅本机模式（请在设置中配置你的 Cloudflare Worker HTTPS 域名）";
            persistRuntimeState(app);
            return;
        }
        running = true;
        publicUrl = hubBase;
        statusText = "正在连接公网网关...";
        persistRuntimeState(app);
        new Thread(() -> runDirectPollLoop(app, hubBase, gen), "direct-hub-poll-" + gen).start();
    }

    private static void runDirectPollLoop(Context app, String hubBase, int gen) {
        int consecutiveFailures = 0;
        int consecutiveAuthFailures = 0;
        while (running && generation.get() == gen) {
            HttpURLConnection conn = null;
            try {
                ShizukuBridge.ensureAccessibilityRestoredIfOptedIn(app);
                String token = new OAuthManager(app).getOrCreatePairingToken();
                URL url = new URL(hubBase + "/_phone/poll");
                conn = (HttpURLConnection) url.openConnection();
                activePollConn = conn;
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(26000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(buildPollHealthPayload(app).getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code == 200) {
                    consecutiveFailures = 0;
                    consecutiveAuthFailures = 0;
                    publicUrl = hubBase;
                    statusText = "公网网关已连通 (" + hubBase.replaceFirst("^https://", "") + ")";
                    persistRuntimeState(app);
                    String body = readStream(conn.getInputStream());
                    conn.disconnect();
                    if (activePollConn == conn) activePollConn = null;
                    if (!running || generation.get() != gen) break;
                    JSONObject obj = new JSONObject(body);
                    if (obj.optBoolean("has_task", false)) {
                        final String taskId = obj.getString("id");
                        final String reqBody = obj.getString("body");
                        new Thread(() -> handleAndReply(hubBase, token, taskId, reqBody), "mcp-worker-" + taskId).start();
                    }
                } else {
                    conn.disconnect();
                    if (activePollConn == conn) activePollConn = null;
                    consecutiveFailures++;
                    if (code == 401) {
                        consecutiveAuthFailures++;
                        if (consecutiveAuthFailures >= 3) {
                            running = false;
                            statusText = "公网网关鉴权连续失败 (401)，已暂停重试以避免触发 15 分钟 IP 锁定；请核对 PHONE_SECRET 后点击「启动 / 重连服务」";
                            persistRuntimeState(app);
                            break;
                        }
                        statusText = "公网网关鉴权失败 (401，第 " + consecutiveAuthFailures + " 次)，请核对 PHONE_SECRET 与本机连接码一致";
                        persistRuntimeState(app);
                        Thread.sleep(10000L);
                    } else if (code == 429) {
                        statusText = "公网网关限流或 IP 已临时锁定 (429)，60 秒后重试";
                        persistRuntimeState(app);
                        Thread.sleep(60000L);
                    } else if (code == 404) {
                        statusText = "公网网关路径不存在 (404)，请确认域名正确且不含 /mcp 后缀";
                        persistRuntimeState(app);
                        Thread.sleep(30000L);
                    } else {
                        statusText = "公网网关返回异常 (HTTP " + code + ")，正在重试...";
                        persistRuntimeState(app);
                        Thread.sleep(Math.min(30000L, 2000L * consecutiveFailures));
                    }
                }
            } catch (Exception e) {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) { }
                    if (activePollConn == conn) activePollConn = null;
                }
                if (!running || generation.get() != gen) break;
                consecutiveFailures++;
                if (consecutiveFailures >= 3) {
                    statusText = "正在重连公网网关...";
                    persistRuntimeState(app);
                }
                try {
                    Thread.sleep(Math.min(10000L, 1000L * consecutiveFailures));
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    private static String buildPollHealthPayload(Context app) {
        try {
            JSONArray dispIds = new JSONArray();
            android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                for (android.view.Display d : dm.getDisplays()) {
                    dispIds.put(d.getDisplayId());
                }
            }
            JSONObject health = new JSONObject()
                    .put("accessibility_enabled", ScreenControlService.instance != null)
                    .put("shizuku_ready", ShizukuBridge.isRunning() && ShizukuBridge.isAuthorized())
                    .put("available_displays", dispIds);
            return new JSONObject().put("health", health).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void handleAndReply(String hubBase, String token, String taskId, String reqBody) {
        int status = 200;
        String respBody = "{}";
        try {
            URL local = new URL("http://127.0.0.1:8765/mcp");
            HttpURLConnection lc = (HttpURLConnection) local.openConnection();
            lc.setConnectTimeout(3000);
            lc.setReadTimeout(20000);
            lc.setRequestMethod("POST");
            lc.setDoOutput(true);
            lc.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            lc.setRequestProperty("Authorization", "Bearer " + token);
            byte[] payload = reqBody.getBytes(StandardCharsets.UTF_8);
            lc.setRequestProperty("Content-Length", String.valueOf(payload.length));
            try (OutputStream os = lc.getOutputStream()) {
                os.write(payload);
            }
            status = lc.getResponseCode();
            InputStream is = (status >= 200 && status < 400) ? lc.getInputStream() : lc.getErrorStream();
            respBody = is != null ? readStream(is) : "{}";
            lc.disconnect();
        } catch (Exception e) {
            status = 500;
            try {
                respBody = new JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", JSONObject.NULL)
                        .put("error", new JSONObject().put("code", -32000).put("message", e.getMessage()))
                        .toString();
            } catch (Exception ignored) { }
        }
        try {
            URL replyUrl = new URL(hubBase + "/_phone/reply");
            HttpURLConnection rc = (HttpURLConnection) replyUrl.openConnection();
            rc.setConnectTimeout(8000);
            rc.setReadTimeout(8000);
            rc.setRequestMethod("POST");
            rc.setDoOutput(true);
            rc.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            rc.setRequestProperty("Authorization", "Bearer " + token);
            byte[] out = new JSONObject()
                    .put("id", taskId)
                    .put("status", status)
                    .put("body", respBody)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = rc.getOutputStream()) {
                os.write(out);
            }
            rc.getResponseCode();
            rc.disconnect();
        } catch (Exception ignored) { }
    }

    private static String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    static synchronized void stop() {
        running = false;
        generation.incrementAndGet();
        statusText = "已停止";
        disconnectActivePollAsync();
        final Context app = lastAppContext;
        if (app != null) {
            new Thread(() -> notifyOffline(app), "notify-offline").start();
        }
    }

    private static void notifyOffline(Context app) {
        try {
            String hubBase = getSavedCustomDomain(app).trim().replaceAll("/+$", "");
            if (hubBase.isEmpty() || !hubBase.startsWith("https://")) return;
            String token = new OAuthManager(app).getOrCreatePairingToken();
            URL url = new URL(hubBase + "/_phone/offline");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream os = conn.getOutputStream()) {
                os.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) { }
    }
}
