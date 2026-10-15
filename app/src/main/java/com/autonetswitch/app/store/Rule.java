package com.autonetswitch.app.store;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public class Rule {

    public static final int TYPE_WIFI_NO_NET = 1;
    public static final int TYPE_SSID = 2;
    public static final int TYPE_SCHEDULE = 3;
    public static final int TYPE_WEAK_SIGNAL = 4;

    public String id;
    public String name = "";
    public int type = TYPE_WIFI_NO_NET;
    public boolean enabled = true;

    // TYPE_WIFI_NO_NET：条件持续 delaySec 秒后触发
    public int delaySec = 15;

    // TYPE_SSID：ssid 支持 * 通配（如 "*" 表示任意网络）
    public String ssid = "";
    public boolean ssidEnter = true;

    // TYPE_SCHEDULE：end <= start 视为跨天；daysMask bit0=周一 … bit6=周日
    public int startHour = 7;
    public int startMinute = 0;
    public int endHour = 9;
    public int endMinute = 0;
    public int daysMask = 0x7f;

    // 动作集合
    public boolean doWifi = false;
    public boolean wifiOn = true;
    public boolean doData = true;
    public boolean dataOn = true;
    public boolean doAirplane = false;
    public boolean airplaneOn = false;

    public int cooldownMin = 5;
    public boolean autoRevert = true;

    // TYPE_WEAK_SIGNAL：RSSI 低于阈值（更负）视为弱信号
    public int rssiThreshold = -75;
    public int weakHoldSec = 15;

    // 运行时状态（持久化，跨重启保持）
    public long lastTriggeredAt = 0;
    public boolean active = false;
    public long condSinceMs = 0;
    public long clearSinceMs = 0;
    // 非持久化：本条件周期内是否已提示过 Shizuku 未授权（避免每次尝试都刷日志）
    public transient boolean warnedNoAuth = false;

    public Rule() {
        this.id = UUID.randomUUID().toString();
    }

    public static Rule defaultFor(int type) {
        Rule r = new Rule();
        r.type = type;
        switch (type) {
            case TYPE_WIFI_NO_NET:
                r.name = "WiFi 无网络切数据";
                r.doWifi = false;
                r.doData = true;
                r.dataOn = true;
                r.autoRevert = true;
                break;
            case TYPE_SSID:
                r.name = "SSID 触发";
                r.ssid = "";
                r.ssidEnter = true;
                r.doWifi = false;
                r.doData = false;
                r.autoRevert = false;
                break;
            case TYPE_SCHEDULE:
                r.name = "定时开关 WiFi";
                r.doWifi = true;
                r.wifiOn = false;
                r.doData = false;
                r.autoRevert = false;
                break;
            case TYPE_WEAK_SIGNAL:
                r.name = "弱信号重选 WiFi";
                r.doWifi = false;
                r.doData = false;
                r.autoRevert = true;
                break;
            default:
                break;
        }
        return r;
    }

    public String typeLabel() {
        switch (type) {
            case TYPE_WIFI_NO_NET:
                return "WiFi 无网络";
            case TYPE_SSID:
                return "SSID 触发";
            case TYPE_SCHEDULE:
                return "定时任务";
            case TYPE_WEAK_SIGNAL:
                return "弱信号重选";
            default:
                return "未知";
        }
    }

    public String describe() {
        switch (type) {
            case TYPE_WIFI_NO_NET:
                return "WiFi 无网络 ≥ " + delaySec + " 秒 → 执行动作"
                        + (autoRevert ? "（恢复后回滚）" : "");
            case TYPE_SSID:
                return (ssidEnter ? "连接到 " : "离开 ") + (ssid == null || ssid.isEmpty() ? "(未设置)" : ssid)
                        + " → 执行动作" + (autoRevert ? "（离开后回滚）" : "");
            case TYPE_SCHEDULE:
                return String.format(java.util.Locale.getDefault(),
                        "每天 %02d:%02d–%02d:%02d → 执行动作", startHour, startMinute, endHour, endMinute);
            case TYPE_WEAK_SIGNAL:
                return String.format(java.util.Locale.getDefault(),
                        "WiFi 信号 < %d dBm 持续 %d 秒 → 重选网络%s",
                        rssiThreshold, weakHoldSec, autoRevert ? "（恢复后回滚辅助动作）" : "");
            default:
                return "";
        }
    }

    public String actionsBrief() {
        StringBuilder sb = new StringBuilder();
        if (doWifi) {
            sb.append("WiFi").append(wifiOn ? "开" : "关").append(" ");
        }
        if (doData) {
            sb.append("数据").append(dataOn ? "开" : "关").append(" ");
        }
        if (doAirplane) {
            sb.append("飞行模式").append(airplaneOn ? "开" : "关");
        }
        return sb.length() == 0 ? "无动作" : sb.toString().trim();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("type", type);
            o.put("enabled", enabled);
            o.put("delaySec", delaySec);
            o.put("ssid", ssid);
            o.put("ssidEnter", ssidEnter);
            o.put("startHour", startHour);
            o.put("startMinute", startMinute);
            o.put("endHour", endHour);
            o.put("endMinute", endMinute);
            o.put("daysMask", daysMask);
            o.put("doWifi", doWifi);
            o.put("wifiOn", wifiOn);
            o.put("doData", doData);
            o.put("dataOn", dataOn);
            o.put("doAirplane", doAirplane);
            o.put("airplaneOn", airplaneOn);
            o.put("cooldownMin", cooldownMin);
            o.put("autoRevert", autoRevert);
            o.put("rssiThreshold", rssiThreshold);
            o.put("weakHoldSec", weakHoldSec);
            o.put("lastTriggeredAt", lastTriggeredAt);
            o.put("active", active);
            o.put("condSinceMs", condSinceMs);
            o.put("clearSinceMs", clearSinceMs);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Rule fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        Rule r = new Rule();
        r.id = o.optString("id", UUID.randomUUID().toString());
        r.name = o.optString("name", "");
        r.type = o.optInt("type", TYPE_WIFI_NO_NET);
        r.enabled = o.optBoolean("enabled", true);
        r.delaySec = o.optInt("delaySec", 15);
        r.ssid = o.optString("ssid", "");
        r.ssidEnter = o.optBoolean("ssidEnter", true);
        r.startHour = o.optInt("startHour", 7);
        r.startMinute = o.optInt("startMinute", 0);
        r.endHour = o.optInt("endHour", 9);
        r.endMinute = o.optInt("endMinute", 0);
        r.daysMask = o.optInt("daysMask", 0x7f);
        r.doWifi = o.optBoolean("doWifi", false);
        r.wifiOn = o.optBoolean("wifiOn", true);
        r.doData = o.optBoolean("doData", true);
        r.dataOn = o.optBoolean("dataOn", true);
        r.doAirplane = o.optBoolean("doAirplane", false);
        r.airplaneOn = o.optBoolean("airplaneOn", false);
        r.cooldownMin = o.optInt("cooldownMin", 5);
        r.autoRevert = o.optBoolean("autoRevert", true);
        r.rssiThreshold = o.optInt("rssiThreshold", -75);
        r.weakHoldSec = o.optInt("weakHoldSec", 15);
        r.lastTriggeredAt = o.optLong("lastTriggeredAt", 0);
        r.active = o.optBoolean("active", false);
        r.condSinceMs = o.optLong("condSinceMs", 0);
        r.clearSinceMs = o.optLong("clearSinceMs", 0);
        return r;
    }
}
