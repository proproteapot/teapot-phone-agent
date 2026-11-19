package com.teapot.phoneagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 闭环手机 Agent 无障碍执行与观测引擎：
 * 支持结构化页面快照、按文本/序号智能点击、自动焦点输入、语义滑动、动作后自动等待与 UI 变化核验，以及 Android 11+ 免弹窗真实截图。
 */
public final class ScreenControlService extends AccessibilityService {
    static volatile ScreenControlService instance;
    private static final Set<String> PROTECTED_PACKAGES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.teapot.phoneagent",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.lbe.security.miui",
            "com.miui.securitycenter",
            "moe.shizuku.privileged.api",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller"
    ));

    static boolean isProtectedPackage(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return false;
        return PROTECTED_PACKAGES.contains(pkg.trim().toLowerCase(Locale.ROOT));
    }

    private View overlay;
    private TextView overlayLabel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, List<Rect>> lastInteractiveBoundsByDisplay = new ConcurrentHashMap<>();
    private volatile String currentTaskStatus = "AI Agent 正在操作";

    @Override protected void onServiceConnected() {
        instance = this;
        if (TunnelManager.isAutoStartEnabled(this) && !BridgeService.running) {
            try { startForegroundService(new android.content.Intent(this, BridgeService.class)); }
            catch (Exception ignored) { }
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { ScreenApprovalActivity.end(this, "无障碍服务中断"); }
    @Override public void onDestroy() {
        ScreenApprovalActivity.end(this, "无障碍服务停止");
        removeOverlay();
        instance = null;
        super.onDestroy();
    }

    boolean showActiveOverlay() {
        try {
            removeOverlay();
            LinearLayout bar = new LinearLayout(this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(20, 6, 12, 6);
            bar.setBackgroundColor(Color.argb(210, 180, 20, 30));
            TextView label = new TextView(this);
            label.setText("● " + currentTaskStatus);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            this.overlayLabel = label;
            bar.addView(label);
            Button stop = new Button(this);
            stop.setText("停止");
            stop.setTextSize(12);
            stop.setOnClickListener(v -> ScreenApprovalActivity.end(this, "你已手动停止 Agent 控制"));
            bar.addView(stop);
            WindowManager.LayoutParams p = overlayParams();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(bar, p);
            overlay = bar;
            return true;
        } catch (Exception e) {
            removeOverlay();
            return false;
        }
    }

    void updateOverlayStatus(String statusText) {
        currentTaskStatus = (statusText == null || statusText.trim().isEmpty()) ? "AI Agent 正在操作" : statusText.trim();
        main.post(() -> {
            if (overlayLabel != null) {
                overlayLabel.setText("● " + currentTaskStatus);
            }
        });
    }

    void showEndedOverlay() {
        currentTaskStatus = "AI Agent 正在操作";
        main.post(() -> {
            if (instance != this || ScreenApprovalActivity.allowed()) return;
            removeOverlay();
        });
    }

    private WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = Math.round(12 * getResources().getDisplayMetrics().density);
        p.y = Math.round(42 * getResources().getDisplayMetrics().density);
        return p;
    }

    private void removeOverlay() {
        if (overlay != null) {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlay); } catch (Exception ignored) { }
            overlay = null;
            overlayLabel = null;
        }
    }

    static ScreenControlService authorized() {
        ScreenControlService service = instance;
        if (service == null) throw new SecurityException("无障碍服务未启用");
        KeyguardManager guard = (KeyguardManager) service.getSystemService(KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) service.getSystemService(POWER_SERVICE);
        if (guard.isDeviceLocked() || !power.isInteractive()) {
            ScreenApprovalActivity.end(service, "手机已锁屏或息屏");
            throw new SecurityException("手机当前处于锁屏或息屏状态，无法执行屏幕操作");
        }
        ScreenApprovalActivity.autoActivate(service);
        return service;
    }

    /** 兼容旧版简单节点列表 */
    JSONArray screenNodes() throws Exception {
        return captureSnapshot().optJSONArray("interactive_nodes");
    }

    JSONObject captureSnapshot() throws Exception {
        return captureSnapshot(Display.DEFAULT_DISPLAY);
    }

    private DisplayMetrics getDisplayMetricsFor(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm != null) {
                Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (d != null) {
                    DisplayMetrics out = new DisplayMetrics();
                    d.getRealMetrics(out);
                    return out;
                }
            }
            return getResources().getDisplayMetrics();
        }
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display target = dm != null ? dm.getDisplay(displayId) : null;
        if (target == null) {
            throw new IllegalStateException("目标虚拟屏 display_id=" + displayId + " 不存在或已关闭（禁止回退到主屏）");
        }
        DisplayMetrics out = new DisplayMetrics();
        target.getRealMetrics(out);
        return out;
    }

    /**
     * 捕获结构化闭环观测快照（支持指定 display_id，虚拟屏无窗口时不回退主屏）：
     * 包含当前前台应用包名/名称、窗口 ID/标题、屏幕尺寸、可见文本摘要、带 index/view_id/中心坐标的可交互节点列表及页面签名。
     */
    JSONObject captureSnapshot(int displayId) throws Exception {
        ScreenApprovalActivity.checkDeviceUnlocked(this);
        if (!ScreenApprovalActivity.allowed()) ScreenApprovalActivity.autoActivate(this);
        DisplayMetrics dm = getDisplayMetricsFor(displayId);
        AccessibilityWindowInfo bestWin = findBestWindowInfo(displayId);
        AccessibilityNodeInfo root = bestWin != null ? bestWin.getRoot() : (displayId == Display.DEFAULT_DISPLAY ? findBestRootWindow() : null);
        String fgPkg = root != null && root.getPackageName() != null ? root.getPackageName().toString() : "";
        String fgApp = resolveAppLabel(fgPkg);
        int windowId = bestWin != null ? bestWin.getId() : -1;
        String windowTitle = (bestWin != null && Build.VERSION.SDK_INT >= 24 && bestWin.getTitle() != null)
                ? bestWin.getTitle().toString() : "";

        JSONArray interactive = new JSONArray();
        Set<String> visibleTexts = new LinkedHashSet<>();
        List<Rect> boundsCache = new ArrayList<>();
        StringBuilder sigBuilder = new StringBuilder().append(displayId).append('|').append(fgPkg).append('|').append(windowId).append('|');
        boolean isProtected = isProtectedPackage(fgPkg);

        if (root != null) {
            try {
                if (!isProtected) {
                    walkSnapshot(root, interactive, visibleTexts, boundsCache, sigBuilder, 0, dm.widthPixels, dm.heightPixels);
                }
            } finally {
                root.recycle();
            }
        }
        this.lastInteractiveBoundsByDisplay.put(displayId, boundsCache);

        JSONArray textsArr = new JSONArray();
        for (String t : visibleTexts) {
            textsArr.put(t);
            if (textsArr.length() >= 60) break;
        }

        String signature = Integer.toHexString(sigBuilder.toString().hashCode());
        return new JSONObject()
                .put("display_id", displayId)
                .put("window_found", root != null)
                .put("window_id", windowId)
                .put("window_title", isProtected ? "" : windowTitle)
                .put("foreground_package", fgPkg)
                .put("foreground_app", fgApp)
                .put("protected", isProtected)
                .put("screen_size", new JSONObject().put("width", dm.widthPixels).put("height", dm.heightPixels))
                .put("ui_signature", signature)
                .put("visible_texts", textsArr)
                .put("node_count", interactive.length())
                .put("interactive_nodes", interactive);
    }

    private AccessibilityWindowInfo findBestWindowInfo(int displayId) {
        try {
            List<AccessibilityWindowInfo> windows = null;
            if (Build.VERSION.SDK_INT >= 30) {
                android.util.SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
                if (all != null) windows = all.get(displayId);
            } else if (displayId == Display.DEFAULT_DISPLAY) {
                windows = getWindows();
            }
            if (windows != null && !windows.isEmpty()) {
                AccessibilityWindowInfo fallback = null;
                for (AccessibilityWindowInfo w : windows) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    String pkg = r.getPackageName() != null ? r.getPackageName().toString() : "";
                    r.recycle();
                    if ("com.android.systemui".equals(pkg) && !w.isFocused()) continue;
                    if (w.isActive() || w.isFocused()) return w;
                    if (fallback == null) fallback = w;
                }
                if (fallback != null) return fallback;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private AccessibilityNodeInfo findBestRootWindow(int displayId) {
        AccessibilityWindowInfo win = findBestWindowInfo(displayId);
        if (win != null) {
            AccessibilityNodeInfo r = win.getRoot();
            if (r != null) return r;
        }
        if (displayId == Display.DEFAULT_DISPLAY) {
            return findBestRootWindow();
        }
        return null;
    }

    private AccessibilityNodeInfo findBestRootWindow() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null && !windows.isEmpty()) {
                AccessibilityNodeInfo fallback = null;
                for (AccessibilityWindowInfo w : windows) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    String pkg = r.getPackageName() != null ? r.getPackageName().toString() : "";
                    if ("com.android.systemui".equals(pkg) && !w.isFocused()) {
                        r.recycle();
                        continue;
                    }
                    if (w.isActive() || w.isFocused()) {
                        if (fallback != null) fallback.recycle();
                        return r;
                    }
                    if (fallback == null) fallback = r;
                    else r.recycle();
                }
                if (fallback != null) return fallback;
            }
        } catch (Exception ignored) { }
        return getRootInActiveWindow();
    }

    private String resolveAppLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }

    private void walkSnapshot(
            AccessibilityNodeInfo node,
            JSONArray out,
            Set<String> visibleTexts,
            List<Rect> boundsCache,
            StringBuilder sigBuilder,
            int depth,
            int screenW,
            int screenH) throws Exception {
        if (node == null || depth > 24 || out.length() >= 120) return;
        if (!node.isVisibleToUser()) return;

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        boolean validBounds = rect.width() > 4 && rect.height() > 4
                && rect.right > 0 && rect.bottom > 0
                && rect.left < screenW && rect.top < screenH;

        String text = (!node.isPassword() && node.getText() != null) ? node.getText().toString().trim() : "";
        String desc = (!node.isPassword() && node.getContentDescription() != null) ? node.getContentDescription().toString().trim() : "";
        String hint = (Build.VERSION.SDK_INT >= 26 && node.getHintText() != null) ? node.getHintText().toString().trim() : "";
        String resId = node.getViewIdResourceName() != null ? shortenResourceId(node.getViewIdResourceName()) : "";

        if (!text.isEmpty() && text.length() <= 120) visibleTexts.add(text);
        else if (!desc.isEmpty() && desc.length() <= 120) visibleTexts.add(desc);

        boolean isInteractive = node.isClickable() || node.isEditable() || node.isScrollable() || node.isCheckable() || node.isLongClickable();
        boolean hasMeaning = !text.isEmpty() || !desc.isEmpty() || !hint.isEmpty();

        if (validBounds && (isInteractive || hasMeaning)) {
            int idx = out.length();
            int cx = Math.max(0, Math.min(screenW - 1, rect.centerX()));
            int cy = Math.max(0, Math.min(screenH - 1, rect.centerY()));

            JSONObject item = new JSONObject()
                    .put("index", idx)
                    .put("class", shortenClassName(node.getClassName()))
                    .put("center", new JSONArray().put(cx).put(cy))
                    .put("bounds", new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom));
            if (!text.isEmpty()) item.put("text", text);
            if (!desc.isEmpty()) item.put("desc", desc);
            if (!hint.isEmpty()) item.put("hint", hint);
            if (!resId.isEmpty()) item.put("id", resId);
            if (node.isClickable()) item.put("clickable", true);
            if (node.isEditable()) item.put("editable", true);
            if (node.isScrollable()) item.put("scrollable", true);
            if (node.isChecked()) item.put("checked", true);
            if (node.isSelected()) item.put("selected", true);
            if (node.isFocused()) item.put("focused", true);
            if (node.isPassword()) item.put("password", true);

            out.put(item);
            boundsCache.add(new Rect(rect));
            sigBuilder.append(idx).append(':').append(text).append('/').append(desc)
                    .append('@').append(rect.left).append(',').append(rect.top).append(';');
        }

        int count = node.getChildCount();
        for (int i = 0; i < count && out.length() < 120; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    walkSnapshot(child, out, visibleTexts, boundsCache, sigBuilder, depth + 1, screenW, screenH);
                } finally {
                    child.recycle();
                }
            }
        }
    }

    private static String shortenClassName(CharSequence cls) {
        if (cls == null) return "View";
        String s = cls.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static String shortenResourceId(String resId) {
        if (resId == null) return "";
        int slash = resId.indexOf(":id/");
        return slash >= 0 ? resId.substring(slash + 4) : resId;
    }

    JSONObject runClosedLoopAction(String actionName, String actionDetail, ActionRunner runner, int waitMs) throws Exception {
        return runClosedLoopAction(Display.DEFAULT_DISPLAY, null, null, actionName, actionDetail, runner, waitMs);
    }

    /**
     * 闭环动作包装器：
     * 执行前校验 display_id / expected_package / expected_window，
     * 动作后分离返回 action_delivered、ui_changed 与 goal_reached。
     */
    JSONObject runClosedLoopAction(int displayId, String expectedPackage, String expectedWindow,
                                   String actionName, String actionDetail, ActionRunner runner, int waitMs) throws Exception {
        JSONObject before = captureSnapshot(displayId);
        String beforeSig = before.optString("ui_signature", "");
        String beforePkg = before.optString("foreground_package", "");
        String beforeWinId = String.valueOf(before.optInt("window_id", -1));
        String beforeWinTitle = before.optString("window_title", "");

        if (isProtectedPackage(beforePkg)) {
            JSONObject redactedScreen = new JSONObject()
                    .put("display_id", displayId)
                    .put("foreground_package", beforePkg)
                    .put("protected", true);
            return new JSONObject()
                    .put("ok", false)
                    .put("action", actionName)
                    .put("display_id", displayId)
                    .put("error_code", "system_rejected")
                    .put("stage", "protected_package_guard")
                    .put("retryable", false)
                    .put("action_delivered", false)
                    .put("action_submitted", false)
                    .put("ui_changed", false)
                    .put("goal_reached", false)
                    .put("feedback", "❌ 安全保护：禁止自动操作本应用、系统权限弹窗、安装器或 Shizuku 界面（" + beforePkg + "）。请由用户本机手动操作。")
                    .put("screen", redactedScreen);
        }

        if (displayId > Display.DEFAULT_DISPLAY && !before.optBoolean("window_found", false)) {
            return new JSONObject()
                    .put("ok", false)
                    .put("action", actionName)
                    .put("display_id", displayId)
                    .put("error_code", "action_failed")
                    .put("stage", "target_display_check")
                    .put("retryable", false)
                    .put("action_delivered", false)
                    .put("action_submitted", false)
                    .put("ui_changed", false)
                    .put("goal_reached", false)
                    .put("feedback", "❌ 虚拟屏 display_id=" + displayId + " 当前无可用窗口，已中止动作（禁止自动切回主屏）。")
                    .put("screen", before);
        }

        if (expectedPackage != null && !expectedPackage.trim().isEmpty()
                && !expectedPackage.trim().equals(beforePkg)) {
            return new JSONObject()
                    .put("ok", false)
                    .put("action", actionName)
                    .put("display_id", displayId)
                    .put("error_code", "target_window_changed")
                    .put("stage", "precondition_check")
                    .put("retryable", false)
                    .put("expected_package", expectedPackage.trim())
                    .put("actual_package", beforePkg)
                    .put("action_delivered", false)
                    .put("action_submitted", false)
                    .put("ui_changed", false)
                    .put("goal_reached", false)
                    .put("feedback", "❌ 目标应用已变化：期望 " + expectedPackage.trim() + "，当前为 " + beforePkg + "，已取消本次动作。")
                    .put("screen", before);
        }

        if (expectedWindow != null && !expectedWindow.trim().isEmpty()) {
            String ew = expectedWindow.trim();
            if (!ew.equals(beforeWinId) && !beforeWinTitle.contains(ew)) {
                return new JSONObject()
                        .put("ok", false)
                        .put("action", actionName)
                        .put("display_id", displayId)
                        .put("error_code", "target_window_changed")
                        .put("stage", "precondition_check")
                        .put("retryable", false)
                        .put("expected_window", ew)
                        .put("actual_window_id", beforeWinId)
                        .put("actual_window_title", beforeWinTitle)
                        .put("action_delivered", false)
                        .put("action_submitted", false)
                        .put("ui_changed", false)
                        .put("goal_reached", false)
                        .put("feedback", "❌ 目标窗口已变化：期望 " + ew + "，当前为 window_id=" + beforeWinId + " (" + beforeWinTitle + ")，已取消本次动作。")
                        .put("screen", before);
            }
        }

        updateOverlayStatus((displayId == Display.DEFAULT_DISPLAY ? "主屏: " : "副屏#" + displayId + ": ") + actionName);

        boolean submitted = false;
        String errorDetail = null;
        try {
            submitted = runner.run();
        } catch (Exception e) {
            errorDetail = e.getMessage();
        }

        int actualWait = Math.max(100, Math.min(5000, waitMs));
        if (submitted) {
            Thread.sleep(actualWait);
        }

        JSONObject after = captureSnapshot(displayId);
        String afterSig = after.optString("ui_signature", "");
        String afterPkg = after.optString("foreground_package", "");
        String afterApp = after.optString("foreground_app", "");
        boolean pkgChanged = !beforePkg.equals(afterPkg);
        boolean uiChanged = pkgChanged || !beforeSig.equals(afterSig);

        String feedback;
        if (!submitted) {
            feedback = "❌ 动作未送达" + (errorDetail != null ? "（" + errorDetail + "）" : "")
                    + "。请查看下方 screen.interactive_nodes 选择其他目标，或调用 screen_screenshot 视觉观察。";
        } else if (pkgChanged) {
            feedback = "✅ 动作已送达，界面已切换至「" + afterApp + "」(" + afterPkg + ")。";
        } else if (uiChanged) {
            feedback = "✅ 动作已送达，界面已更新。";
        } else {
            feedback = "⚠️ 动作已送达，但界面未发生明显变化（可能正在后台加载、或点击无响应）。如操作长期服务或开关，请配合端口/状态探测确认目标是否达成。";
        }

        JSONObject out = new JSONObject()
                .put("ok", submitted)
                .put("action", actionName)
                .put("display_id", displayId)
                .put("detail", actionDetail)
                .put("action_delivered", submitted)
                .put("action_submitted", submitted)
                .put("ui_changed", uiChanged)
                .put("goal_reached", pkgChanged)
                .put("package_changed", pkgChanged)
                .put("wait_ms", actualWait)
                .put("feedback", feedback)
                .put("screen", after);
        if (!submitted) {
            out.put("error_code", "action_failed");
            out.put("stage", "accessibility_dispatch");
            out.put("retryable", true);
        }
        return out;
    }

    interface ActionRunner {
        boolean run() throws Exception;
    }

    /** 优先按控件 view_id，其次按 node_index 或坐标点击，支持指定 displayId 与目标守卫 */
    JSONObject tapClosedLoop(int displayId, String expectedPackage, String expectedWindow,
                             String viewId, Integer x, Integer y, Integer nodeIndex, int durationMs, int waitMs) throws Exception {
        int pressMs = Math.max(60, Math.min(3000, durationMs));
        if (viewId != null && !viewId.trim().isEmpty()) {
            final String targetId = viewId.trim();
            return runClosedLoopAction(displayId, expectedPackage, expectedWindow, "screen_tap",
                    "display=" + displayId + ", view_id=" + targetId, () -> clickByViewId(displayId, targetId, pressMs), waitMs);
        }
        final int[] coords = new int[2];
        String detail;
        List<Rect> boundsList = lastInteractiveBoundsByDisplay.getOrDefault(displayId, java.util.Collections.emptyList());
        if (nodeIndex != null && nodeIndex >= 0 && nodeIndex < boundsList.size()) {
            Rect r = boundsList.get(nodeIndex);
            coords[0] = r.centerX();
            coords[1] = r.centerY();
            detail = "display=" + displayId + ", node_index=" + nodeIndex + " @(" + coords[0] + "," + coords[1] + ")";
        } else if (x != null && y != null) {
            coords[0] = x;
            coords[1] = y;
            detail = "display=" + displayId + " (" + x + "," + y + ")";
        } else {
            throw new IllegalArgumentException("必须提供 view_id、当前 display_id=" + displayId + " 下有效的 node_index 或 (x, y) 坐标");
        }
        return runClosedLoopAction(displayId, expectedPackage, expectedWindow, "screen_tap", detail,
                () -> tapRaw(displayId, coords[0], coords[1], pressMs), waitMs);
    }

    private boolean clickByViewId(int displayId, String viewId, int pressMs) {
        AccessibilityNodeInfo root = findBestRootWindow(displayId);
        if (root == null) return false;
        try {
            AccessibilityNodeInfo matched = findNodeByViewId(root, viewId);
            if (matched == null) return false;
            try {
                Rect bounds = new Rect();
                matched.getBoundsInScreen(bounds);
                boolean clicked = false;
                if (pressMs < 300) {
                    AccessibilityNodeInfo clickable = findClickableNodeOrAncestor(matched);
                    if (clickable != null) {
                        clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        if (clickable != matched) clickable.recycle();
                    } else {
                        clicked = matched.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
                if (clicked) return true;
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return tapRaw(displayId, bounds.centerX(), bounds.centerY(), pressMs);
                }
                return false;
            } finally {
                matched.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findNodeByViewId(AccessibilityNodeInfo node, String targetId) {
        if (node == null || !node.isVisibleToUser()) return null;
        String rawId = node.getViewIdResourceName();
        if (rawId != null) {
            String shortId = shortenResourceId(rawId);
            if (rawId.equalsIgnoreCase(targetId) || shortId.equalsIgnoreCase(targetId)) {
                return AccessibilityNodeInfo.obtain(node);
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    AccessibilityNodeInfo found = findNodeByViewId(child, targetId);
                    if (found != null) return found;
                } finally {
                    child.recycle();
                }
            }
        }
        return null;
    }

    boolean tap(int x, int y) {
        return tapRaw(Display.DEFAULT_DISPLAY, x, y, 80);
    }

    boolean tapRaw(int displayId, int x, int y, int durationMs) {
        if (x < 0 || y < 0) return false;
        DisplayMetrics m = getDisplayMetricsFor(displayId);
        if (x >= m.widthPixels || y >= m.heightPixels) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder gb = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
        if (Build.VERSION.SDK_INT >= 30) {
            gb.setDisplayId(displayId);
        }
        boolean ok = dispatchGestureSync(gb.build(), durationMs + 600);
        if (!ok && displayId > Display.DEFAULT_DISPLAY && ShizukuBridge.isRunning() && ShizukuBridge.isAuthorized()) {
            return ShizukuBridge.inputTapOnDisplay(displayId, x, y);
        }
        return ok;
    }

    /**
     * 按文本 / 描述 / 资源 ID 智能查找并点击（支持指定 displayId 与目标守卫）
     */
    JSONObject clickTextClosedLoop(int displayId, String expectedPackage, String expectedWindow,
                                   String query, String matchMode, int indexOffset, int waitMs) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        final String target = query.trim();
        final boolean exact = "exact".equalsIgnoreCase(matchMode);
        final int occ = Math.max(0, indexOffset);

        return runClosedLoopAction(displayId, expectedPackage, expectedWindow,
                "screen_click_text", "display=" + displayId + ", text=\"" + target + "\" (occurrence=" + occ + ")", () -> {
            AccessibilityNodeInfo root = findBestRootWindow(displayId);
            if (root == null) return false;
            try {
                List<AccessibilityNodeInfo> matches = new ArrayList<>();
                collectMatchingNodes(root, target, exact, matches);
                if (occ >= matches.size()) {
                    for (AccessibilityNodeInfo m : matches) m.recycle();
                    return false;
                }
                AccessibilityNodeInfo chosen = matches.get(occ);
                Rect bounds = new Rect();
                chosen.getBoundsInScreen(bounds);

                boolean clickedByAction = false;
                AccessibilityNodeInfo clickable = findClickableNodeOrAncestor(chosen);
                if (clickable != null) {
                    clickedByAction = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (clickable != chosen) clickable.recycle();
                } else {
                    clickedByAction = chosen.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                for (AccessibilityNodeInfo m : matches) m.recycle();

                if (clickedByAction) return true;
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return tapRaw(displayId, bounds.centerX(), bounds.centerY(), 80);
                }
                return false;
            } finally {
                root.recycle();
            }
        }, waitMs);
    }

    private void collectMatchingNodes(AccessibilityNodeInfo node, String target, boolean exact, List<AccessibilityNodeInfo> out) {
        if (node == null || !node.isVisibleToUser()) return;
        String t = (!node.isPassword() && node.getText() != null) ? node.getText().toString().trim() : "";
        String d = (!node.isPassword() && node.getContentDescription() != null) ? node.getContentDescription().toString().trim() : "";
        String h = (Build.VERSION.SDK_INT >= 26 && node.getHintText() != null) ? node.getHintText().toString().trim() : "";
        String id = node.getViewIdResourceName() != null ? shortenResourceId(node.getViewIdResourceName()) : "";

        String lowerTarget = target.toLowerCase(Locale.ROOT);
        boolean matched = exact
                ? (t.equalsIgnoreCase(target) || d.equalsIgnoreCase(target) || h.equalsIgnoreCase(target) || id.equalsIgnoreCase(target))
                : (t.toLowerCase(Locale.ROOT).contains(lowerTarget)
                        || d.toLowerCase(Locale.ROOT).contains(lowerTarget)
                        || h.toLowerCase(Locale.ROOT).contains(lowerTarget)
                        || id.toLowerCase(Locale.ROOT).contains(lowerTarget));
        if (matched) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    collectMatchingNodes(child, target, exact, out);
                } finally {
                    child.recycle();
                }
            }
        }
    }

    private AccessibilityNodeInfo findClickableNodeOrAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
        for (int depth = 0; depth < 5 && cur != null; depth++) {
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            cur.recycle();
            cur = parent;
        }
        if (cur != null) cur.recycle();
        return null;
    }

    /**
     * 增强型输入：优先按 view_id，其次按 targetHint / 焦点框 / 首个可编辑框定位；
     * 严格区分 text_filled（已填入）、enter_submitted（已提交回车）与 executed（观察到实际执行/状态变化）。
     */
    JSONObject setTextClosedLoop(int displayId, String expectedPackage, String expectedWindow,
                                 String viewId, String text, String targetHint, boolean submitEnter, int waitMs) throws Exception {
        if (text == null || text.length() > 4000) throw new IllegalArgumentException("输入文本无效或过长");
        final boolean[] textFilled = new boolean[] { false };
        final boolean[] enterSubmitted = new boolean[] { false };
        final String[] targetViewIdUsed = new String[] { "" };
        JSONObject res = runClosedLoopAction(displayId, expectedPackage, expectedWindow,
                "screen_set_text", "display=" + displayId + ", text=\"" + text + "\"", () -> {
            return inputTextSmart(displayId, viewId, text, targetHint, submitEnter, textFilled, enterSubmitted, targetViewIdUsed);
        }, waitMs);

        // 严格依据动作后的界面/包名变化或输入框内容已被消费，判定 executed，禁止假阳性
        boolean uiChanged = res.optBoolean("ui_changed", false);
        boolean pkgChanged = res.optBoolean("package_changed", false);
        boolean inputConsumed = false;
        if (textFilled[0] && submitEnter) {
            String afterFieldText = readCurrentEditableText(displayId, targetViewIdUsed[0]);
            if (afterFieldText != null && !afterFieldText.equals(text)) {
                inputConsumed = true;
            }
        }
        boolean executed = submitEnter && enterSubmitted[0] && (uiChanged || pkgChanged || inputConsumed);
        res.put("text_filled", textFilled[0]);
        res.put("enter_submitted", enterSubmitted[0]);
        res.put("executed", executed);
        if (submitEnter) {
            res.put("goal_reached", executed);
            if (textFilled[0] && enterSubmitted[0] && !executed) {
                res.put("feedback", "⚠️ 文本已填入且回车已提交，但未观察到界面变化或输入被消费，命令可能尚未真正执行。");
            }
        }
        return res;
    }

    private String readCurrentEditableText(int displayId, String viewId) {
        AccessibilityNodeInfo root = findBestRootWindow(displayId);
        if (root == null) return null;
        AccessibilityNodeInfo node = null;
        try {
            if (viewId != null && !viewId.isEmpty()) node = findNodeByViewId(root, viewId);
            if (node == null) node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (node != null && node.getText() != null) return node.getText().toString();
            return null;
        } finally {
            if (node != null) node.recycle();
            root.recycle();
        }
    }

    boolean inputText(String text) {
        return inputTextSmart(Display.DEFAULT_DISPLAY, null, text, null, false, new boolean[1], new boolean[1], new String[1]);
    }

    private boolean inputTextSmart(int displayId, String viewId, String text, String targetHint, boolean submitEnter,
                                   boolean[] textFilledOut, boolean[] enterSubmittedOut, String[] viewIdUsedOut) {
        AccessibilityNodeInfo root = findBestRootWindow(displayId);
        if (root == null) return false;
        AccessibilityNodeInfo targetNode = null;
        try {
            if (viewId != null && !viewId.trim().isEmpty()) {
                AccessibilityNodeInfo byId = findNodeByViewId(root, viewId.trim());
                if (byId != null && !byId.isPassword()) targetNode = byId;
                else if (byId != null) byId.recycle();
            }
            if (targetNode == null && targetHint != null && !targetHint.trim().isEmpty()) {
                targetNode = findEditableNodeByHint(root, targetHint.trim());
            }
            if (targetNode == null) {
                targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (targetNode != null && (!targetNode.isEditable() || targetNode.isPassword())) {
                    targetNode.recycle();
                    targetNode = null;
                }
            }
            if (targetNode == null) {
                targetNode = findFirstEditableNode(root);
            }
            if (targetNode == null || targetNode.isPassword()) return false;
            if (targetNode.getViewIdResourceName() != null) {
                viewIdUsedOut[0] = targetNode.getViewIdResourceName();
            }

            if (!targetNode.isFocused()) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Rect r = new Rect();
                targetNode.getBoundsInScreen(r);
                if (r.width() > 0 && r.height() > 0) {
                    tapRaw(displayId, r.centerX(), r.centerY(), 60);
                }
                try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            }

            String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
            boolean isTermux = "com.termux".equals(pkg);
            String payload = (submitEnter && isTermux && !text.endsWith("\r") && !text.endsWith("\n"))
                    ? (text + "\r")
                    : text;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, payload);
            boolean setOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            textFilledOut[0] = setOk;
            if (setOk && submitEnter) {
                if (payload.endsWith("\r") || payload.endsWith("\n")) {
                    enterSubmittedOut[0] = true;
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    try { Thread.sleep(120); } catch (InterruptedException ignored) { }
                    if (targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                        enterSubmittedOut[0] = true;
                    }
                }
                if (!enterSubmittedOut[0] && displayId > Display.DEFAULT_DISPLAY
                        && ShizukuBridge.isRunning() && ShizukuBridge.isAuthorized()) {
                    if (ShizukuBridge.inputKeyOnDisplay(displayId, 66)) { // KEYCODE_ENTER
                        enterSubmittedOut[0] = true;
                    }
                }
            }
            return setOk;
        } finally {
            if (targetNode != null) targetNode.recycle();
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findEditableNodeByHint(AccessibilityNodeInfo node, String hint) {
        if (node == null || !node.isVisibleToUser()) return null;
        String lower = hint.toLowerCase(Locale.ROOT);
        if (node.isEditable() && !node.isPassword()) {
            String t = node.getText() != null ? node.getText().toString().toLowerCase(Locale.ROOT) : "";
            String d = node.getContentDescription() != null ? node.getContentDescription().toString().toLowerCase(Locale.ROOT) : "";
            String h = (Build.VERSION.SDK_INT >= 26 && node.getHintText() != null) ? node.getHintText().toString().toLowerCase(Locale.ROOT) : "";
            String id = node.getViewIdResourceName() != null ? shortenResourceId(node.getViewIdResourceName()).toLowerCase(Locale.ROOT) : "";
            if (t.contains(lower) || d.contains(lower) || h.contains(lower) || id.contains(lower)) {
                return AccessibilityNodeInfo.obtain(node);
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    AccessibilityNodeInfo found = findEditableNodeByHint(child, hint);
                    if (found != null) return found;
                } finally {
                    child.recycle();
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstEditableNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser()) return null;
        if (node.isEditable() && !node.isPassword()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    AccessibilityNodeInfo found = findFirstEditableNode(child);
                    if (found != null) return found;
                } finally {
                    child.recycle();
                }
            }
        }
        return null;
    }

    /** 支持按方向 (up/down/left/right) 或起止坐标在指定 display_id 上滑动并自动返回闭环快照 */
    JSONObject swipeClosedLoop(int displayId, String expectedPackage, String expectedWindow,
                               String direction, Integer x1, Integer y1, Integer x2, Integer y2, int durationMs, int waitMs) throws Exception {
        DisplayMetrics m = getDisplayMetricsFor(displayId);
        int w = m.widthPixels;
        int h = m.heightPixels;
        final int sx1, sy1, sx2, sy2;
        if (direction != null && !direction.trim().isEmpty()) {
            String dir = direction.trim().toLowerCase(Locale.ROOT);
            if ("up".equals(dir) || "scroll_down".equals(dir)) {
                sx1 = w / 2; sy1 = (int) (h * 0.76); sx2 = w / 2; sy2 = (int) (h * 0.24);
            } else if ("down".equals(dir) || "scroll_up".equals(dir)) {
                sx1 = w / 2; sy1 = (int) (h * 0.24); sx2 = w / 2; sy2 = (int) (h * 0.76);
            } else if ("left".equals(dir)) {
                sx1 = (int) (w * 0.85); sy1 = h / 2; sx2 = (int) (w * 0.15); sy2 = h / 2;
            } else if ("right".equals(dir)) {
                sx1 = (int) (w * 0.15); sy1 = h / 2; sx2 = (int) (w * 0.85); sy2 = h / 2;
            } else {
                throw new IllegalArgumentException("不支持的滑动方向：" + direction + "（可选 up / down / left / right）");
            }
        } else if (x1 != null && y1 != null && x2 != null && y2 != null) {
            sx1 = x1; sy1 = y1; sx2 = x2; sy2 = y2;
        } else {
            throw new IllegalArgumentException("请提供 direction (up/down/left/right) 或完整的 (x1, y1, x2, y2) 坐标");
        }
        int dur = Math.max(120, Math.min(2000, durationMs));
        String detail = "display=" + displayId + " (" + sx1 + "," + sy1 + ")->(" + sx2 + "," + sy2 + ")";
        return runClosedLoopAction(displayId, expectedPackage, expectedWindow, "screen_swipe", detail,
                () -> swipeRaw(displayId, sx1, sy1, sx2, sy2, dur), waitMs);
    }

    boolean swipe(int x1, int y1, int x2, int y2) {
        return swipeRaw(Display.DEFAULT_DISPLAY, x1, y1, x2, y2, 350);
    }

    private boolean swipeRaw(int displayId, int x1, int y1, int x2, int y2, int durationMs) {
        DisplayMetrics m = getDisplayMetricsFor(displayId);
        if (x1 < 0 || x2 < 0 || y1 < 0 || y2 < 0
                || x1 >= m.widthPixels || x2 >= m.widthPixels
                || y1 >= m.heightPixels || y2 >= m.heightPixels) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.Builder gb = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
        if (Build.VERSION.SDK_INT >= 30) {
            gb.setDisplayId(displayId);
        }
        boolean ok = dispatchGestureSync(gb.build(), durationMs + 700);
        if (!ok && displayId > Display.DEFAULT_DISPLAY && ShizukuBridge.isRunning() && ShizukuBridge.isAuthorized()) {
            return ShizukuBridge.inputSwipeOnDisplay(displayId, x1, y1, x2, y2, durationMs);
        }
        return ok;
    }

    /** 同步等待手势真正执行完毕后再开始计算 UI 稳定时间 */
    private boolean dispatchGestureSync(GestureDescription gesture, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] completed = new boolean[]{false};
        final boolean[] cancelled = new boolean[]{false};
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                completed[0] = true;
                latch.countDown();
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                cancelled[0] = true;
                latch.countDown();
            }
        }, main);
        if (!accepted) return false;
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) { }
        return !cancelled[0] && (completed[0] || accepted);
    }

    /** 系统按键闭环操作（支持 display_id 与目标守卫，副屏绝不误触发主屏 globalAction） */
    JSONObject pressSystemKeyClosedLoop(int displayId, String expectedPackage, String expectedWindow,
                                        String keyAction, int waitMs) throws Exception {
        String k = (keyAction == null || keyAction.trim().isEmpty()) ? "back" : keyAction.trim().toLowerCase(Locale.ROOT);
        if (displayId > Display.DEFAULT_DISPLAY) {
            final int keyCode;
            if ("back".equals(k)) keyCode = 4; // KEYCODE_BACK
            else if ("home".equals(k)) keyCode = 3; // KEYCODE_HOME
            else if ("enter".equals(k)) keyCode = 66; // KEYCODE_ENTER
            else throw new IllegalArgumentException("虚拟屏仅支持 back / home / enter 按键，禁止在副屏执行 " + keyAction);
            return runClosedLoopAction(displayId, expectedPackage, expectedWindow, "screen_press_key",
                    "display=" + displayId + ", key=" + k, () -> ShizukuBridge.inputKeyOnDisplay(displayId, keyCode), waitMs);
        }
        final int globalAction;
        if ("back".equals(k)) globalAction = GLOBAL_ACTION_BACK;
        else if ("home".equals(k)) globalAction = GLOBAL_ACTION_HOME;
        else if ("recents".equals(k)) globalAction = GLOBAL_ACTION_RECENTS;
        else if ("notifications".equals(k)) globalAction = GLOBAL_ACTION_NOTIFICATIONS;
        else if ("quick_settings".equals(k)) globalAction = GLOBAL_ACTION_QUICK_SETTINGS;
        else throw new IllegalArgumentException("不支持的系统动作：" + keyAction + "（可选 back / home / recents / notifications / quick_settings）");

        return runClosedLoopAction(displayId, expectedPackage, expectedWindow, "screen_press_key", k,
                () -> performGlobalAction(globalAction), waitMs);
    }

    /** 等待指定文本出现或等待固定毫秒后返回最新快照 */
    JSONObject waitForScreen(int displayId, String untilText, int waitMs, int timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        if (untilText == null || untilText.trim().isEmpty()) {
            int sleep = Math.max(100, Math.min(10000, waitMs));
            Thread.sleep(sleep);
            JSONObject snap = captureSnapshot(displayId);
            return new JSONObject()
                    .put("action", "screen_wait")
                    .put("display_id", displayId)
                    .put("waited_ms", System.currentTimeMillis() - start)
                    .put("screen", snap);
        }
        String needle = untilText.trim().toLowerCase(Locale.ROOT);
        long maxWait = Math.max(500, Math.min(15000, timeoutMs));
        JSONObject lastSnap = null;
        boolean matched = false;
        while (System.currentTimeMillis() - start < maxWait) {
            lastSnap = captureSnapshot(displayId);
            if (lastSnap.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                matched = true;
                break;
            }
            Thread.sleep(350);
        }
        if (lastSnap == null) lastSnap = captureSnapshot(displayId);
        return new JSONObject()
                .put("action", "screen_wait")
                .put("display_id", displayId)
                .put("until_text", untilText)
                .put("matched", matched)
                .put("waited_ms", System.currentTimeMillis() - start)
                .put("screen", lastSnap);
    }

    /**
     * Android 11+ (API 30+) 免弹窗无障碍原生截图（支持指定 display_id），返回压缩后的 JPEG Base64 字符串。
     */
    String captureScreenshotBase64(int displayId, int maxWidth, int quality) throws Exception {
        ScreenApprovalActivity.checkDeviceUnlocked(this);
        if (Build.VERSION.SDK_INT < 30) {
            throw new UnsupportedOperationException("当前 Android 版本低于 11，不支持无障碍直接截图");
        }
        getDisplayMetricsFor(displayId); // 校验目标屏幕存在
        AccessibilityWindowInfo win = findBestWindowInfo(displayId);
        AccessibilityNodeInfo root = win != null ? win.getRoot() : (displayId == Display.DEFAULT_DISPLAY ? findBestRootWindow() : null);
        if (root != null) {
            try {
                String fgPkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
                if (isProtectedPackage(fgPkg)) {
                    throw new SecurityException("安全保护：禁止对本应用、系统权限弹窗、安装器或 Shizuku 界面截图（" + fgPkg + "）");
                }
            } finally {
                root.recycle();
            }
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmapRef = new AtomicReference<>(null);
        final AtomicReference<String> errRef = new AtomicReference<>(null);

        if (displayId == Display.DEFAULT_DISPLAY) {
            main.post(() -> {
                if (overlay != null) overlay.setVisibility(View.INVISIBLE);
            });
            Thread.sleep(60);
        }

        try {
            takeScreenshot(displayId, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult screenshot) {
                    try (HardwareBuffer hb = screenshot.getHardwareBuffer()) {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, screenshot.getColorSpace());
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            bitmapRef.set(sw);
                        } else {
                            errRef.set("wrapHardwareBuffer 返回空");
                        }
                    } catch (Throwable t) {
                        errRef.set(t.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }

                @Override public void onFailure(int errorCode) {
                    errRef.set("takeScreenshot 失败，errorCode=" + errorCode);
                    latch.countDown();
                }
            });
            latch.await(5000, TimeUnit.MILLISECONDS);
        } finally {
            main.post(() -> {
                if (overlay != null) overlay.setVisibility(View.VISIBLE);
            });
        }

        Bitmap bmp = bitmapRef.get();
        if (bmp == null && displayId > Display.DEFAULT_DISPLAY && ShizukuBridge.isRunning() && ShizukuBridge.isAuthorized()) {
            try {
                byte[] png = ShizukuBridge.screencapPngOnDisplay(displayId);
                bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
            } catch (Exception ignored) { }
        }
        if (bmp == null) {
            throw new IllegalStateException(errRef.get() != null ? errRef.get() : "截图超时");
        }
        try {
            int targetW = Math.max(240, Math.min(1080, maxWidth));
            if (bmp.getWidth() > targetW) {
                int targetH = Math.round((float) bmp.getHeight() * targetW / bmp.getWidth());
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            int q = Math.max(30, Math.min(90, quality));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } finally {
            bmp.recycle();
        }
    }
}
