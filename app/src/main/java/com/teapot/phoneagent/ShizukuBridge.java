package com.teapot.phoneagent;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

/**
 * Shizuku 权限后端管理（ADB shell uid=2000 架构）：
 * 通过 Shizuku.newProcess 启动独立的 app_process 离屏虚拟屏守护进程（VirtualDisplayDaemon），
 * 实现主屏零遮挡的真离屏虚拟屏创建/销毁，并提供受控的跨屏启动、屏幕查询与副屏输入兜底能力。
 */
final class ShizukuBridge {
    private static final int SHIZUKU_PERMISSION_CODE = 1001;
    private static final String PREFS = "shizuku_bridge_prefs";
    private static final String KEY_AUTO_RESTORE_A11Y = "auto_restore_a11y";
    private static final String A11Y_COMPONENT = "com.teapot.phoneagent/com.teapot.phoneagent.ScreenControlService";
    private static volatile boolean initialized;
    private static volatile Context appContext;
    private static volatile String lastError = "";
    private static final Map<Integer, ManagedVirtualDisplay> MANAGED_DISPLAYS = new ConcurrentHashMap<>();

    private static final class ManagedVirtualDisplay {
        final int displayId;
        final int width;
        final int height;
        final int densityDpi;
        final int flags;
        final Process process;
        final OutputStream stdin;

        ManagedVirtualDisplay(int displayId, int width, int height, int densityDpi, int flags, Process process) {
            this.displayId = displayId;
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.flags = flags;
            this.process = process;
            this.stdin = process.getOutputStream();
        }

        void destroy() {
            try {
                stdin.write("QUIT\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (Exception ignored) { }
            try { process.destroy(); } catch (Exception ignored) { }
        }
    }

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED = () -> {
        lastError = "";
    };

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD = () -> {
        cleanupAllVirtualDisplays();
    };

    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_RESULT = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_PERMISSION_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            lastError = "";
        }
    };

    private ShizukuBridge() { }

    static synchronized void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
        if (initialized) return;
        initialized = true;
        try {
            Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED);
            Shizuku.addBinderDeadListener(BINDER_DEAD);
            Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT);
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
    }

    static void cleanupAllVirtualDisplays() {
        for (ManagedVirtualDisplay vd : MANAGED_DISPLAYS.values()) {
            vd.destroy();
        }
        MANAGED_DISPLAYS.clear();
    }

    static boolean isInstalled(Context context) {
        Context ctx = context != null ? context : appContext;
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isAuthorized() {
        try {
            return isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isAutoRestoreA11yEnabled(Context context) {
        Context ctx = context != null ? context : appContext;
        if (ctx == null) return false;
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_RESTORE_A11Y, false);
    }

    static void setAutoRestoreA11yEnabled(Context context, boolean enabled) {
        Context ctx = context != null ? context : appContext;
        if (ctx == null) return;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_RESTORE_A11Y, enabled).commit();
    }

    /** 仅在用户于手机端显式启用开关且 Shizuku 已授权时，恢复被系统误杀的无障碍服务。 */
    static void ensureAccessibilityRestoredIfOptedIn(Context context) {
        if (ScreenControlService.instance != null) return;
        if (!isAutoRestoreA11yEnabled(context) || !isRunning() || !isAuthorized()) return;
        try {
            String cur = runPrivilegedCmd("settings", "get", "secure", "enabled_accessibility_services").trim();
            if ("null".equalsIgnoreCase(cur)) cur = "";
            if (!cur.contains(A11Y_COMPONENT)) {
                String next = cur.isEmpty() ? A11Y_COMPONENT : (cur + ":" + A11Y_COMPONENT);
                runPrivilegedCmd("settings", "put", "secure", "enabled_accessibility_services", next);
            }
            runPrivilegedCmd("settings", "put", "secure", "accessibility_enabled", "1");
        } catch (Exception ignored) { }
    }

    static void requestPermission() {
        try {
            if (!isRunning()) return;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
            }
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
    }

    static JSONObject getStatusJson(Context context) {
        JSONObject out = new JSONObject();
        try {
            boolean installed = isInstalled(context);
            boolean running = installed && isRunning();
            boolean authorized = running && isAuthorized();
            int uid = -1;
            String level = "none";
            if (running) {
                try {
                    uid = Shizuku.getUid();
                    level = (uid == 0) ? "root" : (uid == 2000 ? "adb_shell" : "uid_" + uid);
                } catch (Throwable ignored) { }
            }
            String statusCode;
            if (!installed) statusCode = "not_installed";
            else if (!running) statusCode = "service_not_running";
            else if (!authorized) statusCode = "permission_denied";
            else statusCode = "ready";

            out.put("installed", installed)
                    .put("running", running)
                    .put("authorized", authorized)
                    .put("uid", uid)
                    .put("permission_level", level)
                    .put("backend", "shizuku_app_process")
                    .put("managed_virtual_displays", MANAGED_DISPLAYS.size())
                    .put("status_code", statusCode);
            if (!lastError.isEmpty()) out.put("last_error", lastError);
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * 创建真正的离屏虚拟屏（type=VIRTUAL，主屏零遮挡）。
     * 严禁静默降级为遮挡主屏的 overlay_display_devices。
     */
    static synchronized JSONObject createVirtualDisplay(int width, int height, int densityDpi) throws Exception {
        ensurePrivilegedReady();
        if (MANAGED_DISPLAYS.size() >= 2) {
            throw new IllegalStateException("最多同时托管 2 个离屏虚拟屏，请先调用 virtual_display_destroy 释放已有虚拟屏");
        }
        int w = Math.max(480, Math.min(2560, width <= 0 ? 1080 : width));
        int h = Math.max(640, Math.min(3200, height <= 0 ? 2400 : height));
        int dpi = Math.max(160, Math.min(640, densityDpi <= 0 ? 420 : densityDpi));

        if (appContext == null) throw new IllegalStateException("Context 未初始化");
        String apkPath = appContext.getApplicationInfo().sourceDir;
        Process p = startPrivilegedProcess(
                "app_process",
                "-Djava.class.path=" + apkPath,
                "/",
                VirtualDisplayDaemon.class.getName(),
                String.valueOf(w),
                String.valueOf(h),
                String.valueOf(dpi),
                "PhoneAgentVirtual"
        );

        BufferedReader stdout = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        BufferedReader stderr = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
        final StringBuilder errBuf = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try {
                String l;
                while ((l = stderr.readLine()) != null) {
                    if (errBuf.length() < 2000) errBuf.append(l).append('\n');
                }
            } catch (Exception ignored) { }
        }, "vd-stderr");
        errReader.setDaemon(true);
        errReader.start();

        String readyLine = null;
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            if (stdout.ready()) {
                String line = stdout.readLine();
                if (line == null) break;
                if (line.startsWith("READY:") || line.startsWith("ERROR:")) {
                    readyLine = line;
                    break;
                }
            }
            try {
                if (!p.isAlive()) break;
            } catch (Throwable ignored) { }
            Thread.sleep(60);
        }

        if (readyLine == null || !readyLine.startsWith("READY:")) {
            try { p.destroy(); } catch (Exception ignored) { }
            String reason = readyLine != null ? readyLine : ("守护进程未就绪: " + errBuf.toString().trim());
            lastError = reason;
            return new JSONObject()
                    .put("ok", false)
                    .put("error_code", "system_rejected")
                    .put("stage", "virtual_display_create")
                    .put("retryable", false)
                    .put("message", "离屏虚拟屏创建失败（拒绝回退到遮挡主屏的叠加模式）：" + reason);
        }

        String[] parts = readyLine.split(":");
        int displayId = Integer.parseInt(parts[1]);
        int usedFlags = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

        ManagedVirtualDisplay holder = new ManagedVirtualDisplay(displayId, w, h, dpi, usedFlags, p);
        MANAGED_DISPLAYS.put(displayId, holder);

        // 守护线程持续排空 stdout 防止管道阻塞，并在进程退出时自动清理
        Thread watcher = new Thread(() -> {
            try {
                while (stdout.readLine() != null) { /* drain */ }
            } catch (Exception ignored) {
            } finally {
                MANAGED_DISPLAYS.remove(displayId, holder);
            }
        }, "vd-watch-" + displayId);
        watcher.setDaemon(true);
        watcher.start();

        return new JSONObject()
                .put("ok", true)
                .put("backend", "offscreen_virtual_display")
                .put("display_id", displayId)
                .put("width", w)
                .put("height", h)
                .put("density_dpi", dpi)
                .put("flags", usedFlags);
    }

    static JSONObject destroyVirtualDisplay(int displayId) throws Exception {
        if (displayId <= 0) {
            return new JSONObject()
                    .put("ok", false)
                    .put("error_code", "action_failed")
                    .put("stage", "virtual_display_destroy")
                    .put("message", "禁止销毁主屏 display_id=0");
        }
        ManagedVirtualDisplay holder = MANAGED_DISPLAYS.remove(displayId);
        if (holder != null) {
            holder.destroy();
            return new JSONObject()
                    .put("ok", true)
                    .put("display_id", displayId)
                    .put("backend", "offscreen_virtual_display");
        }
        return new JSONObject()
                .put("ok", false)
                .put("error_code", "action_failed")
                .put("stage", "virtual_display_destroy")
                .put("message", "未找到由手机桥托管的虚拟屏 display_id=" + displayId);
    }

    static JSONObject startAppOnDisplay(String pkg, String action, String dataUri, int displayId) throws Exception {
        ensurePrivilegedReady();
        if (pkg == null || !pkg.matches("[a-zA-Z0-9._]+")) {
            throw new IllegalArgumentException("非法包名: " + pkg);
        }
        if (ScreenControlService.isProtectedPackage(pkg)) {
            throw new SecurityException("禁止通过远程虚拟屏启动受保护系统/安全应用：" + pkg);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("am");
        cmd.add("start");
        cmd.add("--display");
        cmd.add(String.valueOf(displayId));
        if (dataUri != null && !dataUri.trim().isEmpty()) {
            String cleanUrl = sanitizeHttpUrl(dataUri);
            String cleanAction = (action == null || action.trim().isEmpty() || "android.intent.action.VIEW".equals(action.trim()))
                    ? "android.intent.action.VIEW" : null;
            if (cleanAction == null) throw new SecurityException("仅允许 android.intent.action.VIEW 动作打开网页 URL");
            cmd.add("-a");
            cmd.add(cleanAction);
            cmd.add("-d");
            cmd.add(cleanUrl);
            cmd.add(pkg);
        } else {
            String resolved = resolveLauncherComponent(pkg);
            if (!resolved.isEmpty()) {
                cmd.add("-n");
                cmd.add(resolved);
            } else {
                cmd.add("-a");
                cmd.add("android.intent.action.MAIN");
                cmd.add("-c");
                cmd.add("android.intent.category.LAUNCHER");
                cmd.add(pkg);
            }
        }
        String amOut = runPrivilegedCmd(cmd.toArray(new String[0]));
        Thread.sleep(900);
        JSONObject topByDisplay = queryTopPackagesByDisplay();
        String onTarget = topByDisplay.optString(String.valueOf(displayId), "");
        String onPrimary = topByDisplay.optString("0", "");
        boolean landed = pkg.equals(onTarget);
        boolean hijacked = displayId > 0 && pkg.equals(onPrimary) && !landed;
        return new JSONObject()
                .put("ok", landed || (displayId == 0 && !amOut.contains("Error")))
                .put("requested_display_id", displayId)
                .put("package", pkg)
                .put("landed_on_target_display", landed)
                .put("hijacked_primary_display", hijacked)
                .put("target_display_top_package", onTarget)
                .put("primary_display_top_package", onPrimary)
                .put("am_output", amOut.trim());
    }

    static boolean inputTapOnDisplay(int displayId, int x, int y) {
        try {
            String out = runPrivilegedCmd("input", "-d", String.valueOf(displayId), "tap", String.valueOf(x), String.valueOf(y));
            return !out.toLowerCase(Locale.ROOT).contains("error");
        } catch (Exception e) {
            return false;
        }
    }

    static boolean inputSwipeOnDisplay(int displayId, int x1, int y1, int x2, int y2, int durationMs) {
        try {
            String out = runPrivilegedCmd("input", "-d", String.valueOf(displayId), "swipe",
                    String.valueOf(x1), String.valueOf(y1), String.valueOf(x2), String.valueOf(y2), String.valueOf(durationMs));
            return !out.toLowerCase(Locale.ROOT).contains("error");
        } catch (Exception e) {
            return false;
        }
    }

    static boolean inputKeyOnDisplay(int displayId, int keyCode) {
        try {
            String out = runPrivilegedCmd("input", "-d", String.valueOf(displayId), "keyevent", String.valueOf(keyCode));
            return !out.toLowerCase(Locale.ROOT).contains("error");
        } catch (Exception e) {
            return false;
        }
    }

    /** 使用 Shizuku screencap -d <displayId> -p 截取指定屏幕（作为无障碍截图的副屏补充通道） */
    static byte[] screencapPngOnDisplay(int displayId) throws Exception {
        ensurePrivilegedReady();
        Process p = startPrivilegedProcess("screencap", "-d", String.valueOf(displayId), "-p");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream is = p.getInputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        }
        p.waitFor();
        byte[] data = bos.toByteArray();
        if (data.length < 100) {
            throw new IllegalStateException("screencap 未返回有效图像数据");
        }
        return data;
    }

    static JSONObject queryDisplays(Context context) {
        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            Context ctx = context != null ? context : appContext;
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                for (Display d : dm.getDisplays()) {
                    DisplayMetrics m = new DisplayMetrics();
                    d.getRealMetrics(m);
                    arr.put(new JSONObject()
                            .put("display_id", d.getDisplayId())
                            .put("name", d.getName())
                            .put("width", m.widthPixels)
                            .put("height", m.heightPixels)
                            .put("density_dpi", m.densityDpi)
                            .put("managed_by_bridge", MANAGED_DISPLAYS.containsKey(d.getDisplayId())));
                }
            }
            out.put("ok", true).put("displays", arr);
            if (isRunning() && isAuthorized()) {
                out.put("top_packages", queryTopPackagesByDisplay());
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String sanitizeHttpUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty() || s.startsWith("-") || s.length() > 2048) {
            throw new SecurityException("URL 格式不合法或过长");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x1F || c == 0x7F || Character.isWhitespace(c)) {
                throw new SecurityException("URL 不得包含控制字符或空白字符");
            }
        }
        java.net.URI u = java.net.URI.create(s);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new SecurityException("仅允许 http:// 或 https:// 协议的 URL");
        }
        if (u.getHost() == null || u.getHost().isEmpty()) {
            throw new SecurityException("URL 缺少有效主机名");
        }
        return s;
    }

    private static void ensurePrivilegedReady() {
        if (!isRunning()) throw new IllegalStateException("Shizuku 服务未启动，请在手机 Shizuku App 或通过 ADB 启动");
        if (!isAuthorized()) throw new SecurityException("Teapot Phone-Agent 尚未获得 Shizuku 授权，请在手机上点击授权");
    }

    private static String resolveLauncherComponent(String pkg) {
        try {
            String out = runPrivilegedCmd("cmd", "package", "resolve-activity", "--brief",
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg);
            for (String line : out.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.contains("/") && trimmed.startsWith(pkg)) return trimmed;
            }
        } catch (Exception ignored) { }
        return "";
    }

    private static JSONObject queryTopPackagesByDisplay() {
        JSONObject map = new JSONObject();
        try {
            String dump = runPrivilegedCmd("dumpsys", "activity", "activities");
            int currentDisplay = -1;
            Pattern dp = Pattern.compile("Display #(\\d+)");
            Pattern rp = Pattern.compile("topResumedActivity=ActivityRecord\\{[^ ]+ u\\d+ ([a-zA-Z0-9._]+)/");
            Pattern fp = Pattern.compile("ResumedActivity: ActivityRecord\\{[^ ]+ u\\d+ ([a-zA-Z0-9._]+)/");
            for (String line : dump.split("\n")) {
                Matcher dm = dp.matcher(line);
                if (dm.find()) {
                    currentDisplay = Integer.parseInt(dm.group(1));
                    continue;
                }
                if (currentDisplay >= 0) {
                    Matcher rm = rp.matcher(line);
                    if (rm.find()) {
                        map.put(String.valueOf(currentDisplay), rm.group(1));
                        continue;
                    }
                    Matcher fm = fp.matcher(line);
                    if (fm.find() && !map.has(String.valueOf(currentDisplay))) {
                        map.put(String.valueOf(currentDisplay), fm.group(1));
                    }
                }
            }
        } catch (Exception ignored) { }
        return map;
    }

    private static Process startPrivilegedProcess(String... args) throws Exception {
        ensurePrivilegedReady();
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, args, null, null);
    }

    private static String runPrivilegedCmd(String... args) throws Exception {
        Process p = startPrivilegedProcess(args);
        final boolean[] timedOut = { false };
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(10000L);
                if (p.isAlive()) {
                    timedOut[0] = true;
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) { }
        }, "shizuku-cmd-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        StringBuilder sb = new StringBuilder();
        try {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            try (BufferedReader eb = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = eb.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            watchdog.interrupt();
        }
        if (timedOut[0]) {
            throw new IllegalStateException("Shizuku 特权命令执行超时");
        }
        return sb.toString();
    }
}
