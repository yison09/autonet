package com.autonetswitch.app.store;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class RuleStore {

    private final Context ctx;
    private final List<Rule> rules = new ArrayList<>();
    private final Object lock = new Object();

    public RuleStore(Context c) {
        ctx = c.getApplicationContext();
        load();
    }

    private SharedPreferences sp() {
        return ctx.getSharedPreferences("autonet_rules", Context.MODE_PRIVATE);
    }

    private void load() {
        synchronized (lock) {
            rules.clear();
            String raw = sp().getString("rules", "[]");
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    Rule r = Rule.fromJson(arr.optJSONObject(i));
                    if (r != null) {
                        rules.add(r);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        synchronized (lock) {
            for (Rule r : rules) {
                arr.put(r.toJson());
            }
        }
        sp().edit().putString("rules", arr.toString()).apply();
    }

    public List<Rule> list() {
        synchronized (lock) {
            return new ArrayList<>(rules);
        }
    }

    public Rule get(String id) {
        synchronized (lock) {
            for (Rule r : rules) {
                if (r.id.equals(id)) {
                    return r;
                }
            }
        }
        return null;
    }

    public void save(Rule rule) {
        if (rule == null || rule.id == null) {
            return;
        }
        synchronized (lock) {
            boolean found = false;
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).id.equals(rule.id)) {
                    rules.set(i, rule);
                    found = true;
                    break;
                }
            }
            if (!found) {
                rules.add(rule);
            }
        }
        persist();
    }

    public void remove(String id) {
        synchronized (lock) {
            java.util.Iterator<Rule> it = rules.iterator();
            while (it.hasNext()) {
                if (it.next().id.equals(id)) {
                    it.remove();
                }
            }
        }
        persist();
    }

    public int enabledCount() {
        synchronized (lock) {
            int n = 0;
            for (Rule r : rules) {
                if (r.enabled) {
                    n++;
                }
            }
            return n;
        }
    }

    public boolean anyEnabled() {
        return enabledCount() > 0;
    }
}
