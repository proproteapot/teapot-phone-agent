package com.teapot.phoneagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.security.SecureRandom;

/**
 * 屏幕控制会话与本机人工确认管理：
 * - 默认「严格本机确认模式」：首次请求必须由用户在本机点击批准，单次会话无操作超时 2 分钟、绝对总上限 10 分钟；
 * - 可选「宽松自动续期模式」：用户可在手机设置中显式开启；
 * - 任何模式下，锁屏或息屏均强制拒绝激活并立即撤销现有会话。
 */
public final class ScreenApprovalActivity extends Activity {
    static final int APPROVAL_NOTIFICATION_ID = 6409;
    private static final long IDLE_TIMEOUT_MS = 120000L;
    private static final long HARD_CAP_MS = 10 * 60 * 1000L;
    private static final String PREFS = "screen_approval_prefs";
    private static final String KEY_AUTO_APPROVE = "auto_approve_enabled";
    private static final String CHANNEL_APPROVAL = "screen_approval";
    private static final SecureRandom RNG = new SecureRandom();

    static volatile long expiresAt;
    static volatile long sessionHardCapAt;
    static volatile String pendingRequest;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String req = getIntent() != null ? getIntent().getStringExtra("request") : null;
        if (req == null || !req.equals(pendingRequest)) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("允许远程 Agent 临时控制屏幕？")
                .setMessage("批准后将开启临时屏幕控制会话（无操作 2 分钟结束，单次最长 10 分钟）；控制期间顶部常驻红色停止条，息屏或锁屏立即自动撤销。")
                .setCancelable(false)
                .setPositiveButton("批准", (d, w) -> {
                    try {
                        grantSession(this, true);
                    } catch (Exception ignored) { }
                    finish();
                })
                .setNegativeButton("拒绝", (d, w) -> {
                    pendingRequest = null;
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) nm.cancel(APPROVAL_NOTIFICATION_ID);
                    finish();
                })
                .show();
    }

    static boolean isAutoApproveEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_APPROVE, false);
    }

    static void setAutoApproveEnabled(Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_APPROVE, enabled).commit();
    }

    static void checkDeviceUnlocked(Context context) {
        Context app = context.getApplicationContext();
        KeyguardManager guard = (KeyguardManager) app.getSystemService(KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) app.getSystemService(POWER_SERVICE);
        if ((guard != null && guard.isDeviceLocked()) || (power != null && !power.isInteractive())) {
            end(app, "手机已锁屏或息屏");
            throw new SecurityException("手机当前处于锁屏或息屏状态，拒绝执行屏幕或虚拟屏操作");
        }
    }

    static boolean allowed() {
        long now = SystemClock.elapsedRealtime();
        return now < expiresAt && (sessionHardCapAt == 0 || now < sessionHardCapAt);
    }

    static void revoke() {
        expiresAt = 0;
        sessionHardCapAt = 0;
        pendingRequest = null;
    }

    static synchronized void requireOrActivate(Context context) {
        checkDeviceUnlocked(context);
        if (ScreenControlService.instance == null) {
            throw new SecurityException("无障碍服务未启用");
        }
        if (allowed()) {
            grantSession(context, false);
            return;
        }
        if (isAutoApproveEnabled(context)) {
            grantSession(context, true);
            return;
        }
        requestManualApproval(context);
        throw new SecurityException("屏幕控制需要手机本机批准：已向手机发送批准通知（也可在主页点击批准按钮，或在设置中开启宽松自动续期模式）。");
    }

    static synchronized void autoActivate(Context context) {
        requireOrActivate(context);
    }

    private static void requestManualApproval(Context context) {
        Context app = context.getApplicationContext();
        String req = Long.toHexString(RNG.nextLong());
        pendingRequest = req;
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_APPROVAL, "屏幕控制人工确认", NotificationManager.IMPORTANCE_HIGH));
            Intent approveIntent = new Intent(app, ScreenApprovalActivity.class)
                    .setAction("com.teapot.phoneagent.APPROVE." + req)
                    .putExtra("request", req)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(
                    app, 6409, approveIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(app, CHANNEL_APPROVAL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Agent 请求临时屏幕控制")
                    .setContentText("点击批准或拒绝（单次最长 10 分钟）")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(APPROVAL_NOTIFICATION_ID, n);
        } catch (Exception ignored) { }
    }

    private static synchronized void grantSession(Context context, boolean freshGrant) {
        checkDeviceUnlocked(context);
        if (ScreenControlService.instance == null) {
            throw new IllegalStateException("无障碍服务未启用");
        }
        pendingRequest = null;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(APPROVAL_NOTIFICATION_ID);

        long now = SystemClock.elapsedRealtime();
        final boolean wasAllowed = allowed();
        if (freshGrant || sessionHardCapAt == 0 || now >= sessionHardCapAt) {
            sessionHardCapAt = now + HARD_CAP_MS;
        }
        long nextExpiry = Math.min(now + IDLE_TIMEOUT_MS, sessionHardCapAt);
        expiresAt = nextExpiry;
        final long expectedExpiry = expiresAt;
        final long delayMs = Math.max(1000L, nextExpiry - now);
        final Context appCtx = context.getApplicationContext();
        MAIN.post(() -> {
            if (ScreenControlService.instance != null && !wasAllowed) {
                ScreenControlService.instance.showActiveOverlay();
            }
        });
        MAIN.postDelayed(() -> {
            if (expiresAt == expectedExpiry) {
                end(appCtx, "临时控制会话已到期");
            }
        }, delayMs);
    }

    static void end(Context context, String reason) {
        boolean wasActive = expiresAt != 0;
        revoke();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(APPROVAL_NOTIFICATION_ID);
            manager.cancel(6410);
            manager.cancel(6411);
        }
        if (wasActive && ScreenControlService.instance != null) {
            ScreenControlService.instance.showEndedOverlay();
        }
    }
}
