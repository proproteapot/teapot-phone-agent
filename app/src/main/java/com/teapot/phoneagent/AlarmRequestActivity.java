package com.teapot.phoneagent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** A notification click is a user-visible launch, not proof that an alarm was saved. */
public final class AlarmRequestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent request = new SystemTools(this).alarmIntent(getIntent().getIntExtra("hour", -1),
                    getIntent().getIntExtra("minute", -1), getIntent().getStringExtra("label"));
            if (request.resolveActivity(getPackageManager()) == null) throw new IllegalStateException("没有可处理闹钟的应用");
            startActivity(request);
        } catch (Exception e) { Toast.makeText(this, "无法打开系统闹钟", Toast.LENGTH_LONG).show(); }
        finish();
    }
}
