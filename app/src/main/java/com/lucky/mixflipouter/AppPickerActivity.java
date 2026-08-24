package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** User-facing launcher activity picker; package/component names never need manual entry. */
public final class AppPickerActivity extends Activity {
    static final String EXTRA_LABEL = "app_label";
    static final String EXTRA_COMPONENT = "app_component";

    private final List<AppEntry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadApps();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), 0);
        page.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));

        TextView title = text("选择要打开的应用", 25,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(title);
        TextView subtitle = text("已按应用名称排序，保存时会记录可启动组件。", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        subtitle.setPadding(0, dp(5), 0, dp(12));
        page.addView(subtitle);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(new AppAdapter());
        list.setOnItemClickListener((parent, view, position, id) -> select(entries.get(position)));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);
    }

    private void loadApps() {
        PackageManager packageManager = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> matches = packageManager.queryIntentActivities(query, 0);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : matches) {
            if (info.activityInfo == null) continue;
            ComponentName component = new ComponentName(
                    info.activityInfo.packageName, info.activityInfo.name);
            if (!seen.add(component.flattenToString())) continue;
            CharSequence label = info.loadLabel(packageManager);
            entries.add(new AppEntry(label == null ? info.activityInfo.packageName : label.toString(),
                    component, info.loadIcon(packageManager)));
        }
        entries.sort(Comparator.comparing(entry -> entry.label,
                java.text.Collator.getInstance(java.util.Locale.getDefault())));
    }

    private void select(AppEntry entry) {
        setResult(RESULT_OK, new Intent()
                .putExtra(EXTRA_LABEL, entry.label)
                .putExtra(EXTRA_COMPONENT, entry.component.flattenToString()));
        finish();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            Row row;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof Row) {
                row = (Row) recycled.getTag();
            } else {
                LinearLayout root = new LinearLayout(AppPickerActivity.this);
                root.setOrientation(LinearLayout.HORIZONTAL);
                root.setGravity(Gravity.CENTER_VERTICAL);
                root.setPadding(dp(12), dp(9), dp(12), dp(9));
                ImageView icon = new ImageView(AppPickerActivity.this);
                root.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
                LinearLayout labels = new LinearLayout(AppPickerActivity.this);
                labels.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1f);
                labelsParams.setMarginStart(dp(14));
                root.addView(labels, labelsParams);
                TextView name = text("", 17,
                        color(com.google.android.material.R.attr.colorOnSurface));
                TextView packageName = text("", 12,
                        color(com.google.android.material.R.attr.colorOnSurfaceVariant));
                labels.addView(name);
                labels.addView(packageName);
                row = new Row(root, icon, name, packageName);
                root.setTag(row);
            }
            AppEntry entry = entries.get(position);
            row.icon.setImageDrawable(entry.icon);
            row.name.setText(entry.label);
            row.packageName.setText(entry.component.getPackageName());
            return row.root;
        }
    }

    private static final class AppEntry {
        final String label;
        final ComponentName component;
        final Drawable icon;

        AppEntry(String label, ComponentName component, Drawable icon) {
            this.label = label;
            this.component = component;
            this.icon = icon;
        }
    }

    private static final class Row {
        final LinearLayout root;
        final ImageView icon;
        final TextView name;
        final TextView packageName;

        Row(LinearLayout root, ImageView icon, TextView name, TextView packageName) {
            this.root = root;
            this.icon = icon;
            this.name = name;
            this.packageName = packageName;
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
