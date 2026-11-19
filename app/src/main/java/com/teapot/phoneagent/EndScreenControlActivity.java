package com.teapot.phoneagent;

import android.app.Activity;
import android.os.Bundle;

public final class EndScreenControlActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScreenApprovalActivity.end(this, "你已结束临时屏幕控制");
        finish();
    }
}
