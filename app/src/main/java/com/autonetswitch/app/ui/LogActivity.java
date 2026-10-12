package com.autonetswitch.app.ui;

import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.autonetswitch.app.R;
import com.autonetswitch.app.util.AppLog;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

public class LogActivity extends AppCompatActivity {

    private TextView txtLog;
    private ScrollView scroll;

    private final AppLog.Listener liveListener = line ->
            runOnUiThread(this::load);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_log);
        txtLog = findViewById(R.id.txtLog);
        scroll = findViewById(R.id.scrollLog);

        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_refresh) {
                load();
                return true;
            }
            if (id == R.id.action_clear) {
                AppLog.clear();
                load();
                return true;
            }
            return false;
        });

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.addListener(liveListener);
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppLog.removeListener(liveListener);
    }

    private void load() {
        List<String> lines = AppLog.tail(300);
        if (lines.isEmpty()) {
            txtLog.setText(R.string.log_empty);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        txtLog.setText(sb.toString().trim());
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
