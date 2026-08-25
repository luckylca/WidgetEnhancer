package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lists real active SystemUI tiles and installed-but-not-active TileService capabilities. */
public final class QSTilePickerActivity extends Activity {
    static final String EXTRA_LABEL = "tile_label";
    static final String EXTRA_SPEC = "tile_spec";
    private final List<TileEntry> entries = new ArrayList<>();
    private boolean bridgeReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadTiles();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), 0);
        page.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));

        TextView title = text("选择快捷设置磁贴", 25,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(title);
        TextView subtitle = text(bridgeReady
                        ? "可选择已加入系统控制中心且当前可用的磁贴。"
                        : "QS 桥接尚未连接；请在 LSPosed 勾选“系统界面”并重启 SystemUI。",
                14, color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        subtitle.setPadding(0, dp(5), 0, dp(12));
        page.addView(subtitle);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(new TileAdapter());
        list.setOnItemClickListener((parent, view, position, id) -> select(entries.get(position)));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);
        SystemBars.apply(this);
    }

    private void loadTiles() {
        Map<String, TileEntry> merged = new LinkedHashMap<>();
        try {
            Bundle snapshot = getContentResolver().call(
                    Contract.PROVIDER_URI, "get_qs_tiles", null, null);
            bridgeReady = snapshot != null && snapshot.getBoolean("bridge_ready");
            JSONArray active = new JSONArray(snapshot == null
                    ? "[]" : snapshot.getString("tiles_json", "[]"));
            for (int i = 0; i < active.length(); i++) {
                JSONObject item = active.optJSONObject(i);
                if (item == null) continue;
                String spec = item.optString("spec", "");
                if (spec.isEmpty()) continue;
                merged.put(spec, new TileEntry(
                        item.optString("label", spec), spec, null,
                        true, bridgeReady && item.optBoolean("available", false),
                        item.optInt("state", 0), item.optBoolean("custom", false)));
            }
        } catch (Throwable ignored) {
            bridgeReady = false;
        }

        PackageManager pm = getPackageManager();
        Intent query = new Intent(TileService.ACTION_QS_TILE);
        for (ResolveInfo info : pm.queryIntentServices(query, PackageManager.GET_META_DATA)) {
            if (info.serviceInfo == null) continue;
            ComponentName component = new ComponentName(
                    info.serviceInfo.packageName, info.serviceInfo.name);
            String spec = "custom(" + component.flattenToShortString() + ")";
            CharSequence labelValue = info.loadLabel(pm);
            String label = labelValue == null ? component.getPackageName() : labelValue.toString();
            Drawable icon = info.loadIcon(pm);
            TileEntry current = merged.get(spec);
            if (current == null) {
                merged.put(spec, new TileEntry(label, spec, icon,
                        false, false, 0, true));
            } else {
                current.icon = icon;
                if (current.label.equals(current.spec)) current.label = label;
            }
        }
        entries.addAll(merged.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        entries.sort(Comparator
                .comparing((TileEntry entry) -> !entry.active)
                .thenComparing(entry -> entry.label, collator));
    }

    private void select(TileEntry entry) {
        if (!entry.active) {
            Toast.makeText(this, "请先把该磁贴加入系统控制中心", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!entry.available) {
            Toast.makeText(this, bridgeReady ? "该磁贴当前不可用" : "QS 桥接未连接",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        setResult(RESULT_OK, new Intent()
                .putExtra(EXTRA_LABEL, entry.label)
                .putExtra(EXTRA_SPEC, entry.spec));
        finish();
    }

    private final class TileAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            Row row;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof Row) {
                row = (Row) recycled.getTag();
            } else {
                LinearLayout root = new LinearLayout(QSTilePickerActivity.this);
                root.setOrientation(LinearLayout.HORIZONTAL);
                root.setGravity(Gravity.CENTER_VERTICAL);
                root.setPadding(dp(12), dp(10), dp(12), dp(10));
                ImageView icon = new ImageView(QSTilePickerActivity.this);
                root.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
                LinearLayout labels = new LinearLayout(QSTilePickerActivity.this);
                labels.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1f);
                labelsParams.setMarginStart(dp(14));
                root.addView(labels, labelsParams);
                TextView name = text("", 17,
                        color(com.google.android.material.R.attr.colorOnSurface));
                TextView status = text("", 12,
                        color(com.google.android.material.R.attr.colorOnSurfaceVariant));
                labels.addView(name);
                labels.addView(status);
                row = new Row(root, icon, name, status);
                root.setTag(row);
            }
            TileEntry entry = entries.get(position);
            if (entry.icon == null) row.icon.setImageResource(android.R.drawable.ic_menu_manage);
            else row.icon.setImageDrawable(entry.icon);
            row.name.setText(entry.label);
            row.status.setText(status(entry));
            row.root.setAlpha(entry.active && entry.available ? 1f : 0.5f);
            return row.root;
        }
    }

    private String status(TileEntry entry) {
        if (!entry.active) return "未加入控制中心 · " + entry.spec;
        if (!bridgeReady) return "桥接未连接 · " + entry.spec;
        if (!entry.available) return "当前不可用 · " + entry.spec;
        return (entry.state == 2 ? "已开启" : "已关闭") + " · " + entry.spec;
    }

    private static final class TileEntry {
        String label;
        final String spec;
        Drawable icon;
        final boolean active;
        final boolean available;
        final int state;
        final boolean custom;

        TileEntry(String label, String spec, Drawable icon, boolean active,
                  boolean available, int state, boolean custom) {
            this.label = label;
            this.spec = spec;
            this.icon = icon;
            this.active = active;
            this.available = available;
            this.state = state;
            this.custom = custom;
        }
    }

    private static final class Row {
        final LinearLayout root;
        final ImageView icon;
        final TextView name;
        final TextView status;

        Row(LinearLayout root, ImageView icon, TextView name, TextView status) {
            this.root = root;
            this.icon = icon;
            this.name = name;
            this.status = status;
        }
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int color(int attribute) {
        return com.google.android.material.color.MaterialColors.getColor(this, attribute, Color.BLACK);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
