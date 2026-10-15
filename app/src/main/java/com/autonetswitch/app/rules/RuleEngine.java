package com.autonetswitch.app.rules;

import android.os.Handler;
import android.os.Looper;

import com.autonetswitch.app.App;
import com.autonetswitch.app.net.NetState;
import com.autonetswitch.app.net.NetworkMonitor;
import com.autonetswitch.app.shizuku.ShizukuBridge;
import com.autonetswitch.app.store.Rule;
import com.autonetswitch.app.util.AppLog;

import java.util.Calendar;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 规则评估引擎。
 * 评估时机：状态变化事件 + 周期 tick + 按“延迟到期”自适应唤醒。
 */
public class RuleEngine {

    private static final long TICK_MS = 30_000;
    private static final long REVERT_STABLE_MS = 60_000;
    private static final long SSID_DELAY_MS = 3_000;

    private static final RuleEngine INSTANCE = new RuleEngine();

    public static RuleEngine get() {
        return INSTANCE;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private NetworkMonitor.Listener monitorListener;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            doTick();
            handler.postDelayed(this, computeNextWake());
        }
    };

    public void start() {
        if (running) {
            return;
        }
        running = true;
        monitorListener = s -> handler.post(this::evaluateAll);
        NetworkMonitor.get().addListener(monitorListener);
        handler.removeCallbacks(tick);
        handler.post(tick);
        AppLog.append("规则引擎已启动");
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (monitorListener != null) {
            NetworkMonitor.get().removeListener(monitorListener);
            monitorListener = null;
        }
        handler.removeCallbacks(tick);
        AppLog.append("规则引擎已停止");
    }

    public boolean isRunning() {
        return running;
    }

    private void doTick() {
        NetworkMonitor.get().refresh();
        evaluateAll();
    }

    private long computeNextWake() {
        if (!running) {
            return TICK_MS;
        }
        long now = System.currentTimeMillis();
        long next = TICK_MS;
        NetState s = NetworkMonitor.get().snapshot();
        for (Rule r : App.get().rules().list()) {
            if (!r.enabled) {
                continue;
            }
            if (!r.active && r.condSinceMs > 0) {
                // 只在"未来会到点"时安排精确唤醒；remain≤0 说明到点但被挡住
                //（冷却/未授权），回落常规 tick，避免 1Hz 忙轮询
                long remain = delayFor(r) - (now - r.condSinceMs);
                if (remain > 0 && remain < next) {
                    next = remain;
                }
            }
            if (r.active && r.type == Rule.TYPE_SCHEDULE) {
                long remain = msUntilScheduleEnd(r, now);
                if (remain > 0 && remain < next) {
                    next = remain;
                }
            }
            if (r.active && r.autoRevert && r.type != Rule.TYPE_SCHEDULE && r.clearSinceMs > 0) {
                long remain = REVERT_STABLE_MS - (now - r.clearSinceMs);
                if (remain > 0 && remain < next) {
                    next = remain;
                }
            }
        }
        if (next < 1_000) {
            next = 1_000;
        }
        if (next > TICK_MS) {
            next = TICK_MS;
        }
        return next;
    }

    private void evaluateAll() {
        if (!running) {
            return;
        }
        NetState s = NetworkMonitor.get().snapshot();
        List<Rule> rules = App.get().rules().list();
        for (Rule r : rules) {
            if (!r.enabled) {
                continue;
            }
            try {
                evaluate(r, s);
            } catch (Throwable t) {
                AppLog.append("规则「" + r.name + "」异常: " + t);
            }
        }
    }

    private long delayFor(Rule r) {
        switch (r.type) {
            case Rule.TYPE_WIFI_NO_NET:
                return Math.max(3, r.delaySec) * 1000L;
            case Rule.TYPE_SSID:
                return SSID_DELAY_MS;
            case Rule.TYPE_WEAK_SIGNAL:
                return Math.max(3, r.weakHoldSec) * 1000L;
            case Rule.TYPE_SCHEDULE:
            default:
                return 0;
        }
    }

    private void evaluate(Rule r, NetState s) {
        long now = System.currentTimeMillis();
        boolean cond;
        switch (r.type) {
            case Rule.TYPE_WIFI_NO_NET:
                cond = s.wifiEnabled && s.wifiConnected && !s.wifiValidated;
                break;
            case Rule.TYPE_SSID: {
                boolean matched = ssidMatches(s.ssid, r.ssid) && s.wifiConnected;
                cond = r.ssidEnter ? matched : !matched;
                break;
            }
            case Rule.TYPE_SCHEDULE:
                cond = scheduleCond(r, now);
                break;
            case Rule.TYPE_WEAK_SIGNAL:
                // -127 表示无效读数（如无定位权限），不触发
                cond = s.wifiEnabled && s.wifiConnected
                        && s.wifiRssi > -127 && s.wifiRssi < r.rssiThreshold;
                break;
            default:
                cond = false;
        }

        boolean changed = false;
        if (cond) {
            if (r.condSinceMs == 0) {
                r.condSinceMs = now;
                changed = true;
            }
            if (r.clearSinceMs != 0) {
                r.clearSinceMs = 0;
                changed = true;
            }
            if (!r.active && now - r.condSinceMs >= delayFor(r)) {
                changed |= tryTrigger(r, now);
            }
        } else {
            if (r.clearSinceMs == 0) {
                r.clearSinceMs = now;
                changed = true;
            }
            if (r.condSinceMs != 0) {
                r.condSinceMs = 0;
                r.warnedNoAuth = false;
                changed = true;
            }
            if (r.active) {
                if (r.type == Rule.TYPE_SCHEDULE) {
                    // 定时规则语义 = "时段内生效"：窗口结束立即回滚窗口内执行的动作
                    // （不依赖 autoRevert，UI 上该开关对定时类型固定禁用）
                    changed |= doRevert(r, "窗口结束，已回滚");
                } else if (r.autoRevert) {
                    boolean recovered = r.type != Rule.TYPE_WIFI_NO_NET
                            || (s.wifiEnabled && s.wifiConnected && s.wifiValidated);
                    if (recovered && now - r.clearSinceMs >= REVERT_STABLE_MS) {
                        changed |= doRevert(r, "条件恢复，已回滚");
                    }
                } else {
                    r.active = false;
                    changed = true;
                }
            }
        }

        if (changed) {
            App.get().rules().save(r);
        }
    }

    private boolean tryTrigger(Rule r, long now) {
        if (r.lastTriggeredAt > 0 && now - r.lastTriggeredAt < r.cooldownMin * 60_000L) {
            return false;
        }
        if (!ShizukuBridge.get().granted()) {
            // 本条件周期内只提示一次（此前条件恒 false，该日志永远打不出来）
            if (!r.warnedNoAuth) {
                r.warnedNoAuth = true;
                AppLog.append("规则「" + r.name + "」无法执行：Shizuku 未授权");
            }
            return false;
        }
        if (r.type == Rule.TYPE_WEAK_SIGNAL) {
            // 核心动作：重选 WiFi（系统在已保存网络中重新选优）
            ShizukuBridge.get().reselectWifi(res ->
                    AppLog.append("规则「" + r.name + "」WiFi重选 → " + res));
        }
        runActions(r, false);
        r.active = true;
        r.lastTriggeredAt = now;
        AppLog.append("规则「" + r.name + "」已触发：" + r.actionsBrief());
        return true;
    }

    private boolean doRevert(Rule r, String reason) {
        if (!ShizukuBridge.get().granted()) {
            return false;
        }
        runActions(r, true);
        r.active = false;
        r.clearSinceMs = 0;
        AppLog.append("规则「" + r.name + "」" + reason);
        return true;
    }

    /**
     * M8：规则在活动状态被停用/删除/改类型时，先把已执行的动作滚回去，
     * 避免"数据一直开、无人回滚"。
     */
    public void revertIfActive(Rule r, String reason) {
        if (r == null || !r.active) {
            return;
        }
        if (doRevert(r, reason)) {
            App.get().rules().save(r);
        } else {
            AppLog.append("规则「" + r.name + "」" + reason + "失败：Shizuku 未授权，动作未回滚");
        }
    }

    private void runActions(Rule r, boolean invert) {
        ShizukuBridge b = ShizukuBridge.get();
        String tag = "规则「" + r.name + "」" + (invert ? "回滚" : "执行");

        boolean airOn = r.doAirplane && (invert != r.airplaneOn);
        boolean dataOn = r.doData && (invert != r.dataOn);
        boolean wifiVal = r.doWifi && (invert != r.wifiOn);
        // 弱信号规则的 WiFi 动作由“重选”接管，忽略手动配置的开关以免冲突
        boolean wifiSuppressed = r.type == Rule.TYPE_WEAK_SIGNAL;

        // 顺序：先“建立连接”后“断开”，避免出现断网空窗
        if (r.doAirplane && airOn) {
            b.setAirplane(true, res -> AppLog.append(tag + " 飞行模式开 → " + res));
        }
        if (r.doData && dataOn) {
            b.setData(true, res -> AppLog.append(tag + " 移动数据开 → " + res));
        }
        if (r.doWifi && !wifiSuppressed) {
            final boolean v = wifiVal;
            b.setWifi(v, res -> AppLog.append(tag + " WiFi" + (v ? "开" : "关") + " → " + res));
        }
        if (r.doData && !dataOn) {
            b.setData(false, res -> AppLog.append(tag + " 移动数据关 → " + res));
        }
        if (r.doAirplane && !airOn) {
            b.setAirplane(false, res -> AppLog.append(tag + " 飞行模式关 → " + res));
        }
    }

    static boolean ssidMatches(String actual, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        if (!pattern.contains("*")) {
            return actual.equalsIgnoreCase(pattern);
        }
        String[] segs = pattern.split("\\*", -1);
        StringBuilder rx = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                rx.append(".*");
            }
            rx.append(Pattern.quote(segs[i]));
        }
        return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE).matcher(actual).matches();
    }

    /** M1：计算距离当前定时窗口结束还有多少毫秒（供精确唤醒执行回滚）。 */
    private static long msUntilScheduleEnd(Rule r, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int minuteOfDay = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int secondOfMinute = c.get(Calendar.SECOND);
        int start = r.startHour * 60 + r.startMinute;
        int end = r.endHour * 60 + r.endMinute;
        if (start == end) {
            return Long.MAX_VALUE;
        }
        if (start < end) {
            if (minuteOfDay >= start && minuteOfDay < end) {
                return (end - minuteOfDay) * 60_000L - secondOfMinute * 1000L;
            }
        } else {
            // 跨天窗口：[start, 24:00) ∪ [00:00, end)
            if (minuteOfDay >= start) {
                return ((24 * 60 - minuteOfDay) + end) * 60_000L - secondOfMinute * 1000L;
            }
            if (minuteOfDay < end) {
                return (end - minuteOfDay) * 60_000L - secondOfMinute * 1000L;
            }
        }
        // 不在窗口内但规则仍 active：尽快唤醒执行回滚
        return 1_000L;
    }

    private static boolean scheduleCond(Rule r, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int minuteOfDay = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int start = r.startHour * 60 + r.startMinute;
        int end = r.endHour * 60 + r.endMinute;
        if (start == end) {
            return false;
        }
        boolean inWindow;
        int calDay = c.get(Calendar.DAY_OF_WEEK);
        if (start < end) {
            inWindow = minuteOfDay >= start && minuteOfDay < end;
            if (!inWindow) {
                return false;
            }
            return (r.daysMask & (1 << dayToBit(calDay))) != 0;
        }
        // 跨天窗口
        if (minuteOfDay >= start) {
            return (r.daysMask & (1 << dayToBit(calDay))) != 0;
        }
        if (minuteOfDay < end) {
            return (r.daysMask & (1 << dayToBit(prevDay(calDay)))) != 0;
        }
        return false;
    }

    private static int dayToBit(int calDay) {
        switch (calDay) {
            case Calendar.MONDAY:
                return 0;
            case Calendar.TUESDAY:
                return 1;
            case Calendar.WEDNESDAY:
                return 2;
            case Calendar.THURSDAY:
                return 3;
            case Calendar.FRIDAY:
                return 4;
            case Calendar.SATURDAY:
                return 5;
            case Calendar.SUNDAY:
            default:
                return 6;
        }
    }

    private static int prevDay(int calDay) {
        return calDay == Calendar.SUNDAY ? Calendar.SATURDAY : calDay - 1;
    }
}
