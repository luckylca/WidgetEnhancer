package com.lucky.mixflipouter;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.pm.PackageManager;
import android.content.Intent;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-level AppWidget picker: pick an app, then one of its widgets. The grid
 * size is derived from the provider's declared minimum size, so a single tap
 * adds the widget.
 */
public final class AppWidgetPickerActivity extends Activity {
    static final String EXTRA_PROVIDER = "appwidget_provider";
    static final String EXTRA_LABEL = "appwidget_label";
    static final String EXTRA_COLS = "appwidget_cols";
    static final String EXTRA_ROWS = "appwidget_rows";

    private final List<AppGroup> groups = new ArrayList<>();
    private LinearLayout page;
    private TextView title;
    private TextView subtitle;
    private ListView list;
    private AppGroup expanded;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadProviders();

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), 0);
        page.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));

        title = text("选择应用", 25,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(title);
        subtitle = text("", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        subtitle.setPadding(0, dp(5), 0, dp(12));
        page.addView(subtitle);

        list = new ListView(this);
        list.setDivider(null);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);
        SystemBars.apply(this);
        showApps();
    }

    private void loadProviders() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        PackageManager packageManager = getPackageManager();
        Map<String, AppGroup> byPackage = new LinkedHashMap<>();
        for (AppWidgetProviderInfo info : manager.getInstalledProviders()) {
            if (info == null || info.provider == null) continue;
            String packageName = info.provider.getPackageName();
            AppGroup group = byPackage.get(packageName);
            if (group == null) {
                group = new AppGroup(packageName, appLabel(packageManager, packageName),
                        appIcon(packageManager, packageName));
                byPackage.put(packageName, group);
            }
            group.widgets.add(info);
        }
        groups.addAll(byPackage.values());
        groups.sort(Comparator.comparing(group -> group.label,
                java.text.Collator.getInstance(java.util.Locale.getDefault())));
    }

    private void showApps() {
        expanded = null;
        title.setText("选择应用");
        subtitle.setVisibility(View.GONE);
        list.setAdapter(new AppAdapter());
        list.setOnItemClickListener((parent, view, position, id) -> showWidgets(groups.get(position)));
    }

    private void showWidgets(AppGroup group) {
        expanded = group;
        title.setText(group.label);
        subtitle.setVisibility(View.GONE);
        list.setAdapter(new WidgetAdapter(group));
        list.setOnItemClickListener((parent, view, position, id) -> select(group.widgets.get(position)));
        list.setSelection(0);
    }

    @Override
    public void onBackPressed() {
        if (expanded != null) {
            showApps();
            return;
        }
        super.onBackPressed();
    }

    private void select(AppWidgetProviderInfo info) {
        float density = getResources().getDisplayMetrics().density;
        int[] size = AppWidgetLayoutEngine.autoSize(info.minWidth, info.minHeight, density);
        String label = info.loadLabel(getPackageManager());
        setResult(RESULT_OK, new Intent()
                .putExtra(EXTRA_PROVIDER, info.provider.flattenToString())
                .putExtra(EXTRA_LABEL, label == null ? "" : label)
                .putExtra(EXTRA_COLS, size[0])
                .putExtra(EXTRA_ROWS, size[1]));
        finish();
    }

    private String appLabel(PackageManager packageManager, String packageName) {
        try {
            CharSequence label = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0));
            return label == null ? packageName : label.toString();
        } catch (Throwable ignored) {
            return packageName;
        }
    }

    private Drawable appIcon(PackageManager packageManager, String packageName) {
        try {
            return packageManager.getApplicationIcon(packageName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return groups.size(); }
        @Override public Object getItem(int position) { return groups.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            Row row = Row.of(recycled, AppWidgetPickerActivity.this);
            AppGroup group = groups.get(position);
            row.icon.setImageDrawable(group.icon);
            row.name.setText(group.label);
            row.detail.setText(group.widgets.size() + " 个小部件");
            return row.root;
        }
    }

    private final class WidgetAdapter extends BaseAdapter {
        private final AppGroup group;

        WidgetAdapter(AppGroup group) {
            this.group = group;
        }

        @Override public int getCount() { return group.widgets.size(); }
        @Override public Object getItem(int position) { return group.widgets.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            Row row = Row.of(recycled, AppWidgetPickerActivity.this);
            AppWidgetProviderInfo info = group.widgets.get(position);
            Drawable preview = info.loadPreviewImage(AppWidgetPickerActivity.this, 0);
            row.icon.setImageDrawable(preview != null ? preview
                    : info.loadIcon(AppWidgetPickerActivity.this, 0));
            String label = info.loadLabel(getPackageManager());
            row.name.setText(label == null || label.isEmpty()
                    ? info.provider.getClassName() : label);
            float density = getResources().getDisplayMetrics().density;
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int[] natural = AppWidgetLayoutEngine.naturalSizeDp(info.minWidth, info.minHeight,
                    metrics.density,
                    Math.round(metrics.widthPixels / metrics.density),
                    Math.round(metrics.heightPixels / metrics.density));
            int targetW = android.os.Build.VERSION.SDK_INT >= 31 ? info.targetCellWidth : 0;
            int targetH = android.os.Build.VERSION.SDK_INT >= 31 ? info.targetCellHeight : 0;
            int cellsW = AppWidgetLayoutEngine.naturalCellsWide(targetW,
                    natural == null ? -1 : natural[0]);
            int cellsH = AppWidgetLayoutEngine.naturalCellsWide(targetH,
                    natural == null ? -1 : natural[1]);
            int[] size = AppWidgetLayoutEngine.autoSize(info.minWidth, info.minHeight, density);
            row.detail.setText(cellsW + " × " + cellsH
                    + " · 建议占 " + size[0] + " × " + size[1] + " 格");
            return row.root;
        }
    }

    private static final class AppGroup {
        final String packageName;
        final String label;
        final Drawable icon;
        final List<AppWidgetProviderInfo> widgets = new ArrayList<>();

        AppGroup(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final class Row {
        final LinearLayout root;
        final ImageView icon;
        final TextView name;
        final TextView detail;

        Row(LinearLayout root, ImageView icon, TextView name, TextView detail) {
            this.root = root;
            this.icon = icon;
            this.name = name;
            this.detail = detail;
        }

        static Row of(View recycled, AppWidgetPickerActivity activity) {
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof Row) {
                return (Row) recycled.getTag();
            }
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(activity.dp(12), activity.dp(9), activity.dp(12), activity.dp(9));
            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(icon, new LinearLayout.LayoutParams(
                    activity.dp(72), activity.dp(48)));
            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1f);
            labelsParams.setMarginStart(activity.dp(14));
            root.addView(labels, labelsParams);
            TextView name = activity.text("", 17,
                    activity.color(com.google.android.material.R.attr.colorOnSurface));
            TextView detail = activity.text("", 12,
                    activity.color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            labels.addView(name);
            labels.addView(detail);
            Row row = new Row(root, icon, name, detail);
            root.setTag(row);
            return row;
        }
    }

    private TextView text(String value, float size, int textColor) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(textColor);
        return view;
    }

    private int color(int attribute) {
        return com.google.android.material.color.MaterialColors.getColor(this, attribute, Color.BLACK);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
