package com.autonetswitch.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.autonetswitch.app.App;
import com.autonetswitch.app.R;
import com.autonetswitch.app.net.NetState;
import com.autonetswitch.app.net.NetworkMonitor;
import com.autonetswitch.app.service.AutoSwitchService;
import com.autonetswitch.app.shizuku.ShizukuBridge;
import com.autonetswitch.app.store.Rule;
import com.autonetswitch.app.util.AppLog;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 41;
    private static final long POLL_MS = 4000;

    private TextView txtShizuku, txtWifiSub, txtCellSub, txtAirSub, txtDefaultNet, txtRulesEmpty;
    private MaterialSwitch swWifi, swData, swAirplane, swService, swAutostart;
    private MaterialButton btnShizuku, btnBattery, btnGrantLocation;
    private View rowSim;
    private android.widget.Spinner spSub;
    private RuleAdapter adapter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean suppress;
    private boolean askedPerms;

    private final ShizukuBridge.StateListener shizukuListener = this::refreshShizukuState;
    private final NetworkMonitor.Listener netListener = s -> ui.post(() -> fillNet(s));

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            NetworkMonitor.get().refresh();
            ui.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        txtShizuku = findViewById(R.id.txtShizukuState);
        btnShizuku = findViewById(R.id.btnShizukuAction);
        txtWifiSub = findViewById(R.id.txtWifiSub);
        txtCellSub = findViewById(R.id.txtCellSub);
        txtAirSub = findViewById(R.id.txtAirSub);
        txtDefaultNet = findViewById(R.id.txtDefaultNet);
        txtRulesEmpty = findViewById(R.id.txtRulesEmpty);
        swWifi = findViewById(R.id.swWifi);
        swData = findViewById(R.id.swData);
        swAirplane = findViewById(R.id.swAirplane);
        swService = findViewById(R.id.swService);
        swAutostart = findViewById(R.id.swAutostart);
        rowSim = findViewById(R.id.rowSim);
        spSub = findViewById(R.id.spSub);
        btnBattery = findViewById(R.id.btnBattery);
        btnGrantLocation = findViewById(R.id.btnGrantLocation);
        FloatingActionButton fab = findViewById(R.id.fabAdd);

        adapter = new RuleAdapter(new RuleAdapter.Listener() {
            @Override
            public void onOpen(Rule r) {
                Intent i = new Intent(MainActivity.this, RuleEditActivity.class);
                i.putExtra("rule_id", r.id);
                startActivity(i);
            }

            @Override
            public void onToggle(Rule r, boolean enabled) {
                if (!enabled) {
                    // M8：停用活动中的规则前先回滚已执行动作
                    com.autonetswitch.app.rules.RuleEngine.get().revertIfActive(r, "规则已停用，回滚");
                }
                r.enabled = enabled;
                App.get().rules().save(r);
                AppLog.append("规则「" + r.name + "」" + (enabled ? "已启用" : "已停用"));
                if (enabled && !AutoSwitchService.isRunning()) {
                    AutoSwitchService.start(MainActivity.this);
                    ui.postDelayed(() -> syncServiceSwitch(), 500);
                }
                refreshRules();
            }
        });
        RecyclerView recycler = findViewById(R.id.recyclerRules);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        btnShizuku.setOnClickListener(v -> handleShizukuAction());
        fab.setOnClickListener(v -> {
            Intent i = new Intent(MainActivity.this, RuleEditActivity.class);
            i.putExtra("rule_type", Rule.TYPE_WIFI_NO_NET);
            startActivity(i);
        });
        btnBattery.setOnClickListener(v -> openBatterySettings());
        btnGrantLocation.setOnClickListener(v -> requestNeededPermissions());

        swWifi.setOnCheckedChangeListener((btn, checked) -> {
            if (suppress) return;
            runToggle(checked, ok -> ShizukuBridge.get().setWifi(ok, res ->
                    logAsync("手动 WiFi" + (ok ? "开" : "关"), res)));
        });
        swData.setOnCheckedChangeListener((btn, checked) -> {
            if (suppress) return;
            runToggle(checked, ok -> ShizukuBridge.get().setData(ok, res ->
                    logAsync("手动 移动数据" + (ok ? "开" : "关"), res)));
        });
        swAirplane.setOnCheckedChangeListener((btn, checked) -> {
            if (suppress) return;
            runToggle(checked, ok -> ShizukuBridge.get().setAirplane(ok, res ->
                    logAsync("手动 飞行模式" + (ok ? "开" : "关"), res)));
        });

        swService.setOnCheckedChangeListener((btn, checked) -> {
            if (suppress) return;
            if (checked) {
                AutoSwitchService.start(this);
            } else {
                AutoSwitchService.stop(this);
            }
            ui.postDelayed(() -> syncServiceSwitch(), 500);
        });

        swAutostart.setOnCheckedChangeListener((btn, checked) -> App.setPrefBool("autostart", checked));

        findViewById(R.id.sceneDefault).setOnClickListener(v -> sceneDefault());
        findViewById(R.id.sceneCell).setOnClickListener(v -> sceneCellOnly());
        findViewById(R.id.sceneWifiOn).setOnClickListener(v -> sceneWifiOn());
        findViewById(R.id.sceneAirplaneOn).setOnClickListener(v -> sceneAirplaneOn());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShizukuBridge.get().addListener(shizukuListener);
        NetworkMonitor.get().addListener(netListener);
        refreshShizukuState();
        refreshRules();
        syncServiceSwitch();
        syncAutostartSwitch();
        NetworkMonitor.get().refresh();
        setupSimRow();
        maybeAskPerms();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ShizukuBridge.get().removeListener(shizukuListener);
        NetworkMonitor.get().removeListener(netListener);
        ui.removeCallbacks(poll);
    }

    // ---------- Shizuku ----------

    private void refreshShizukuState() {
        ShizukuBridge b = ShizukuBridge.get();
        if (!b.daemonAlive()) {
            txtShizuku.setText(R.string.shizuku_not_running);
            btnShizuku.setText(R.string.btn_shizuku_open);
        } else if (!b.granted()) {
            txtShizuku.setText(R.string.shizuku_not_granted);
            btnShizuku.setText(R.string.btn_shizuku_activate);
        } else {
            txtShizuku.setText(R.string.shizuku_granted);
            btnShizuku.setText(R.string.btn_shizuku_retry);
        }
    }

    private void handleShizukuAction() {
        ShizukuBridge b = ShizukuBridge.get();
        if (!b.daemonAlive()) {
            try {
                // Manager 的 applicationId 是 moe.shizuku.privileged.api（moe.shizuku.manager 只是 namespace）
                Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (i != null) {
                    startActivity(i);
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")));
            } catch (Exception e) {
                toast("无法打开浏览器");
            }
            return;
        }
        if (!b.granted()) {
            try {
                if (Shizuku.isPreV11()) {
                    toast("Shizuku 版本过旧，请升级到新版");
                    return;
                }
                Shizuku.requestPermission(REQ_PERMS);
            } catch (Throwable t) {
                toast("请求授权失败: " + t);
            }
            return;
        }
        b.execute(svc -> "连接成功 (uid=" + svc.ping() + ")", res -> ui.post(() -> {
            AppLog.append("Shizuku 连接测试: " + res);
            toast(res);
            refreshShizukuState();
        }));
    }

    private void requireAuth(Runnable action) {
        if (!ShizukuBridge.get().granted()) {
            toast("请先在上方授权 Shizuku");
            refreshShizukuState();
            return;
        }
        action.run();
    }

    private void logAsync(String what, String res) {
        ui.post(() -> {
            AppLog.append(what + " → " + res);
            scheduleStateRefresh();
        });
    }

    private interface ToggleOp {
        void run(boolean on);
    }

    private void runToggle(boolean checked, ToggleOp op) {
        if (!ShizukuBridge.get().granted()) {
            toast("请先授权 Shizuku");
            ui.postDelayed(() -> NetworkMonitor.get().refresh(), 300);
            return;
        }
        op.run(checked);
        scheduleStateRefresh();
    }

    private void scheduleStateRefresh() {
        ui.postDelayed(() -> NetworkMonitor.get().refresh(), 800);
        ui.postDelayed(() -> NetworkMonitor.get().refresh(), 2400);
    }

    // ---------- 场景 ----------

    private void sceneDefault() {
        requireAuth(() -> {
            ShizukuBridge.get().setAirplane(false, res -> logAsync("场景[恢复默认] 飞行模式关", res));
            ShizukuBridge.get().setData(true, res -> logAsync("场景[恢复默认] 数据开", res));
            ShizukuBridge.get().setWifi(true, res -> logAsync("场景[恢复默认] WiFi开", res));
            scheduleStateRefresh();
        });
    }

    private void sceneCellOnly() {
        requireAuth(() -> {
            ShizukuBridge.get().setWifi(false, res -> logAsync("场景[强制蜂窝] WiFi关", res));
            ShizukuBridge.get().setData(true, res -> logAsync("场景[强制蜂窝] 数据开", res));
            scheduleStateRefresh();
        });
    }

    private void sceneWifiOn() {
        requireAuth(() -> {
            ShizukuBridge.get().setWifi(true, res -> logAsync("场景[打开WiFi] WiFi开", res));
            scheduleStateRefresh();
        });
    }

    private void sceneAirplaneOn() {
        requireAuth(() -> {
            ShizukuBridge.get().setAirplane(true, res -> logAsync("场景[飞行模式] 开", res));
            scheduleStateRefresh();
        });
    }

    // ---------- 网络状态展示 ----------

    private void fillNet(NetState s) {
        suppress = true;
        swWifi.setChecked(s.wifiEnabled);
        swData.setChecked(s.dataEnabled);
        swAirplane.setChecked(s.airplane);
        suppress = false;
        txtWifiSub.setText(s.wifiBrief());
        txtCellSub.setText(s.cellBrief());
        txtAirSub.setText(s.airplane ? R.string.airplane_on : R.string.airplane_off);
        txtDefaultNet.setText(getString(R.string.default_transport_fmt, s.defaultBrief()));
        btnGrantLocation.setVisibility(
                s.wifiConnected && s.ssid.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void syncServiceSwitch() {
        suppress = true;
        swService.setChecked(AutoSwitchService.isRunning());
        suppress = false;
    }

    private void syncAutostartSwitch() {
        suppress = true;
        swAutostart.setChecked(App.prefBool("autostart", true));
        suppress = false;
    }

    private void refreshRules() {
        List<Rule> rules = App.get().rules().list();
        adapter.submit(rules);
        txtRulesEmpty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------- 数据卡 ----------

    private void setupSimRow() {
        try {
            if (Build.VERSION.SDK_INT < 24) {
                rowSim.setVisibility(View.GONE);
                return;
            }
            SubscriptionManager sm = SubscriptionManager.from(this);
            List<SubscriptionInfo> list;
            try {
                list = sm.getActiveSubscriptionInfoList();
            } catch (SecurityException se) {
                rowSim.setVisibility(View.GONE);
                return;
            }
            if (list == null || list.size() < 2) {
                rowSim.setVisibility(View.GONE);
                return;
            }
            final List<SubscriptionInfo> infos = new ArrayList<>(list);
            final int defId = sm.getDefaultDataSubscriptionId();
            List<String> labels = new ArrayList<>();
            int sel = 0;
            for (int i = 0; i < infos.size(); i++) {
                SubscriptionInfo info = infos.get(i);
                String name = info.getDisplayName() == null
                        ? ("卡" + (i + 1)) : info.getDisplayName().toString();
                labels.add(name + " · " + (info.getSimSlotIndex() + 1) + " 号卡槽");
                if (info.getSubscriptionId() == defId) {
                    sel = i;
                }
            }
            rowSim.setVisibility(View.VISIBLE);
            ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, labels);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spSub.setAdapter(ad);
            spSub.setSelection(sel, false);
            spSub.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                boolean first = true;

                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (first) {
                        first = false;
                        return;
                    }
                    if (position >= infos.size()) {
                        return;
                    }
                    final int target = infos.get(position).getSubscriptionId();
                    if (target == defId) {
                        return;
                    }
                    switchDefaultData(infos, target);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        } catch (Throwable t) {
            rowSim.setVisibility(View.GONE);
        }
    }

    private void switchDefaultData(final List<SubscriptionInfo> infos, final int target) {
        requireAuth(() -> {
            ShizukuBridge.get().execute(svc -> {
                // 先走真正的默认数据卡切换（ISub.setDefaultDataSubId），
                // 失败再回退逐卡开关（旧方案，语义不完全等价）
                String primary = svc.setDefaultDataSubId(target);
                if (primary != null && primary.startsWith("OK")) {
                    return primary;
                }
                StringBuilder sb = new StringBuilder(primary + " → 回退逐卡: ");
                sb.append(svc.setUserDataEnabled(target, true));
                for (SubscriptionInfo info : infos) {
                    if (info.getSubscriptionId() != target) {
                        sb.append(" | ")
                                .append(svc.setUserDataEnabled(info.getSubscriptionId(), false));
                    }
                }
                return sb.toString();
            }, res -> {
                ui.post(() -> {
                    AppLog.append("切换默认数据卡 sub=" + target + " → " + res);
                    scheduleStateRefresh();
                    ui.postDelayed(() -> setupSimRow(), 1600);
                });
            });
        });
    }

    // ---------- 权限 / 设置 ----------

    private void maybeAskPerms() {
        if (askedPerms) {
            return;
        }
        askedPerms = true;
        requestNeededPermissions();
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        String[] wanted = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
        };
        for (String p : wanted) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(p);
            }
        }
        if (Build.VERSION.SDK_INT >= 31
                && ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    need.toArray(new String[0]), REQ_PERMS);
        }
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                toast("请在系统设置中关闭本应用的电池优化");
            }
        }
    }

    // ---------- 菜单 ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_log) {
            startActivity(new Intent(this, LogActivity.class));
            return true;
        }
        if (id == R.id.action_rescan) {
            refreshShizukuState();
            if (ShizukuBridge.get().granted()) {
                handleShizukuAction();
            }
            return true;
        }
        if (id == R.id.action_help) {
            showHelp();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showHelp() {
        TextView tv = new TextView(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);
        tv.setText(R.string.help_text);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_help)
                .setView(sv)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
