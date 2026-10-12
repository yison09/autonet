package com.autonetswitch.app.net;

public class NetState {

    public boolean wifiEnabled;
    public boolean wifiConnected;
    public boolean wifiValidated;
    public String ssid = "";
    public boolean cellConnected;
    public boolean cellValidated;
    public boolean dataEnabled;
    public boolean airplane;
    public int defaultTransport = -1;
    // 无效值 -127；有效范围约 -110（极弱）~ 0（极强）
    public int wifiRssi = -127;

    public NetState copy() {
        NetState s = new NetState();
        s.wifiEnabled = wifiEnabled;
        s.wifiConnected = wifiConnected;
        s.wifiValidated = wifiValidated;
        s.ssid = ssid;
        s.wifiRssi = wifiRssi;
        s.cellConnected = cellConnected;
        s.cellValidated = cellValidated;
        s.dataEnabled = dataEnabled;
        s.airplane = airplane;
        s.defaultTransport = defaultTransport;
        return s;
    }

    public boolean sameAs(NetState o) {
        if (o == null) {
            return false;
        }
        return wifiEnabled == o.wifiEnabled
                && wifiConnected == o.wifiConnected
                && wifiValidated == o.wifiValidated
                && wifiRssi == o.wifiRssi
                && cellConnected == o.cellConnected
                && cellValidated == o.cellValidated
                && dataEnabled == o.dataEnabled
                && airplane == o.airplane
                && defaultTransport == o.defaultTransport
                && eq(ssid, o.ssid);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public String wifiBrief() {
        if (!wifiEnabled) {
            return "已关闭";
        }
        if (!wifiConnected) {
            return "未连接";
        }
        String s = (ssid == null || ssid.isEmpty()) ? "(未知SSID)" : ssid;
        if (wifiRssi > -127) {
            s += " " + wifiRssi + "dBm";
        }
        return s + (wifiValidated ? " · 有网络" : " · 无网络");
    }

    public String cellBrief() {
        if (airplane) {
            return "飞行模式";
        }
        if (!dataEnabled) {
            return "数据已关闭";
        }
        if (!cellConnected) {
            return "未连接";
        }
        return cellValidated ? "已连接 · 有网络" : "已连接 · 无网络";
    }

    public String defaultBrief() {
        switch (defaultTransport) {
            case 1: // TRANSPORT_WIFI
                return "WiFi";
            case 0: // TRANSPORT_CELLULAR
                return "移动数据";
            case -1:
                return "无";
            default:
                return "其他";
        }
    }
}
