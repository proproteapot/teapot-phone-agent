package com.teapot.phoneagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.security.SecureRandom;

/**
 * 屏幕控制会话与本机人工确认管理：
 * 默认采用「严格本机确认模式」——远程 Agent 首次请求屏幕控制时，必须在手机本机点击通知或主页按钮批准；
 * 用户也可在手机本机设置中显式开启「2 分钟受控自动续期模式」。
 * 控制期间右上角常驻可随时停止的红色悬浮条，息屏或锁屏立即自动撤销。
 */
public final class ScreenApprovalActivity extends Activity {
    static final int APPROVAL_NOTIFICATION_ID = 6409;
    private static final String PREFS = "screen_approval_prefs";
    private static final String KEY_AUTO_APPROVE = "auto_approve_enabled";
    private static final String CHANNEL_APPROVAL = "screen_approval";
    private static final SecureRandom RNG = new SecureRandom();

    static volatile long expiresAt;
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
                .setMessage("批准后将开启 2 分钟限时屏幕控制会话；控制期间顶部常驻红色停止条，息屏或锁屏立即自动撤销。")
                .setCancelable(false)
                .setPositiveButton("批准（2 分钟）", (d, w) -> {
                    grantSession(this);
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

    static boolean allowed() {
        return SystemClock.elapsedRealtime() < expiresAt;
    }

    static void revoke() {
        expiresAt = 0;
        pendingRequest = null;
    }

    /**
     * 检查屏幕控制授权：
     * - 若当前在有效 2 分钟会话内：直接放行并续期；
     * - 若用户已在手机端显式开启自动续期模式：激活会话；
     * - 否则（默认严格模式）：发送本机批准通知并抛出 SecurityException，等待用户手动批准。
     */
    static synchronized void requireOrActivate(Context context) {
        if (ScreenControlService.instance == null) {
            throw new SecurityException("无障碍服务未启用");
        }
        if (allowed() || isAutoApproveEnabled(context)) {
            grantSession(context);
            return;
        }
        requestManualApproval(context);
        throw new SecurityException("屏幕控制需要手机本机批准：已向手机发送批准通知（也可在「手机助手桥」主页点击批准按钮，或在设置中开启自动续期模式）。");
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
                    .setContentText("点击批准或拒绝（2 分钟限时）")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(APPROVAL_NOTIFICATION_ID, n);
        } catch (Exception ignored) { }
    }

    private static synchronized void grantSession(Context context) {
        if (ScreenControlService.instance == null) {
            throw new IllegalStateException("无障碍服务未启用");
        }
        pendingRequest = null;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(APPROVAL_NOTIFICATION_ID);
        final boolean wasAllowed = allowed();
        expiresAt = SystemClock.elapsedRealtime() + 120000L;
        final long expectedExpiry = expiresAt;
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
        }, 120000L);
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
