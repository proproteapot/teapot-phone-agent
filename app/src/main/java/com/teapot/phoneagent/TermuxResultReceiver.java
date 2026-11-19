package com.teapot.phoneagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * 接收 Termux RUN_COMMAND 后台执行结果的 BroadcastReceiver。
 * 使用 BroadcastReceiver 替代普通 Service，避免 Android 8+ 后台启动服务限制。
 */
public final class TermuxResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String reqId = intent.getStringExtra("req_id");
        String taskId = intent.getStringExtra("task_id");
        Bundle resultBundle = intent.getBundleExtra("result");
        if (resultBundle == null) return;

        String stdout = resultBundle.getString("stdout", "");
        String stderr = resultBundle.getString("stderr", "");
        int exitCode = resultBundle.getInt("exitCode", -1);
        int err = resultBundle.getInt("err", 0);
        String errmsg = resultBundle.getString("errmsg", "");

        if (reqId != null && !reqId.isEmpty()) {
            TermuxCommandManager.onSyncCallback(reqId, stdout, stderr, exitCode, err, errmsg);
        }
        if (taskId != null && !taskId.isEmpty()) {
            TermuxCommandManager.onTaskCallback(context, taskId, stdout, stderr, exitCode, err, errmsg);
        }
    }
}
