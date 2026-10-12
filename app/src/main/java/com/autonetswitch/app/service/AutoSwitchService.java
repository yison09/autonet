package com.autonetswitch.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.autonetswitch.app.App;
import com.autonetswitch.app.R;
import com.autonetswitch.app.net.NetState;
import com.autonetswitch.app.net.NetworkMonitor;
import com.autonetswitch.app.rules.RuleEngine;
import com.autonetswitch.app.ui.MainActivity;
import com.autonetswitch.app.util.AppLog;

public class AutoSwitchService extends Service {

    private static final String CHANNEL_ID = "autonet_bg";
    private static final int NOTIF_ID = 101;
    private static volatile boolean running;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context c) {
        if (running) {
            return;
        }
        Intent i = new Intent(c, AutoSwitchService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            AppLog.append("启动前台服务失败: " + t);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, AutoSwitchService.class));
    }

    private final NetworkMonitor.Listener stateListener = this::onStateChanged;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        Notification n = buildNotification(getString(R.string.notif_starting));
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, NOTIF_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable t2) {
                AppLog.append("startForeground 失败: " + t2);
            }
        }

        NetworkMonitor.get().addListener(stateListener);
        RuleEngine.get().start();
        onStateChanged(NetworkMonitor.get().snapshot());
        AppLog.append("自动切换服务已启动");
    }

    private void onStateChanged(NetState s) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(summarize(s)));
            }
        } catch (Throwable ignored) {
        }
    }

    private String summarize(NetState s) {
        int n = App.get().rules().enabledCount();
        return getString(R.string.notif_summary,
                s.wifiBrief(), s.cellBrief(), n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        NetworkMonitor.get().removeListener(stateListener);
        RuleEngine.get().stop();
        AppLog.append("自动切换服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_net)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }
}
