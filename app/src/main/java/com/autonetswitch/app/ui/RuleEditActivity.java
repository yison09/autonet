package com.autonetswitch.app.ui;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.autonetswitch.app.App;
import com.autonetswitch.app.R;
import com.autonetswitch.app.store.Rule;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Locale;

public class RuleEditActivity extends AppCompatActivity {

    private static final String[] TYPE_DESC = {
            "WiFi 已连接但检测不到网络（持续超过设定时长）时执行动作；网络恢复 60 秒后可自动回滚",
            "连接到 / 离开指定 WiFi 时执行动作（读取 SSID 需要定位权限，支持 * 通配）",
            "在设定的时间段内执行动作，支持跨天（如 23:00–07:00）与按周重复",
            "WiFi 信号持续低于阈值达到设定时长后，自动关闭再开启 WiFi，让系统重新选择最强网络"
    };

    private Rule rule;
    private boolean isNew;

    private TextInputEditText edtName, edtSsid;
    private Spinner spType, spDelay, spSsidMode, spWifiOn, spDataOn, spAirOn, spCooldown, spRssi, spHold;
    private View secNoNet, secSsid, secSchedule, secWeak;
    private TextView txtTypeDesc, txtStart, txtEnd;
    private MaterialCheckBox cbWifi, cbData, cbAir;
    private MaterialSwitch swAutoRevert;
    private Chip[] days = new Chip[7];
    private MaterialButton btnDelete;

    private int startHour, startMinute, endHour, endMinute;
    private int[] delayValues;
    private int[] cooldownValues;
    private int[] rssiValues;
    private int[] holdValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String ruleId = getIntent().getStringExtra("rule_id");
        int type = getIntent().getIntExtra("rule_type", Rule.TYPE_WIFI_NO_NET);
        if (ruleId != null) {
            rule = App.get().rules().get(ruleId);
            isNew = false;
        }
        if (rule == null) {
            rule = Rule.defaultFor(type);
            isNew = true;
        }

        setContentView(R.layout.activity_rule_edit);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(isNew ? R.string.edit_title_new : R.string.edit_title_edit);
        toolbar.setNavigationOnClickListener(v -> finish());

        edtName = findViewById(R.id.edtName);
        edtSsid = findViewById(R.id.edtSsid);
        spType = findViewById(R.id.spType);
        spDelay = findViewById(R.id.spDelay);
        spSsidMode = findViewById(R.id.spSsidMode);
        spWifiOn = findViewById(R.id.spWifiOn);
        spDataOn = findViewById(R.id.spDataOn);
        spAirOn = findViewById(R.id.spAirOn);
        spCooldown = findViewById(R.id.spCooldown);
        secNoNet = findViewById(R.id.secNoNet);
        secSsid = findViewById(R.id.secSsid);
        secSchedule = findViewById(R.id.secSchedule);
        secWeak = findViewById(R.id.secWeak);
        spRssi = findViewById(R.id.spRssi);
        spHold = findViewById(R.id.spHold);
        txtTypeDesc = findViewById(R.id.txtTypeDesc);
        txtStart = findViewById(R.id.txtStart);
        txtEnd = findViewById(R.id.txtEnd);
        cbWifi = findViewById(R.id.cbWifi);
        cbData = findViewById(R.id.cbData);
        cbAir = findViewById(R.id.cbAir);
        swAutoRevert = findViewById(R.id.swAutoRevert);
        btnDelete = findViewById(R.id.btnDelete);
        MaterialButton btnSave = findViewById(R.id.btnSave);

        days[0] = findViewById(R.id.chipD0);
        days[1] = findViewById(R.id.chipD1);
        days[2] = findViewById(R.id.chipD2);
        days[3] = findViewById(R.id.chipD3);
        days[4] = findViewById(R.id.chipD4);
        days[5] = findViewById(R.id.chipD5);
        days[6] = findViewById(R.id.chipD6);

        delayValues = getResources().getIntArray(R.array.delay_values);
        cooldownValues = getResources().getIntArray(R.array.cooldown_values);
        rssiValues = getResources().getIntArray(R.array.rssi_threshold_values);
        holdValues = getResources().getIntArray(R.array.hold_values);

        fillFromRule();
        wireListeners();
        updateSections();

        if (isNew) {
            btnDelete.setVisibility(View.GONE);
        }

        btnSave.setOnClickListener(v -> save());
        btnDelete.setOnClickListener(v -> confirmDelete());
    }

    private void fillFromRule() {
        edtName.setText(rule.name);
        spType.setSelection(Math.max(0, Math.min(3, rule.type - 1)));
        spDelay.setSelection(indexOf(delayValues, rule.delaySec, 2));
        edtSsid.setText(rule.ssid);
        spSsidMode.setSelection(rule.ssidEnter ? 0 : 1);

        startHour = rule.startHour;
        startMinute = rule.startMinute;
        endHour = rule.endHour;
        endMinute = rule.endMinute;
        updateTimeTexts();

        for (int i = 0; i < 7; i++) {
            days[i].setChecked((rule.daysMask & (1 << i)) != 0);
        }

        cbWifi.setChecked(rule.doWifi);
        spWifiOn.setSelection(rule.wifiOn ? 0 : 1);
        cbData.setChecked(rule.doData);
        spDataOn.setSelection(rule.dataOn ? 0 : 1);
        cbAir.setChecked(rule.doAirplane);
        spAirOn.setSelection(rule.airplaneOn ? 0 : 1);

        spCooldown.setSelection(indexOf(cooldownValues, rule.cooldownMin, 2));
        swAutoRevert.setChecked(rule.autoRevert);
    }

    private static int indexOf(int[] arr, int value, int fallback) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == value) {
                return i;
            }
        }
        return fallback;
    }

    private void wireListeners() {
        spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateSections();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        txtStart.setOnClickListener(v -> pickTime(true));
        txtEnd.setOnClickListener(v -> pickTime(false));
    }

    private void pickTime(final boolean isStart) {
        int h = isStart ? startHour : endHour;
        int m = isStart ? startMinute : endMinute;
        new TimePickerDialog(this, (view, hh, mm) -> {
            if (isStart) {
                startHour = hh;
                startMinute = mm;
            } else {
                endHour = hh;
                endMinute = mm;
            }
            updateTimeTexts();
        }, h, m, true).show();
    }

    private void updateTimeTexts() {
        txtStart.setText(String.format(Locale.getDefault(), "%02d:%02d", startHour, startMinute));
        txtEnd.setText(String.format(Locale.getDefault(), "%02d:%02d", endHour, endMinute));
    }

    private void updateSections() {
        int p = spType.getSelectedItemPosition();
        if (p < 0) {
            p = 0;
        }
        secNoNet.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        secSsid.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        secSchedule.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
        secWeak.setVisibility(p == 3 ? View.VISIBLE : View.GONE);
        txtTypeDesc.setText(TYPE_DESC[p]);
        swAutoRevert.setEnabled(p != 2);
    }

    private void save() {
        int typePos = spType.getSelectedItemPosition();
        int type = typePos < 0 ? 1 : typePos + 1;
        String ssid = edtSsid.getText() == null ? "" : edtSsid.getText().toString().trim();

        if (type == Rule.TYPE_SSID && ssid.isEmpty()) {
            Toast.makeText(this, R.string.err_ssid_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        int mask = 0;
        for (int i = 0; i < 7; i++) {
            if (days[i].isChecked()) {
                mask |= (1 << i);
            }
        }
        if (type == Rule.TYPE_SCHEDULE && mask == 0) {
            Toast.makeText(this, R.string.err_no_day, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean doWifi = cbWifi.isChecked();
        boolean doData = cbData.isChecked();
        boolean doAir = cbAir.isChecked();
        // 弱信号类型的核心动作是“重选”，允许不勾任何辅助动作
        if (type != Rule.TYPE_WEAK_SIGNAL && !doWifi && !doData && !doAir) {
            Toast.makeText(this, R.string.err_no_action, Toast.LENGTH_SHORT).show();
            return;
        }

        String name = edtName.getText() == null ? "" : edtName.getText().toString().trim();
        if (name.isEmpty()) {
            name = spType.getSelectedItem().toString();
        }

        // M8：规则正处在活动状态时，若动作配置被改动，先按“旧配置”把已执行的动作滚回去，
        // 否则旧动作会残留而无人回收（新配置的 evaluate 只会按新动作再执行一遍）。
        if (rule.active) {
            boolean wifiOnNew = spWifiOn.getSelectedItemPosition() == 0;
            boolean dataOnNew = spDataOn.getSelectedItemPosition() == 0;
            boolean airOnNew = spAirOn.getSelectedItemPosition() == 0;
            boolean actionChanged = rule.type != type
                    || rule.doWifi != doWifi || (doWifi && rule.wifiOn != wifiOnNew)
                    || rule.doData != doData || (doData && rule.dataOn != dataOnNew)
                    || rule.doAirplane != doAir || (doAir && rule.airplaneOn != airOnNew);
            if (actionChanged) {
                com.autonetswitch.app.rules.RuleEngine.get()
                        .revertIfActive(rule, "规则动作已修改，回滚");
            }
        }

        rule.name = name;
        rule.type = type;
        rule.ssid = ssid;
        rule.ssidEnter = spSsidMode.getSelectedItemPosition() == 0;
        int dIdx = spDelay.getSelectedItemPosition();
        if (dIdx < 0 || dIdx >= delayValues.length) {
            dIdx = 2;
        }
        rule.delaySec = delayValues[dIdx];
        rule.startHour = startHour;
        rule.startMinute = startMinute;
        rule.endHour = endHour;
        rule.endMinute = endMinute;
        rule.daysMask = mask;

        rule.doWifi = doWifi;
        rule.wifiOn = spWifiOn.getSelectedItemPosition() == 0;
        rule.doData = doData;
        rule.dataOn = spDataOn.getSelectedItemPosition() == 0;
        rule.doAirplane = doAir;
        rule.airplaneOn = spAirOn.getSelectedItemPosition() == 0;

        int cIdx = spCooldown.getSelectedItemPosition();
        if (cIdx < 0 || cIdx >= cooldownValues.length) {
            cIdx = 2;
        }
        rule.cooldownMin = cooldownValues[cIdx];
        rule.autoRevert = swAutoRevert.isChecked();

        int rIdx = spRssi.getSelectedItemPosition();
        if (rIdx < 0 || rIdx >= rssiValues.length) {
            rIdx = 3;
        }
        rule.rssiThreshold = rssiValues[rIdx];
        int hIdx = spHold.getSelectedItemPosition();
        if (hIdx < 0 || hIdx >= holdValues.length) {
            hIdx = 2;
        }
        rule.weakHoldSec = holdValues[hIdx];

        App.get().rules().save(rule);
        com.autonetswitch.app.util.AppLog.append("保存规则「" + rule.name + "」");
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.confirm_delete, rule.name))
                .setPositiveButton(R.string.btn_delete, (d, w) -> {
                    // M8：删除活动中的规则前先回滚已执行动作
                    com.autonetswitch.app.rules.RuleEngine.get().revertIfActive(rule, "规则已删除，回滚");
                    App.get().rules().remove(rule.id);
                    com.autonetswitch.app.util.AppLog.append("删除规则「" + rule.name + "」");
                    Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
