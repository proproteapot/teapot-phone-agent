package com.teapot.phoneagent;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Teapot Phone-Agent 主界面：
 * 主页保留高频状态与核心控制按钮；权限与高危能力开关（默认关闭）收纳在折叠面板中。
 */
public final class MainActivity extends Activity {
    private static final int CALENDAR_REQUEST = 10;
    private TextView status;
    private Button approveBtn;
    private Button revokeBtn;
    private Button shizukuBtn;
    private Button filesBtn;
    private Button accessibilityBtn;
    private Button calendarBtn;
    private Button autoApproveBtn;
    private Button termuxExecBtn;
    private Button autoRestoreA11yBtn;
    private LinearLayout settingsPanel;
    private Button toggleSettingsBtn;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            if (status != null) status.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ShizukuBridge.init(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(44, 140, 44, 120);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Teapot Phone-Agent");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, 24, 0, 28);
        root.addView(status);

        approveBtn = new Button(this);
        approveBtn.setText("★ 批准 / 拒绝待处理的屏幕控制请求");
        approveBtn.setVisibility(View.GONE);
        approveBtn.setOnClickListener(v -> {
            String req = ScreenApprovalActivity.pendingRequest;
            if (req != null) {
                startActivity(new Intent(this, ScreenApprovalActivity.class)
                        .setAction("com.teapot.phoneagent.APPROVE." + req).putExtra("request", req));
            }
        });
        root.addView(approveBtn);

        revokeBtn = new Button(this);
        revokeBtn.setText("⏹ 立即结束临时屏幕控制");
        revokeBtn.setVisibility(View.GONE);
        revokeBtn.setOnClickListener(v -> {
            ScreenApprovalActivity.end(this, "你已结束临时屏幕控制");
            refresh();
        });
        root.addView(revokeBtn);

        shizukuBtn = new Button(this);
        shizukuBtn.setText("★ 授权 Shizuku 离屏虚拟屏权限");
        shizukuBtn.setVisibility(View.GONE);
        shizukuBtn.setOnClickListener(v -> {
            ShizukuBridge.requestPermission();
            status.postDelayed(this::refresh, 500);
        });
        root.addView(shizukuBtn);

        Button start = new Button(this);
        start.setText("启动 / 重连服务");
        start.setOnClickListener(v -> {
            TunnelManager.setAutoStartEnabled(this, true);
            startForegroundService(new Intent(this, BridgeService.class));
            new Thread(() -> TunnelManager.start(MainActivity.this)).start();
            status.postDelayed(this::refresh, 800);
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("停止服务");
        stop.setOnClickListener(v -> {
            TunnelManager.setAutoStartEnabled(this, false);
            TunnelManager.stop();
            stopService(new Intent(this, BridgeService.class));
            status.postDelayed(this::refresh, 200);
        });
        root.addView(stop);

        LinearLayout copyRow = new LinearLayout(this);
        copyRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams halfParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);

        Button copyMcpUrl = new Button(this);
        copyMcpUrl.setText("复制 MCP 地址");
        copyMcpUrl.setLayoutParams(halfParams);
        copyMcpUrl.setOnClickListener(v -> {
            String base = TunnelManager.getSavedCustomDomain(this).trim();
            if (base.isEmpty()) {
                android.widget.Toast.makeText(this, "尚未配置公网网关域名，请先在下方设置中填写", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            String mcpUrl = base + "/mcp";
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("phone-agent-mcp-url", mcpUrl));
            android.widget.Toast.makeText(this, "已复制安全接入地址：" + mcpUrl, android.widget.Toast.LENGTH_LONG).show();
        });
        copyRow.addView(copyMcpUrl);

        Button copyToken = new Button(this);
        copyToken.setText("复制连接码");
        copyToken.setLayoutParams(halfParams);
        copyToken.setOnClickListener(v -> {
            String token = new OAuthManager(this).getOrCreatePairingToken();
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            android.content.ClipData clip = android.content.ClipData.newPlainText("phone-agent-token", token);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            cm.setPrimaryClip(clip);
            android.widget.Toast.makeText(this, "已复制连接码（开头：" + token.substring(0, Math.min(6, token.length())) + "...）", android.widget.Toast.LENGTH_SHORT).show();
        });
        copyRow.addView(copyToken);

        toggleSettingsBtn = new Button(this);
        toggleSettingsBtn.setText("⚙️ 展开设置与权限管理");
        toggleSettingsBtn.setOnClickListener(v -> {
            boolean show = settingsPanel.getVisibility() != View.VISIBLE;
            settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            toggleSettingsBtn.setText(show ? "🔼 收起设置与权限管理" : "⚙️ 展开设置与权限管理");
        });
        root.addView(toggleSettingsBtn);

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(16, 20, 16, 20);
        settingsPanel.setVisibility(View.GONE);
        root.addView(settingsPanel);
        settingsPanel.addView(copyRow);

        TextView permHeader = new TextView(this);
        permHeader.setText("── 权限管理 ──");
        permHeader.setTextSize(14);
        permHeader.setPadding(8, 8, 8, 8);
        settingsPanel.addView(permHeader);

        filesBtn = new Button(this);
        filesBtn.setOnClickListener(v -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        settingsPanel.addView(filesBtn);

        accessibilityBtn = new Button(this);
        accessibilityBtn.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        settingsPanel.addView(accessibilityBtn);

        calendarBtn = new Button(this);
        calendarBtn.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, CALENDAR_REQUEST));
        settingsPanel.addView(calendarBtn);

        Button notifications = new Button(this);
        notifications.setText("通知权限（屏幕控制确认与提醒）");
        notifications.setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 12);
        });
        settingsPanel.addView(notifications);

        Button safFiles = new Button(this);
        safFiles.setText("添加单个 SAF 文件目录 (可选)");
        safFiles.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, 11);
        });
        settingsPanel.addView(safFiles);

        TextView safetyHeader = new TextView(this);
        safetyHeader.setText("── 安全开关（高危能力默认关闭）──");
        safetyHeader.setTextSize(14);
        safetyHeader.setPadding(8, 24, 8, 8);
        settingsPanel.addView(safetyHeader);

        autoApproveBtn = new Button(this);
        autoApproveBtn.setOnClickListener(v -> {
            boolean next = !ScreenApprovalActivity.isAutoApproveEnabled(this);
            ScreenApprovalActivity.setAutoApproveEnabled(this, next);
            refresh();
        });
        settingsPanel.addView(autoApproveBtn);

        termuxExecBtn = new Button(this);
        termuxExecBtn.setOnClickListener(v -> {
            boolean next = !TermuxCommandManager.isExecutionEnabled(this);
            TermuxCommandManager.setExecutionEnabled(this, next);
            refresh();
        });
        settingsPanel.addView(termuxExecBtn);

        autoRestoreA11yBtn = new Button(this);
        autoRestoreA11yBtn.setOnClickListener(v -> {
            boolean next = !ShizukuBridge.isAutoRestoreA11yEnabled(this);
            ShizukuBridge.setAutoRestoreA11yEnabled(this, next);
            refresh();
        });
        settingsPanel.addView(autoRestoreA11yBtn);

        TextView advHeader = new TextView(this);
        advHeader.setText("── 公网网关与安全设置 ──");
        advHeader.setTextSize(14);
        advHeader.setPadding(8, 24, 8, 8);
        settingsPanel.addView(advHeader);

        Button configTunnel = new Button(this);
        configTunnel.setText("配置自托管 Cloudflare Worker 域名");
        configTunnel.setOnClickListener(v -> showTunnelConfigDialog());
        settingsPanel.addView(configTunnel);

        Button startLocalOnly = new Button(this);
        startLocalOnly.setText("仅启动本机服务 (127.0.0.1:8765)");
        startLocalOnly.setOnClickListener(v -> {
            TunnelManager.setAutoStartEnabled(this, false);
            TunnelManager.stop();
            startForegroundService(new Intent(this, BridgeService.class));
            status.postDelayed(this::refresh, 800);
        });
        settingsPanel.addView(startLocalOnly);

        Button revokeOAuth = new Button(this);
        revokeOAuth.setText("撤销云端和本机 OAuth 授权");
        revokeOAuth.setOnClickListener(v -> {
            revokeOAuth.setEnabled(false);
            new Thread(() -> {
                new OAuthManager(this).revokeLocalOAuthTokens();
                boolean cloudConfigured = !TunnelManager.getSavedCustomDomain(this).trim().isEmpty();
                boolean cloudOk = false;
                if (cloudConfigured) {
                    try { cloudOk = TunnelManager.revokeCloudOAuthTokens(this); } catch (Exception ignored) { }
                }
                final boolean cConf = cloudConfigured;
                final boolean cOk = cloudOk;
                runOnUiThread(() -> {
                    revokeOAuth.setEnabled(true);
                    String msg = !cConf ? "已撤销本机 OAuth 授权（未配置云端网关）"
                            : (cOk ? "已撤销云端与本机 OAuth 授权；连接码未更改"
                            : "已撤销本机 OAuth 授权；云端撤销失败，请检查网络或重新配置 PHONE_SECRET");
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                    refresh();
                });
            }).start();
        });
        settingsPanel.addView(revokeOAuth);

        Button resetPairing = new Button(this);
        resetPairing.setText("重置连接码并吊销全部授权");
        resetPairing.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("确认重置连接码？")
                .setMessage("重置后将生成全新的本机连接码，暂停公网轮询并吊销已签发的 OAuth 令牌。若使用了 Cloudflare 网关，请同步更新 Worker 的 PHONE_SECRET（更新后云端也会自动作废所有旧 OAuth 令牌）。")
                .setPositiveButton("确认重置", (d, w) -> {
                    resetPairing.setEnabled(false);
                    TunnelManager.stop();
                    new Thread(() -> {
                        boolean cloudConfigured = !TunnelManager.getSavedCustomDomain(this).trim().isEmpty();
                        boolean cloudOk = false;
                        if (cloudConfigured) {
                            try { cloudOk = TunnelManager.revokeCloudOAuthTokens(this); } catch (Exception ignored) { }
                        }
                        new OAuthManager(this).rotatePairingTokenAndRevokeAll();
                        final boolean cConf = cloudConfigured;
                        final boolean cOk = cloudOk;
                        runOnUiThread(() -> {
                            resetPairing.setEnabled(true);
                            String msg = !cConf
                                    ? "已生成新连接码并吊销本机全部授权"
                                    : (cOk
                                    ? "已生成新连接码并吊销云端与本机授权；请更新云端 PHONE_SECRET 后再重连"
                                    : "已生成新连接码并吊销本机授权；云端实时撤销未成功，请立即更新 Worker 的 PHONE_SECRET 以自动作废旧云端令牌");
                            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                            refresh();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show());
        settingsPanel.addView(resetPairing);

        setContentView(scroll);
        refresh();
    }

    private void showTunnelConfigDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 12);
        final android.widget.EditText domainInput = new android.widget.EditText(this);
        domainInput.setHint("https://your-worker.your-subdomain.workers.dev");
        domainInput.setText(TunnelManager.getSavedCustomDomain(this));
        box.addView(domainInput);
        new android.app.AlertDialog.Builder(this)
                .setTitle("配置 Cloudflare 公网网关")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String saved = TunnelManager.saveConfig(this, domainInput.getText().toString());
                    if (saved == null) {
                        android.widget.Toast.makeText(this, "地址无效：仅支持 HTTPS 根域名（如 https://xxx.workers.dev），禁止 http:// 或多余路径", android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    String tip = saved.isEmpty()
                            ? "已清空公网域名（切换为仅本机模式）"
                            : "已保存网关：" + saved + "，点击「启动 / 重连服务」生效";
                    android.widget.Toast.makeText(this, tip, android.widget.Toast.LENGTH_LONG).show();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) {
            status.removeCallbacks(ticker);
            status.post(ticker);
        }
    }

    @Override protected void onPause() {
        if (status != null) status.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 11 && result == RESULT_OK && data != null && data.getData() != null) {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(data.getData(), flags);
        }
        refresh();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        refresh();
    }

    private void refresh() {
        boolean calendar = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        boolean allFiles = android.os.Environment.isExternalStorageManager();
        boolean a11y = ScreenControlService.instance != null;
        boolean shizukuRunning = ShizukuBridge.isRunning();
        boolean shizukuAuth = ShizukuBridge.isAuthorized();
        boolean termuxPerm = checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED;
        boolean termuxEnabled = TermuxCommandManager.isExecutionEnabled(this);
        boolean autoApprove = ScreenApprovalActivity.isAutoApproveEnabled(this);
        boolean autoRestoreA11y = ShizukuBridge.isAutoRestoreA11yEnabled(this);
        boolean controlling = ScreenApprovalActivity.allowed();
        boolean hasPending = ScreenApprovalActivity.pendingRequest != null && !controlling;

        if (approveBtn != null) approveBtn.setVisibility(hasPending ? View.VISIBLE : View.GONE);
        if (revokeBtn != null) revokeBtn.setVisibility(controlling ? View.VISIBLE : View.GONE);
        if (shizukuBtn != null) shizukuBtn.setVisibility((shizukuRunning && !shizukuAuth) ? View.VISIBLE : View.GONE);
        if (filesBtn != null) filesBtn.setText("完整文件与相册访问（" + (allFiles ? "已授权 ✓" : "未授权 - 点击开启") + "）");
        if (accessibilityBtn != null) accessibilityBtn.setText("无障碍屏幕控制与保活（" + (a11y ? "已启用 ✓" : "未启用 - 点击开启") + "）");
        if (calendarBtn != null) calendarBtn.setText("系统日历读写权限（" + (calendar ? "已授权 ✓" : "未授权 - 点击开启") + "）");
        if (autoApproveBtn != null) autoApproveBtn.setText("屏幕控制免确认自动续期：" + (autoApprove ? "已开启" : "关闭（推荐：每次本机确认）"));
        if (termuxExecBtn != null) termuxExecBtn.setText("允许后台 Termux 命令执行：" + (termuxEnabled ? "已开启" : "关闭（默认）"));
        if (autoRestoreA11yBtn != null) autoRestoreA11yBtn.setText("Shizuku 自动恢复被误杀的无障碍：" + (autoRestoreA11y ? "已开启" : "关闭（默认）"));

        String controlState = controlling
                ? "进行中（剩余 " + Math.max(0, (ScreenApprovalActivity.expiresAt - android.os.SystemClock.elapsedRealtime() + 999) / 1000) + " 秒）"
                : (autoApprove ? "待机（自动续期模式）" : "关闭（需手机本机批准）");
        String shizukuState = !ShizukuBridge.isInstalled(this) ? "未安装"
                : (!shizukuRunning ? "未启动" : (shizukuAuth ? "已就绪 ✓" : "未授权（点上方按钮授权）"));

        status.setText("服务状态：" + (BridgeService.running ? "运行中" : "已停止")
                + "\n公网网关：" + TunnelManager.statusText
                + "\n屏幕控制：" + controlState
                + "\n权限概览：文件 " + (allFiles ? "✓" : "✗")
                + " · 无障碍 " + (a11y ? "✓" : "✗")
                + " · 日历 " + (calendar ? "✓" : "✗")
                + "\nShizuku：" + shizukuState
                + " · Termux：" + (termuxPerm ? (termuxEnabled ? "已开启 ✓" : "已授权/开关关闭") : "未授权")
                + "\n\n公网域名与安全开关请在下方设置面板中配置。");
    }
}
