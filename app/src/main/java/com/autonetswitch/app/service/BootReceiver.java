package com.autonetswitch.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.autonetswitch.app.App;
import com.autonetswitch.app.util.AppLog;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            if (!App.prefBool("autostart", true)) {
                return;
            }
            if (!App.get().rules().anyEnabled()) {
                return;
            }
            AppLog.append("开机/更新后自启动");
            AutoSwitchService.start(context);
        } catch (Throwable t) {
            AppLog.append("自启动失败: " + t);
        }
    }
}
