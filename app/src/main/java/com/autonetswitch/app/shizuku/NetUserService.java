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
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), Charset.defaultCharset()));
            StringBuilder sb = new StringBuilder();
            String line;
            int total = 0;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                total += line.length() + 1;
                if (total >= MAX_OUTPUT) {
                    break;
                }
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "EX: " + t;
        } finally {
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

            // 依次尝试各 Android 版本的签名（新 → 旧）
            try {
                itel.getMethod("setDataEnabledForReason", int.class, boolean.class, int.class)
                        .invoke(stub, subId, enable, 0 /* REASON_USER */);
                return "OK: setDataEnabledForReason sub=" + subId + "=" + enable;
            } catch (NoSuchMethodException ignored) {
            }
            try {
                itel.getMethod("setUserDataEnabled", int.class, boolean.class)
                        .invoke(stub, subId, enable);
                return "OK: setUserDataEnabled sub=" + subId + "=" + enable;
            } catch (NoSuchMethodException ignored) {
            }
            try {
                itel.getMethod("setDataEnabled", int.class, boolean.class)
                        .invoke(stub, subId, enable);
                return "OK: setDataEnabled sub=" + subId + "=" + enable;
            } catch (NoSuchMethodException ignored) {
            }
            try {
                itel.getMethod("setDataEnabled", boolean.class).invoke(stub, enable);
                return "OK: setDataEnabled(默认卡) =" + enable;
            } catch (NoSuchMethodException ignored) {
            }
            return "FAIL: 未找到可用的 ITelephony.setDataEnabled 系列方法";
        } catch (Throwable t) {
            return "FAIL: " + t;
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

        String now = run("settings get global airplane_mode_on");
        if ((enable && "1".equals(now.trim())) || (!enable && "0".equals(now.trim())) || "null".equals(now.trim())) {
            return "OK: airplane_mode_on -> " + (enable ? 1 : 0) + "（若未生效请反馈设备型号）";
        }
        return "FAIL: airplane_mode_on 仍为 " + now;
    }
}
