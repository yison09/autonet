package com.autonetswitch.app.net;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 网络状态采集：轮询 + 网络回调双通道。
 * 状态查询全部走公开 API，无需特权。
 */
public class NetworkMonitor {

    public interface Listener {
        void onStateChanged(NetState s);
    }

    private static final NetworkMonitor INSTANCE = new NetworkMonitor();

    public static NetworkMonitor get() {
        return INSTANCE;
    }

    private Context appCtx;
    private ConnectivityManager cm;
    private WifiManager wm;
    private TelephonyManager tm;
    private Handler handler;
    private final NetState state = new NetState();
    private NetState lastNotified;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean started;
    private Runnable pendingRefresh;
    private ConnectivityManager.NetworkCallback netCallback;
    private android.content.BroadcastReceiver wifiReceiver;

    public void start(Context c) {
        if (started) {
            return;
        }
        appCtx = c.getApplicationContext();
        cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
        wm = (WifiManager) appCtx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        tm = (TelephonyManager) appCtx.getSystemService(Context.TELEPHONY_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        started = true;

        try {
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    scheduleRefresh();
                }

                @Override
                public void onLost(Network n) {
                    scheduleRefresh();
                }

                @Override
                public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                    scheduleRefresh();
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), netCallback);
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= 24) {
            try {
                cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network n) {
                        scheduleRefresh();
                    }

                    @Override
                    public void onLost(Network n) {
                        scheduleRefresh();
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        try {
            wifiReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    scheduleRefresh();
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            f.addAction("android.intent.action.AIRPLANE_MODE");
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(wifiReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appCtx.registerReceiver(wifiReceiver, f);
            }
        } catch (Throwable ignored) {
        }

        runRefresh();
    }

    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (cm != null && netCallback != null) {
            try {
                cm.unregisterNetworkCallback(netCallback);
            } catch (Throwable ignored) {
            }
        }
        if (wifiReceiver != null) {
            try {
                appCtx.unregisterReceiver(wifiReceiver);
            } catch (Throwable ignored) {
            }
        }
        if (handler != null && pendingRefresh != null) {
            handler.removeCallbacks(pendingRefresh);
        }
    }

    private void scheduleRefresh() {
        if (handler == null) {
            return;
        }
        if (pendingRefresh == null) {
            pendingRefresh = this::runRefresh;
        }
        handler.removeCallbacks(pendingRefresh);
        handler.postDelayed(pendingRefresh, 400);
    }

    public void refresh() {
        if (handler != null && Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::runRefresh);
        } else {
            runRefresh();
        }
    }

    public NetState snapshot() {
        return state.copy();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void runRefresh() {
        if (cm == null) {
            return;
        }
        NetState s = state;

        try {
            s.airplane = Settings.Global.getInt(appCtx.getContentResolver(), "airplane_mode_on", 0) == 1;
        } catch (Throwable ignored) {
        }

        try {
            s.wifiEnabled = wm.isWifiEnabled();
        } catch (Throwable ignored) {
        }

        s.wifiConnected = false;
        s.wifiValidated = false;
        s.cellConnected = false;
        s.cellValidated = false;

        try {
            Network[] nets = cm.getAllNetworks();
            if (nets != null) {
                for (Network n : nets) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    if (c == null) {
                        continue;
                    }
                    boolean validated = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        s.wifiConnected = true;
                        if (validated) {
                            s.wifiValidated = true;
                        }
                    } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        s.cellConnected = true;
                        if (validated) {
                            s.cellValidated = true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 默认网络
        s.defaultTransport = -1;
        try {
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities c = cm.getNetworkCapabilities(active);
                if (c != null) {
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        s.defaultTransport = NetworkCapabilities.TRANSPORT_WIFI;
                    } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        s.defaultTransport = NetworkCapabilities.TRANSPORT_CELLULAR;
                    } else {
                        s.defaultTransport = -2;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // SSID（需定位权限，否则返回空）
        s.ssid = "";
        s.wifiRssi = -127;
        if (s.wifiConnected) {
            try {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    s.wifiRssi = info.getRssi();
                    String ssid = info.getSSID();
                    if (ssid != null) {
                        ssid = ssid.replace("\"", "").trim();
                        if (!ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                            s.ssid = ssid;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 数据开关
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                s.dataEnabled = tm.isDataEnabled();
            } else {
                throw new UnsupportedOperationException("pre-26");
            }
        } catch (Throwable t) {
            try {
                s.dataEnabled = Settings.Global.getInt(appCtx.getContentResolver(), "mobile_data", 1) == 1;
            } catch (Throwable ignored) {
            }
        }

        notifyIfChanged();
    }

    private void notifyIfChanged() {
        if (state.sameAs(lastNotified)) {
            return;
        }
        lastNotified = state.copy();
        NetState snapshot = state.copy();
        for (Listener l : listeners) {
            try {
                l.onStateChanged(snapshot);
            } catch (Exception ignored) {
            }
        }
    }
}
