package com.teapot.phoneagent;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 Termux RUN_COMMAND 的后台命令与长期服务管理器：
 * - 日志与进程组元数据保存在 Termux 私有目录 $HOME/.phone-agent-tasks/（无需 /sdcard 存储权限）；
 * - 长期服务使用 setsid 独立进程组启动，stop 时按负 PGID 彻底终止整棵进程树；
 * - 区分一次性命令与长期服务，内置本机 HTTP/TCP 就绪探测与重连/重启后的状态持久化查询。
 */
final class TermuxCommandManager {
    private static final String TERMUX_PKG = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final String PREFS = "termux_tasks_v2";
    private static final String KEY_TASKS = "tasks_json";
    private static final String KEY_EXECUTION_ENABLED = "termux_exec_enabled";
    private static final String TASK_DIR_EXPR = "$HOME/.phone-agent-tasks";
    private static final Pattern VALID_TASK_ID = Pattern.compile("^t_[0-9]+_[0-9a-f]+$");
    private static final int BRIDGE_LOCAL_PORT = 8765;

    private static final Map<String, TaskRecord> TASKS = new ConcurrentHashMap<>();
    private static final Map<String, SyncWaiter> SYNC_WAITERS = new ConcurrentHashMap<>();
    private static final SecureRandom RNG = new SecureRandom();
    private static final AtomicInteger REQ_SEQ = new AtomicInteger(100);
    private static volatile boolean loaded;

    static final class CallbackResult {
        final String stdout;
        final String stderr;
        final int exitCode;
        final int err;
        final String errmsg;

        CallbackResult(String stdout, String stderr, int exitCode, int err, String errmsg) {
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exitCode = exitCode;
            this.err = err;
            this.errmsg = errmsg == null ? "" : errmsg;
        }
    }

    private static final class SyncWaiter {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile CallbackResult result;
    }

    static final class TaskRecord {
        final String taskId;
        final String command;
        final String workdir;
        final String mode; // oneshot | service
        final String serviceName;
        final String probeUrl;
        final int probePort;
        final long createdAt;
        volatile long updatedAt;
        volatile String stage; // submitted | process_started | service_ready | completed | stopped | failed
        volatile int pgid = -1;
        volatile int exitCode = -1;
        volatile int termuxErr = 0;
        volatile String errorMessage = "";
        volatile String lastOutput = "";
        volatile boolean processAlive;
        volatile boolean httpReachable;
        volatile int httpStatus;

        TaskRecord(String taskId, String command, String workdir, String mode, String serviceName,
                   String probeUrl, int probePort, long createdAt) {
            this.taskId = taskId;
            this.command = command;
            this.workdir = workdir;
            this.mode = mode;
            this.serviceName = serviceName;
            this.probeUrl = probeUrl;
            this.probePort = probePort;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.stage = "submitted";
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("task_id", taskId)
                        .put("mode", mode)
                        .put("service_name", serviceName)
                        .put("command", command)
                        .put("workdir", workdir)
                        .put("stage", stage)
                        .put("pgid", pgid)
                        .put("process_alive", processAlive)
                        .put("exit_code", exitCode)
                        .put("termux_err", termuxErr)
                        .put("error_message", errorMessage)
                        .put("probe_url", probeUrl)
                        .put("probe_port", probePort)
                        .put("http_reachable", httpReachable)
                        .put("http_status", httpStatus)
                        .put("created_at", createdAt)
                        .put("updated_at", updatedAt);
            } catch (Exception ignored) { }
            return o;
        }
    }

    private TermuxCommandManager() { }

    static synchronized void ensureLoaded(Context context) {
        if (loaded || context == null) return;
        loaded = true;
        try {
            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_TASKS, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("task_id", "");
                if (!isValidTaskId(id)) continue;
                TaskRecord r = new TaskRecord(
                        id,
                        o.optString("command", ""),
                        o.optString("workdir", ""),
                        o.optString("mode", "oneshot"),
                        o.optString("service_name", ""),
                        o.optString("probe_url", ""),
                        o.optInt("probe_port", 0),
                        o.optLong("created_at", 0)
                );
                r.updatedAt = o.optLong("updated_at", r.createdAt);
                r.stage = o.optString("stage", "submitted");
                r.pgid = o.optInt("pgid", -1);
                r.exitCode = o.optInt("exit_code", -1);
                r.termuxErr = o.optInt("termux_err", 0);
                r.errorMessage = o.optString("error_message", "");
                r.processAlive = o.optBoolean("process_alive", false);
                r.httpReachable = o.optBoolean("http_reachable", false);
                r.httpStatus = o.optInt("http_status", 0);
                TASKS.put(id, r);
            }
        } catch (Exception ignored) { }
    }

    private static void persist(Context context) {
        if (context == null) return;
        try {
            List<TaskRecord> list = new ArrayList<>(TASKS.values());
            Collections.sort(list, (a, b) -> Long.compare(b.createdAt, a.createdAt));
            JSONArray arr = new JSONArray();
            for (int i = 0; i < Math.min(30, list.size()); i++) {
                arr.put(list.get(i).toJson());
            }
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_TASKS, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    static boolean isExecutionEnabled(Context context) {
        if (context == null) return false;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_EXECUTION_ENABLED, false);
    }

    static void setExecutionEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_EXECUTION_ENABLED, enabled).commit();
    }

    static JSONObject getPermissionStatus(Context context) {
        JSONObject o = new JSONObject();
        try {
            boolean termuxInstalled = false;
            try {
                context.getPackageManager().getPackageInfo(TERMUX_PKG, 0);
                termuxInstalled = true;
            } catch (Exception ignored) { }
            boolean hasPermission = context.checkSelfPermission(PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
            boolean userEnabled = isExecutionEnabled(context);
            o.put("termux_installed", termuxInstalled)
                    .put("run_command_permission", hasPermission)
                    .put("user_enabled", userEnabled)
                    .put("ready", termuxInstalled && hasPermission && userEnabled);
        } catch (Exception ignored) { }
        return o;
    }

    static int activeTaskCount(Context context) {
        ensureLoaded(context);
        int count = 0;
        for (TaskRecord r : TASKS.values()) {
            if ("submitted".equals(r.stage) || "process_started".equals(r.stage) || "service_ready".equals(r.stage)) {
                count++;
            }
        }
        return count;
    }

    static JSONObject run(Context context, String command, String workdir, String mode,
                          String serviceName, String probeUrl, int probePort, int waitMs) throws Exception {
        ensureLoaded(context);
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        ensureTermuxReady(context);
        String cleanProbeUrl = probeUrl == null ? "" : probeUrl.trim();
        if (!cleanProbeUrl.isEmpty()) {
            validateLoopbackProbeUrl(cleanProbeUrl);
        }
        if (probePort != 0) {
            validateLoopbackPort(probePort);
        }

        String normalizedMode = "service".equalsIgnoreCase(mode) ? "service" : "oneshot";
        String taskId = "t_" + System.currentTimeMillis() + "_" + randomHex(8);
        String cleanWorkdir = (workdir == null || workdir.trim().isEmpty()) ? "/data/data/com.termux/files/home" : workdir.trim();

        TaskRecord record = new TaskRecord(taskId, command.trim(), cleanWorkdir, normalizedMode,
                serviceName == null ? "" : serviceName.trim(),
                cleanProbeUrl,
                probePort, System.currentTimeMillis());
        TASKS.put(taskId, record);
        persist(context);

        int waitLimit = Math.max(500, Math.min(10000, waitMs));
        if ("service".equals(normalizedMode)) {
            String launchScript = buildServiceLaunchScript(record);
            CallbackResult cb = execSync(context, launchScript, cleanWorkdir, taskId, Math.min(4000, waitLimit));
            if (cb == null) {
                record.stage = "submitted";
            } else if (cb.err > 0) {
                record.termuxErr = cb.err;
                record.errorMessage = cb.errmsg;
                record.stage = "failed";
            } else {
                parseStartedPgid(record, cb.stdout);
                if (record.pgid > 0) {
                    record.processAlive = true;
                    record.stage = "process_started";
                } else if (cb.exitCode != 0) {
                    record.exitCode = cb.exitCode;
                    record.errorMessage = cb.stderr.isEmpty() ? cb.stdout : cb.stderr;
                    record.stage = "failed";
                }
            }

            if (!"failed".equals(record.stage) && (hasProbe(record))) {
                long deadline = System.currentTimeMillis() + waitLimit;
                while (System.currentTimeMillis() < deadline) {
                    updateProbe(record);
                    if (record.httpReachable) {
                        record.stage = "service_ready";
                        break;
                    }
                    Thread.sleep(350);
                }
            }
            record.updatedAt = System.currentTimeMillis();
            persist(context);

            JSONObject out = record.toJson();
            out.put("ok", !"failed".equals(record.stage));
            if (!"failed".equals(record.stage)) {
                out.put("log_tail", fetchLogTail(context, record, 40));
            }
            return out;
        } else {
            String oneshotScript = buildOneshotScript(record);
            CallbackResult cb = execSync(context, oneshotScript, cleanWorkdir, taskId, waitLimit);
            if (cb != null) {
                record.exitCode = cb.exitCode;
                record.termuxErr = cb.err;
                record.errorMessage = cb.errmsg;
                record.lastOutput = cb.stdout.isEmpty() ? cb.stderr : cb.stdout;
                if (cb.err > 0 || cb.exitCode != 0) {
                    record.stage = "failed";
                } else {
                    record.stage = "completed";
                }
            } else {
                // 命令耗时超过 waitLimit，仍在后台执行中
                record.stage = "process_started";
            }
            record.updatedAt = System.currentTimeMillis();
            persist(context);

            JSONObject out = record.toJson();
            out.put("ok", !"failed".equals(record.stage));
            out.put("log_tail", record.lastOutput);
            return out;
        }
    }

    static JSONObject status(Context context, String taskId) throws Exception {
        ensureLoaded(context);
        if (taskId == null || taskId.trim().isEmpty()) {
            JSONArray all = new JSONArray();
            List<TaskRecord> list = new ArrayList<>(TASKS.values());
            Collections.sort(list, (a, b) -> Long.compare(b.createdAt, a.createdAt));
            for (int i = 0; i < Math.min(20, list.size()); i++) {
                TaskRecord r = list.get(i);
                updateProbe(r);
                all.put(r.toJson());
            }
            persist(context);
            return new JSONObject().put("ok", true).put("tasks", all);
        }
        String cleanId = taskId.trim();
        if (!isValidTaskId(cleanId)) throw new IllegalArgumentException("无效的任务 ID");
        TaskRecord r = TASKS.get(cleanId);
        if (r == null) throw new IllegalArgumentException("未找到任务: " + taskId);
        if (isExecutionEnabled(context)) {
            refreshLiveStatus(context, r);
        } else {
            updateProbe(r);
        }
        persist(context);
        JSONObject out = r.toJson();
        out.put("ok", true);
        return out;
    }

    static JSONObject logs(Context context, String taskId, int tailLines) throws Exception {
        ensureLoaded(context);
        ensureTermuxReady(context);
        String cleanId = taskId == null ? "" : taskId.trim();
        if (!isValidTaskId(cleanId)) throw new IllegalArgumentException("无效的任务 ID");
        TaskRecord r = TASKS.get(cleanId);
        if (r == null) throw new IllegalArgumentException("未找到任务: " + taskId);
        int lines = Math.max(10, Math.min(300, tailLines <= 0 ? 80 : tailLines));
        String tail = fetchLogTail(context, r, lines);
        if (!tail.isEmpty()) r.lastOutput = tail;
        refreshLiveStatus(context, r);
        persist(context);
        JSONObject out = r.toJson();
        out.put("ok", true);
        out.put("log_tail", tail.isEmpty() ? r.lastOutput : tail);
        return out;
    }

    static JSONObject stop(Context context, String taskId, int waitMs) throws Exception {
        ensureLoaded(context);
        ensureTermuxReady(context);
        String cleanId = taskId == null ? "" : taskId.trim();
        if (!isValidTaskId(cleanId)) throw new IllegalArgumentException("无效的任务 ID");
        TaskRecord r = TASKS.get(cleanId);
        if (r == null) throw new IllegalArgumentException("未找到任务: " + taskId);

        String stopScript = "TDIR=\"" + TASK_DIR_EXPR + "\"; "
                + "PGID=$(cat \"$TDIR/" + r.taskId + ".pgid\" 2>/dev/null || echo \"" + r.pgid + "\"); "
                + "if [ -n \"$PGID\" ] && [ \"$PGID\" -gt 1 ] 2>/dev/null; then "
                + "  kill -TERM -\"$PGID\" 2>/dev/null || kill -TERM \"$PGID\" 2>/dev/null || true; "
                + "  sleep 1; "
                + "  kill -KILL -\"$PGID\" 2>/dev/null || kill -KILL \"$PGID\" 2>/dev/null || true; "
                + "  echo \"STOPPED_PGID=$PGID\"; "
                + "else echo \"NO_PGID\"; exit 1; fi";

        CallbackResult cb = execSync(context, stopScript, r.workdir, null, 4000);
        if (cb != null && cb.err > 0) {
            throw new IllegalStateException("Termux 停止任务失败：" + cb.errmsg);
        }

        long deadline = System.currentTimeMillis() + Math.max(1000, Math.min(8000, waitMs));
        while (System.currentTimeMillis() < deadline) {
            updateProbe(r);
            if (!hasProbe(r) || !r.httpReachable) break;
            Thread.sleep(300);
        }
        r.processAlive = false;
        r.stage = "stopped";
        r.updatedAt = System.currentTimeMillis();
        persist(context);

        JSONObject out = r.toJson();
        out.put("ok", !hasProbe(r) || !r.httpReachable);
        if (hasProbe(r) && r.httpReachable) {
            out.put("warning", "已向进程组发送终止信号，但目标端口仍可访问，请检查是否有其他独立进程监听该端口");
        }
        return out;
    }

    static void onSyncCallback(String reqId, String stdout, String stderr, int exitCode, int err, String errmsg) {
        SyncWaiter w = SYNC_WAITERS.remove(reqId);
        if (w != null) {
            w.result = new CallbackResult(stdout, stderr, exitCode, err, errmsg);
            w.latch.countDown();
        }
    }

    static void onTaskCallback(Context context, String taskId, String stdout, String stderr, int exitCode, int err, String errmsg) {
        ensureLoaded(context);
        TaskRecord r = TASKS.get(taskId);
        if (r == null) return;
        r.updatedAt = System.currentTimeMillis();
        if ("service".equals(r.mode)) {
            if (err > 0) {
                r.termuxErr = err;
                r.errorMessage = errmsg == null ? "" : errmsg;
                r.stage = "failed";
            } else {
                parseStartedPgid(r, stdout);
                if (r.pgid > 0 && "submitted".equals(r.stage)) {
                    r.processAlive = true;
                    r.stage = "process_started";
                }
            }
        } else {
            r.exitCode = exitCode;
            r.termuxErr = err;
            r.errorMessage = errmsg == null ? "" : errmsg;
            if (stdout != null && !stdout.isEmpty()) r.lastOutput = stdout;
            else if (stderr != null && !stderr.isEmpty()) r.lastOutput = stderr;
            if (err > 0 || exitCode != 0) r.stage = "failed";
            else r.stage = "completed";
        }
        persist(context);
    }

    private static void refreshLiveStatus(Context context, TaskRecord r) {
        updateProbe(r);
        if ("service".equals(r.mode) || "process_started".equals(r.stage)) {
            try {
                String checkScript = "TDIR=\"" + TASK_DIR_EXPR + "\"; "
                        + "PGID=$(cat \"$TDIR/" + r.taskId + ".pgid\" 2>/dev/null || echo \"" + r.pgid + "\"); "
                        + "EC=$(cat \"$TDIR/" + r.taskId + ".exit\" 2>/dev/null || echo \"\"); "
                        + "ALIVE=0; "
                        + "if [ -n \"$PGID\" ] && [ \"$PGID\" -gt 1 ] 2>/dev/null; then "
                        + "  (kill -0 -\"$PGID\" 2>/dev/null || kill -0 \"$PGID\" 2>/dev/null) && ALIVE=1; "
                        + "fi; "
                        + "echo \"PGID=$PGID ALIVE=$ALIVE EC=$EC\"";
                CallbackResult cb = execSync(context, checkScript, r.workdir, null, 2500);
                if (cb != null && cb.err <= 0) {
                    Matcher pm = Pattern.compile("PGID=(-?\\d+)").matcher(cb.stdout);
                    if (pm.find()) {
                        int p = Integer.parseInt(pm.group(1));
                        if (p > 0) r.pgid = p;
                    }
                    boolean alive = cb.stdout.contains("ALIVE=1");
                    r.processAlive = alive;
                    Matcher em = Pattern.compile("EC=(-?\\d+)").matcher(cb.stdout);
                    if (em.find()) r.exitCode = Integer.parseInt(em.group(1));
                    if (alive) {
                        r.stage = r.httpReachable ? "service_ready" : "process_started";
                    } else if (!"stopped".equals(r.stage) && !"failed".equals(r.stage) && !"completed".equals(r.stage)) {
                        r.stage = (r.exitCode == 0) ? "completed" : "stopped";
                    }
                }
            } catch (Exception ignored) { }
        }
        r.updatedAt = System.currentTimeMillis();
    }

    private static String fetchLogTail(Context context, TaskRecord r, int lines) {
        try {
            String cmd = "tail -n " + lines + " \"" + TASK_DIR_EXPR + "/" + r.taskId + ".log\" 2>/dev/null | tail -c 24576";
            CallbackResult cb = execSync(context, cmd, r.workdir, null, 3000);
            if (cb != null && cb.err <= 0) return cb.stdout;
        } catch (Exception ignored) { }
        return "";
    }

    private static CallbackResult execSync(Context context, String bashCmd, String workdir, String taskId, int timeoutMs) throws Exception {
        if (!isExecutionEnabled(context)) {
            throw new SecurityException("后台 Termux 命令功能默认关闭；如需使用，请在手机端「设置与权限管理」中手动开启");
        }
        String reqId = "req_" + REQ_SEQ.incrementAndGet() + "_" + randomHex(16);
        SyncWaiter waiter = new SyncWaiter();
        SYNC_WAITERS.put(reqId, waiter);
        try {
            Intent intent = new Intent(ACTION_RUN_COMMAND);
            intent.setClassName(TERMUX_PKG, TERMUX_SERVICE);
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] { "-lc", bashCmd });
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir);
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");

            Intent cbIntent = new Intent(context, TermuxResultReceiver.class).putExtra("req_id", reqId);
            if (taskId != null && !taskId.isEmpty()) cbIntent.putExtra("task_id", taskId);
            PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    reqId.hashCode(),
                    cbIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE
            );
            intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);
            context.startService(intent);

            waiter.latch.await(Math.max(300, timeoutMs), TimeUnit.MILLISECONDS);
            return waiter.result;
        } finally {
            SYNC_WAITERS.remove(reqId);
        }
    }

    private static String buildServiceLaunchScript(TaskRecord r) {
        if (!isValidTaskId(r.taskId)) throw new IllegalArgumentException("无效的任务 ID");
        String inner = r.command + "; echo $? > \"" + TASK_DIR_EXPR + "/" + r.taskId + ".exit\"";
        return "TDIR=\"" + TASK_DIR_EXPR + "\"; mkdir -p \"$TDIR\"; "
                + "LOG=\"$TDIR/" + r.taskId + ".log\"; "
                + "PGID_FILE=\"$TDIR/" + r.taskId + ".pgid\"; "
                + "rm -f \"$TDIR/" + r.taskId + ".exit\"; "
                + "setsid /data/data/com.termux/files/usr/bin/bash -lc '" + escapeSingle(inner) + "' >> \"$LOG\" 2>&1 < /dev/null & "
                + "SPID=$!; echo \"$SPID\" > \"$PGID_FILE\"; echo \"STARTED_PGID=$SPID\"";
    }

    private static String buildOneshotScript(TaskRecord r) {
        if (!isValidTaskId(r.taskId)) throw new IllegalArgumentException("无效的任务 ID");
        return "TDIR=\"" + TASK_DIR_EXPR + "\"; mkdir -p \"$TDIR\"; "
                + "LOG=\"$TDIR/" + r.taskId + ".log\"; "
                + "PGID_FILE=\"$TDIR/" + r.taskId + ".pgid\"; "
                + "echo $$ > \"$PGID_FILE\"; "
                + "/data/data/com.termux/files/usr/bin/bash -lc '" + escapeSingle(r.command) + "' > \"$LOG\" 2>&1; EC=$?; "
                + "echo \"$EC\" > \"$TDIR/" + r.taskId + ".exit\"; "
                + "tail -c 16384 \"$LOG\" 2>/dev/null; exit $EC";
    }

    private static void parseStartedPgid(TaskRecord r, String stdout) {
        if (stdout == null) return;
        Matcher m = Pattern.compile("STARTED_PGID=(\\d+)").matcher(stdout);
        if (m.find()) {
            r.pgid = Integer.parseInt(m.group(1));
        }
    }

    private static void ensureTermuxReady(Context context) {
        if (!isExecutionEnabled(context)) {
            throw new SecurityException("后台 Termux 命令功能默认关闭；如需使用，请在手机端「设置与权限管理」中手动开启");
        }
        JSONObject perm = getPermissionStatus(context);
        if (!perm.optBoolean("termux_installed", false)) {
            throw new IllegalStateException("手机未安装 Termux");
        }
        if (!perm.optBoolean("run_command_permission", false)) {
            throw new SecurityException("缺少 com.termux.permission.RUN_COMMAND 权限，请在系统权限或 ADB 中授予");
        }
    }

    private static boolean hasProbe(TaskRecord r) {
        return (r.probeUrl != null && !r.probeUrl.isEmpty()) || r.probePort > 0;
    }

    private static void updateProbe(TaskRecord r) {
        if (!hasProbe(r)) return;
        HttpProbeResult p = probeLocal(r.probeUrl, r.probePort);
        r.httpReachable = p.reachable;
        r.httpStatus = p.statusCode;
    }

    private static boolean isValidTaskId(String id) {
        return id != null && VALID_TASK_ID.matcher(id).matches();
    }

    private static String randomHex(int byteLen) {
        byte[] bytes = new byte[byteLen];
        RNG.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(byteLen * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void validateLoopbackPort(int port) {
        if (port < 1 || port > 65535 || port == BRIDGE_LOCAL_PORT) {
            throw new IllegalArgumentException("probe_port 必须是 1-65535 的本机端口，且禁止探测内部桥接端口 " + BRIDGE_LOCAL_PORT);
        }
    }

    private static String validateLoopbackProbeUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("probe_url 仅支持本机 http:// 地址");
            }
            if (uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("probe_url 不允许包含用户信息");
            }
            String host = uri.getHost();
            if (host == null || (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host))) {
                throw new IllegalArgumentException("安全限制：probe_url 仅允许探测本机回环地址 (127.0.0.1 / localhost)");
            }
            int port = uri.getPort();
            if (port != -1) {
                validateLoopbackPort(port);
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("probe_url 格式无效");
        }
    }

    private static String escapeSingle(String s) {
        return s.replace("'", "'\\''");
    }

    static final class HttpProbeResult {
        final boolean reachable;
        final int statusCode;
        HttpProbeResult(boolean reachable, int statusCode) { this.reachable = reachable; this.statusCode = statusCode; }
    }

    static HttpProbeResult probeLocal(String probeUrl, int probePort) {
        String urlStr = "";
        if (probeUrl != null && !probeUrl.trim().isEmpty()) {
            try {
                urlStr = validateLoopbackProbeUrl(probeUrl);
            } catch (IllegalArgumentException ignored) {
                return new HttpProbeResult(false, 0);
            }
        } else if (probePort > 0) {
            if (probePort > 65535 || probePort == BRIDGE_LOCAL_PORT) {
                return new HttpProbeResult(false, 0);
            }
            urlStr = "http://127.0.0.1:" + probePort + "/";
        }
        if (!urlStr.isEmpty()) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(800);
                conn.setReadTimeout(800);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                return new HttpProbeResult(code > 0, code);
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        if (probePort >= 1 && probePort <= 65535 && probePort != BRIDGE_LOCAL_PORT) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", probePort), 600);
                return new HttpProbeResult(true, 0);
            } catch (Exception ignored) { }
        }
        return new HttpProbeResult(false, 0);
    }
}
