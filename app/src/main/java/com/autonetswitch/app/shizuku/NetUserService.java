package com.autonetswitch.app.shizuku;

import android.os.IBinder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * 运行在 Shizuku 派生进程（shell / root 身份）中的特权服务实现。
 * 所有操作优先走系统命令（跨厂商最稳），必要时回退隐藏 API 反射。
 */
public class NetUserService extends IUserService.Stub {

    private static final int MAX_OUTPUT = 16 * 1024;

    public NetUserService() {
    }

    public NetUserService(int uid, int pid, String[] args) {
    }

    @Override
    public int ping() {
        return android.os.Process.myUid();
    }

    private String run(String cmd) {
        Process p = null;
        Thread watchdog = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            // 看门狗：15 秒未退出则 destroy，同时解除 readLine 阻塞与 waitFor 死锁
            final Process fp = p;
            watchdog = new Thread(() -> {
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException ie) {
                    return;
                }
                fp.destroy();
            });
            watchdog.setDaemon(true);
            watchdog.start();

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), Charset.defaultCharset()));
            StringBuilder sb = new StringBuilder();
            String line;
            int total = 0;
            boolean truncated = false;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                total += line.length() + 1;
                if (total >= MAX_OUTPUT) {
                    truncated = true;
                    break;
                }
            }
            if (truncated) {
                // 输出超限：立刻终止子进程，防止其继续写满管道阻塞
                p.destroy();
            }
            p.waitFor();
            String out = sb.toString().trim();
            return truncated ? out + "\n[输出超长，已截断]" : out;
        } catch (Throwable t) {
            return "EX: " + t;
        } finally {
            if (watchdog != null) {
                watchdog.interrupt();
            }
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static boolean failed(String out) {
        if (out == null) {
            return true;
        }
        String l = out.toLowerCase();
        return l.contains("not found")
                || l.contains("exception")
                || l.contains("error")
                || l.contains("denial")
                || l.contains("usage:")
                || l.contains("failed")
                || l.contains("unknown")
                || l.contains("ex:")
                || l.contains("no such");
    }

    @Override
    public String exec(String cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return "ERR: empty command";
        }
        return run(cmd);
    }

    @Override
    public String setWifiEnabled(boolean enable) {
        String mode = enable ? "enable" : "disable";
        String out = run("svc wifi " + mode);
        if (!failed(out)) {
            return "OK: svc wifi " + mode + (out.isEmpty() ? "" : " (" + out + ")");
        }
        String mode2 = enable ? "enabled" : "disabled";
        String out2 = run("cmd wifi set-wifi-enabled " + mode2);
        if (!failed(out2)) {
            return "OK: cmd wifi set-wifi-enabled " + mode2;
        }
        return "FAIL: svc=" + out + " | cmd=" + out2;
    }

    @Override
    public String setDataEnabled(boolean enable) {
        String mode = enable ? "enable" : "disable";
        String out = run("svc data " + mode);
        if (!failed(out)) {
            return "OK: svc data " + mode;
        }
        String out2 = run("settings put global mobile_data " + (enable ? "1" : "0"));
        if (!failed(out2)) {
            return "OK: settings mobile_data=" + (enable ? 1 : 0) + " (请以界面状态为准)";
        }
        return "FAIL: svc=" + out + " | settings=" + out2;
    }

    @Override
    public String setUserDataEnabled(int subId, boolean enable) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "phone");
            if (b == null) {
                return "FAIL: telephony service not found";
            }
            Class<?> itel = Class.forName("com.android.internal.telephony.ITelephony");
            Object stub = itel.getMethod("asInterface", IBinder.class).invoke(null, b);

            // AOSP 实际签名（新 → 旧，已逐分支核对 ITelephony.aidl）：
            //   13/14/15: setDataEnabledForReason(int subId, int reason, boolean enable, String callingPackage)
            //   12/12.1: setDataEnabledForReason(int subId, int reason, boolean enable)
            //   9/10/11: setUserDataEnabled(int subId, boolean enable)
            //   8.1:     setDataEnabled(int subId, boolean enable)
            // 注意参数顺序是 (int, int, boolean)，不是 (int, boolean, int)；
            // setDataEnabled(boolean) 在任何 AOSP 版本都不存在，不再尝试。
            NoSuchMethodException lastMiss = null;
            try {
                itel.getMethod("setDataEnabledForReason",
                                int.class, int.class, boolean.class, String.class)
                        .invoke(stub, subId, 0 /* REASON_USER */, enable, "com.android.shell");
                return "OK: setDataEnabledForReason(4) sub=" + subId + "=" + enable;
            } catch (NoSuchMethodException e) {
                lastMiss = e;
            } catch (Throwable t) {
                // 方法存在但调用失败：老签名必然也不存在/同样失败，直接返回带原因的结果
                return "FAIL: setDataEnabledForReason(4): " + t;
            }
            try {
                itel.getMethod("setDataEnabledForReason", int.class, int.class, boolean.class)
                        .invoke(stub, subId, 0 /* REASON_USER */, enable);
                return "OK: setDataEnabledForReason(3) sub=" + subId + "=" + enable;
            } catch (NoSuchMethodException e) {
                lastMiss = e;
            } catch (Throwable t) {
                return "FAIL: setDataEnabledForReason(3): " + t;
            }
            try {
                itel.getMethod("setUserDataEnabled", int.class, boolean.class)
                        .invoke(stub, subId, enable);
                return "OK: setUserDataEnabled sub=" + subId + "=" + enable;
            } catch (NoSuchMethodException e) {
                lastMiss = e;
            } catch (Throwable t) {
                return "FAIL: setUserDataEnabled: " + t;
            }
            try {
                itel.getMethod("setDataEnabled", int.class, boolean.class)
                        .invoke(stub, subId, enable);
                return "OK: setDataEnabled sub=" + subId + "=" + enable;
            } catch (NoSuchMethodException e) {
                lastMiss = e;
            } catch (Throwable t) {
                return "FAIL: setDataEnabled: " + t;
            }
            return "FAIL: 未找到可用的 ITelephony.setDataEnabled 系列方法 ("
                    + (lastMiss == null ? "" : lastMiss.getMessage()) + ")";
        } catch (Throwable t) {
            return "FAIL: " + t;
        }
    }

    /**
     * 真正切换“默认数据卡”：ISub.setDefaultDataSubId（需 MODIFY_PHONE_STATE，shell 持有）。
     * 这是系统级的默认数据订阅切换，与逐卡 setUserDataEnabled 语义不同。
     */
    @Override
    public String setDefaultDataSubId(int subId) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "isub");
            if (b == null) {
                return "FAIL: isub service not found";
            }
            Class<?> isub = Class.forName("com.android.internal.telephony.ISub");
            Object stub = isub.getMethod("asInterface", IBinder.class).invoke(null, b);
            isub.getMethod("setDefaultDataSubId", int.class).invoke(stub, subId);
            return "OK: setDefaultDataSubId=" + subId;
        } catch (Throwable t) {
            return "FAIL: setDefaultDataSubId: " + t;
        }
    }

    @Override
    public String setAirplaneMode(boolean enable) {
        // 策略 1：IConnectivityManager.setAirplaneMode（最直接）
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "connectivity");
            if (b != null) {
                Class<?> ic = Class.forName("android.net.IConnectivityManager");
                Object stub = ic.getMethod("asInterface", IBinder.class).invoke(null, b);
                ic.getMethod("setAirplaneMode", boolean.class).invoke(stub, enable);
                return "OK: IConnectivityManager.setAirplaneMode=" + enable;
            }
        } catch (Throwable ignored) {
        }

        // 策略 2：写全局设置 + 广播（经典组合）
        String out1 = run("settings put global airplane_mode_on " + (enable ? "1" : "0"));
        if (failed(out1)) {
            return "FAIL: settings put: " + out1;
        }
        run("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + (enable ? "true" : "false"));

        // 策略 3：部分新版本的命令
        run("cmd connectivity airplane-mode " + (enable ? "enable" : "disable"));

        // M6：回读必须用“独立来源”。settings get 读回的是自己刚写的值（自证），
        // 证明不了射频真的切换；cmd connectivity airplane-mode 走 ConnectivityService 查询，
        // 输出 enabled/disabled，才是可用的旁证。
        String indep = run("cmd connectivity airplane-mode");
        String want = enable ? "enabled" : "disabled";
        if (indep != null && indep.trim().toLowerCase().contains(want)) {
            return "OK: airplane_mode_on -> " + (enable ? 1 : 0)
                    + "（独立回读 cmd connectivity: " + indep.trim() + "）";
        }
        String now = run("settings get global airplane_mode_on");
        boolean wrote = enable ? "1".equals(now.trim()) : "0".equals(now.trim());
        // 不再把 "null" 当成功（那是读失败，不是生效）
        if (wrote) {
            return "OK?: airplane_mode_on -> " + (enable ? 1 : 0)
                    + "（仅 settings 自证，未取得独立回读：" + indep.trim() + "）";
        }
        return "FAIL: airplane_mode_on 仍为 " + now;
    }
}
