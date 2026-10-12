package com.autonetswitch.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.autonetswitch.app.net.NetworkMonitor;
import com.autonetswitch.app.shizuku.ShizukuBridge;
import com.autonetswitch.app.store.RuleStore;
import com.autonetswitch.app.util.AppLog;

public class App extends Application {

    private static App instance;
    private RuleStore ruleStore;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ruleStore = new RuleStore(this);
        ShizukuBridge.get().init(this);
        NetworkMonitor.get().start(this);
        AppLog.append("应用启动");
    }

    public static App get() {
        return instance;
    }

    public RuleStore rules() {
        return ruleStore;
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("autonet", Context.MODE_PRIVATE);
    }

    public static boolean prefBool(String key, boolean def) {
        return prefs(instance).getBoolean(key, def);
    }

    public static void setPrefBool(String key, boolean v) {
        prefs(instance).edit().putBoolean(key, v).apply();
    }
}
