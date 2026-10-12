package com.autonetswitch.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.autonetswitch.app.R;
import com.autonetswitch.app.store.Rule;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

public class RuleAdapter extends RecyclerView.Adapter<RuleAdapter.VH> {

    public interface Listener {
        void onOpen(Rule r);

        void onToggle(Rule r, boolean enabled);
    }

    private final List<Rule> data = new ArrayList<>();
    private final Listener listener;

    public RuleAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<Rule> rules) {
        data.clear();
        data.addAll(rules);
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return data.get(position).id.hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_rule, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final Rule r = data.get(position);
        holder.name.setText(r.name.isEmpty() ? r.typeLabel() : r.name);
        holder.desc.setText(r.describe());
        holder.actions.setText(r.actionsBrief() + (r.enabled ? "" : " · 已停用"));
        holder.sw.setOnCheckedChangeListener(null);
        holder.sw.setChecked(r.enabled);
        holder.sw.setOnCheckedChangeListener((btn, checked) -> {
            if (listener != null) {
                listener.onToggle(r, checked);
            }
        });
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOpen(r);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView desc;
        final TextView actions;
        final MaterialSwitch sw;

        VH(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.txtRuleName);
            desc = itemView.findViewById(R.id.txtRuleDesc);
            actions = itemView.findViewById(R.id.txtRuleActions);
            sw = itemView.findViewById(R.id.swRuleEnabled);
        }
    }
}
