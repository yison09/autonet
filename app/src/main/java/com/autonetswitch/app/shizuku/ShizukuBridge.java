package com.autonetswitch.app.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * 应用侧 Shizuku 桥：权限检测、服务绑定、串行执行特权操作。
 * 所有操作在单线程 IO 队列中执行，避免并发 Binder 调用。
 */
public class ShizukuBridge {

    public interface Op {
        String run(IUserService svc) throws Exception;
    }

    public interface ResultCallback {
        void onResult(String result);
    }

    public interface StateListener {
        void onShizukuStateChanged();
    }

    private static final ShizukuBridge INSTANCE = new ShizukuBridge();

    public static ShizukuBridge get() {
        return INSTANCE;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();

    private Context appCtx;
    private Shizuku.UserServiceArgs args;
    private ServiceConnection conn;
    private volatile IUserService service;
    private boolean binding;

    private ShizukuBridge() {
    }

    public void init(Context c) {
        if (appCtx != null) {
            return;
        }
        appCtx = c.getApplicationContext();
        try {
            Shizuku.addBinderReceivedListenerSticky((Shizuku.OnBinderReceivedListener) this::notifyState);
            Shizuku.addBinderDeadListener((Shizuku.OnBinderDeadListener) () -> {
                service = null;
                notifyState();
                // binder 死亡的轻量恢复：8 秒后尝试重新拉取
                io.execute(() -> {
                    safeSleep(8000);
                    if (service == null && !Shizuku.pingBinder()) {
                        fetchBinder(new Bundle());
                    }
                });
            });
            Shizuku.addRequestPermissionResultListener(
                    (Shizuku.OnRequestPermissionResultListener) (requestCode, grantResult) -> notifyState());
        } catch (Throwable ignored) {
        }
        // 主动从 Manager 拉取 binder（库无自动 provider 时必需）
        io.execute(() -> fetchBinder(new Bundle()));
    }

    private static void safeSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 主动从 Shizuku Manager 的 Provider 拉取系统 binder。
     * 对应库端 handleGetBinder 的逆动作。
     */
    private volatile boolean fetching;

    private boolean fetchBinder(Bundle extra) {
        if (fetching) {
            return false;
        }
        fetching = true;
        try {
            if (Shizuku.pingBinder()) {
                return true;
            }
            final String auth = "moe.shizuku.privileged.api.shizuku";
            IBinder found = null;
            String failLog = "";
            try {
                Bundle reply = appCtx.getContentResolver().call(
                        Uri.parse("content://" + auth),
                        "getBinder", null, extra);
                if (reply == null) {
                    failLog += auth + "=null;";
                } else {
                    // 放开 BinderContainer 的 classloader，保证跨进程 parcel 正常
                    try {
                        Class<?> bc = Class.forName("moe.shizuku.api.BinderContainer");
                        reply.setClassLoader(bc.getClassLoader());
                    } catch (Throwable ignored) {
                    }
                    Object parcelObj = reply.getParcelable(
                            "moe.shizuku.privileged.api.intent.extra.BINDER");
                    if (parcelObj == null) {
                        failLog += auth + "=nobinder;";
                    } else {
                        try {
                            java.lang.reflect.Field f = parcelObj.getClass().getField("binder");
                            Object b = f.get(parcelObj);
                            if (b instanceof IBinder) {
                                found = (IBinder) b;
                            } else {
                                failLog += auth + "=notibinder;";
                            }
                        } catch (Throwable t) {
                            failLog += auth + "=reflect:" + t.getMessage() + ";";
                        }
                    }
                }
            } catch (Throwable t) {
                failLog += auth + "=" + t.getClass().getSimpleName() + ";";
            }
            if (found != null) {
                try {
                    // 第二参数 = 客户端自己的包名（服务端用它校验 uid 归属，传错会导致 attach 被拒）
                    Shizuku.onBinderReceived(found, appCtx.getPackageName());
                    com.autonetswitch.app.util.AppLog.append("Shizuku binder 已获取");
                    notifyState();
                    return true;
                } catch (Throwable t) {
                    com.autonetswitch.app.util.AppLog.append("Shizuku binder 注入失败: " + t);
                }
            } else {
                com.autonetswitch.app.util.AppLog.append("Shizuku binder 获取失败: " + failLog);
            }
            return false;
        } finally {
            fetching = false;
        }
    }

    public boolean daemonAlive() {
        try {
            if (appCtx == null) {
                return false;
            }
            if (Shizuku.pingBinder()) {
                return true;
            }
            // binder 可能尚未拉取：尝试一次同步拉取再判定
            return fetchBinder(new Bundle());
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean granted() {
        try {
            if (appCtx == null) {
                return false;
            }
            // M3：本方法会被规则引擎的评估路径（主线程）高频调用，
            // 因此这里只做本地 pingBinder()，绝不在调用线程上做跨进程 ContentResolver.call。
            // binder 未附着时改为投递到 IO 队列异步拉取，并按 30 秒节流，避免每 tick 都打 IPC + 写失败日志。
            if (!Shizuku.pingBinder()) {
                requestBinderAsync();
                return false;
            }
            return Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private volatile long lastFetchAttemptMs = 0;

    private void requestBinderAsync() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastFetchAttemptMs < 30_000) {
            return;
        }
        lastFetchAttemptMs = now;
        io.execute(() -> fetchBinder(new Bundle()));
    }

    public void addListener(StateListener l) {
        listeners.add(l);
    }

    public void removeListener(StateListener l) {
        listeners.remove(l);
    }

    private void notifyState() {
        for (StateListener l : listeners) {
            try {
                l.onShizukuStateChanged();
            } catch (Exception ignored) {
            }
        }
    }

    public void execute(Op op, ResultCallback cb) {
        io.execute(() -> {
            String r = doExecute(op, true);
            if (cb != null) {
                try {
                    cb.onResult(r);
                } catch (Exception ignored) {
                }
            }
        });
    }

    public void setWifi(boolean on, ResultCallback cb) {
        execute(svc -> svc.setWifiEnabled(on), cb);
    }

    public void setData(boolean on, ResultCallback cb) {
        execute(svc -> svc.setDataEnabled(on), cb);
    }

    public void setUserData(int subId, boolean on, ResultCallback cb) {
        execute(svc -> svc.setUserDataEnabled(subId, on), cb);
    }

    public void setAirplane(boolean on, ResultCallback cb) {
        execute(svc -> svc.setAirplaneMode(on), cb);
    }

    public void setDefaultDataSubId(int subId, ResultCallback cb) {
        execute(svc -> svc.setDefaultDataSubId(subId), cb);
    }

    /**
     * WiFi 重选：关闭后再开启，让系统在已保存网络中重新打分选优。
     * 单条 shell 命令在派生进程内串行执行，避免客户端时序问题。
     */
    public void reselectWifi(ResultCallback cb) {
        execute(svc -> {
            String out = svc.exec("svc wifi disable && sleep 2 && svc wifi enable");
            return "OK" + (out == null || out.isEmpty() ? "" : ": " + out);
        }, cb);
    }

    private String doExecute(Op op, boolean retry) {
        if (!granted()) {
            return "ERR: Shizuku 未连接或未授权";
        }
        IUserService svc = ensureService();
        if (svc == null) {
            return "ERR: 无法连接 Shizuku 特权服务";
        }
        try {
            return op.run(svc);
        } catch (Throwable t) {
            if (retry) {
                service = null;
                svc = ensureService();
                if (svc != null) {
                    try {
                        return op.run(svc);
                    } catch (Throwable t2) {
                        return "ERR: " + t2;
                    }
                }
            }
            return "ERR: " + t;
        }
    }

    private IUserService ensureService() {
        IUserService svc = service;
        if (svc != null && svc.asBinder().isBinderAlive()) {
            return svc;
        }
        synchronized (lock) {
            if (service != null && service.asBinder().isBinderAlive()) {
                return service;
            }
            if (!Shizuku.pingBinder()) {
                return null;
            }
            if (!binding) {
                binding = true;
                if (conn == null) {
                    conn = createConn();
                }
                if (args == null) {
                    // version 变更会让 Shizuku 重新拉起 user_service 进程：
                    // 应用升级后旧进程仍跑着上一版 dex（实测：升级后飞行模式回读仍是旧文案），
                    // 因此每次发布涉及 NetUserService 的改动都要 +1。
                    // v1.0.7: 1 -> 2（反射签名重写 / 默认数据卡 / 看门狗 / 独立回读）
                    args = new Shizuku.UserServiceArgs(
                            new ComponentName(appCtx, NetUserService.class))
                            .daemon(true)
                            .processNameSuffix("user_service")
                            .version(2);
                }
                try {
                    Shizuku.bindUserService(args, conn);
                } catch (Throwable t) {
                    binding = false;
                    return null;
                }
            }
            long deadline = System.currentTimeMillis() + 12_000;
            while (service == null) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    break;
                }
                try {
                    lock.wait(remain);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (service == null) {
                // L4：绑定超时（冷启动时 user_service 进程首次拉起经常 >6s）→ 必须复位 binding，
                // 否则标志永远为 true，后续调用不会再执行 bindUserService，只能一直等到超时。
                binding = false;
            }
            return service;
        }
    }

    private ServiceConnection createConn() {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service = IUserService.Stub.asInterface(binder);
                synchronized (lock) {
                    binding = false;
                    lock.notifyAll();
                }
                notifyState();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                service = null;
                synchronized (lock) {
                    binding = false;
                    lock.notifyAll();
                }
                notifyState();
            }
        };
    }
}
