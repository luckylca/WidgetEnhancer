package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.ShapeAppearanceModel;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DiagnosticsActivity extends Activity {
    private static final int REQUEST_EXPORT = 4101;
    private static final String EXTRA_SCROLL_BOTTOM = "debug_scroll_bottom";
    private static final String STATE_REPORT = "report_json";
    private static final String STATE_PENDING_EXPORT = "pending_export_json";
    private WidgetRepository repository;
    private TextView status;
    private LinearLayout sections;
    private MaterialButton export;
    private ScrollView scroll;
    private JSONObject currentReport;
    private String pendingExportJson;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = new WidgetRepository(this);
        if (state != null) {
            pendingExportJson = state.getString(STATE_PENDING_EXPORT);
            try {
                String saved = state.getString(STATE_REPORT);
                if (saved != null) currentReport = new JSONObject(saved);
            } catch (Throwable ignored) {
            }
        }
        setContentView(createContent());
        SystemBars.apply(this);
        if (currentReport == null) refresh();
        else render(currentReport);
    }

    private View createContent() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
        LinearLayout root = vertical(0);
        root.setPadding(dp(20), dp(16), dp(20), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        MaterialButton back = textButton("返回", v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = text("诊断", 30,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, marginTop(dp(4)));
        TextView subtitle = text("检查外屏链路，并生成不含用户内容的诊断报告", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        root.addView(subtitle, marginTop(dp(4)));

        LinearLayout actions = horizontal();
        actions.addView(outlinedButton("刷新", v -> refresh()), weighted());
        export = outlinedButton("导出 JSON", v -> beginExport());
        export.setEnabled(currentReport != null);
        LinearLayout.LayoutParams exportParams = weighted();
        exportParams.setMarginStart(dp(8));
        actions.addView(export, exportParams);
        root.addView(actions, marginTop(dp(18)));

        status = text("正在生成诊断快照…", 13,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        root.addView(status, marginTop(dp(12)));
        sections = vertical(0);
        root.addView(sections, marginTop(dp(12)));
        return scroll;
    }

    private void refresh() {
        status.setText("正在生成诊断快照…");
        export.setEnabled(false);
        new Thread(() -> {
            try {
                JSONObject report = DiagnosticReport.collect(this, repository);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    currentReport = report;
                    export.setEnabled(true);
                    render(report);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    status.setText("诊断生成失败：" + safeMessage(error));
                });
            }
        }, "diagnostics-refresh").start();
    }

    private void render(JSONObject report) {
        sections.removeAllViews();
        status.setText("报告格式 v" + report.optInt("schemaVersion", 0)
                + " · " + report.optString("generatedAt", ""));

        JSONObject device = report.optJSONObject("device");
        JSONObject app = report.optJSONObject("app");
        addSection("设备与版本",
                line("模块", value(app, "versionName"), true)
                        + line("设备", value(device, "manufacturer") + " "
                        + value(device, "model"), true)
                        + line("系统", "Android " + intValue(device, "sdk") + " · "
                        + value(device, "buildDisplay"), true)
                        + line("用户数据", bool(device, "userUnlocked") ? "已解锁" : "等待首次解锁",
                        bool(device, "userUnlocked")));

        JSONObject permissions = report.optJSONObject("permissions");
        addSection("权限与服务",
                stateLine("相机 / 手电筒", bool(permissions, "camera"))
                        + stateLine("媒体通知访问", bool(permissions, "notificationListener"))
                        + stateLine("媒体监听已绑定", bool(permissions, "notificationListenerConnected"))
                        + stateLine("勿扰模式访问", bool(permissions, "notificationPolicy"))
                        + stateLine("修改系统设置", bool(permissions, "writeSettings")));

        JSONObject environment = report.optJSONObject("environment");
        addSection("运行环境",
                line("Root", "su_binary_visible".equals(value(environment, "rootIndicator"))
                                ? "应用可见 su" : "应用侧无法判断", true)
                        + stateLine("LSPosed 管理器可见", bool(environment, "lsposedManagerVisible"))
                        + stateLine("LSPosed Hook 上报证据", bool(environment, "lsposedHookEvidence")));

        JSONObject hooks = report.optJSONObject("hooks");
        addSection("Hook 健康",
                stateLine("Provider", bool(hooks, "providerAvailable"))
                        + hookLine(hooks, "compatibility", "兼容检查")
                        + hookLine(hooks, "catalogue", "系统列表")
                        + hookLine(hooks, "runtime", "外屏运行时")
                        + hookLine(hooks, "live_refresh", "即时刷新")
                        + hookLine(hooks, "lyrics", "网易云歌词适配")
                        + hookLine(hooks, "qs", "高级磁贴适配"));

        JSONObject widgets = report.optJSONObject("widgets");
        addSection("Widget 仓库",
                line("数量", intValue(widgets, "enabledCount") + " / "
                        + intValue(widgets, "count") + " 已启用", true)
                        + line("组件", intValue(widgets, "componentCount") + " 个", true)
                        + line("配置架构", "v" + intValue(widgets, "schemaVersion"), true)
                        + line("安全模式", bool(widgets, "safeMode") ? "已开启" : "未开启",
                        !bool(widgets, "safeMode")));

        JSONObject playback = report.optJSONObject("playback");
        JSONObject lyrics = report.optJSONObject("lyrics");
        addSection("媒体与歌词",
                stateLine("活动媒体会话", bool(playback, "sessionAvailable"))
                        + stateLine("专辑封面", bool(playback, "artworkAvailable"))
                        + stateLine("同步歌词", bool(lyrics, "available"))
                        + line("歌词行数", Integer.toString(intValue(lyrics, "lineCount")), true));

        JSONObject qs = report.optJSONObject("quickSettings");
        addSection("可选高级磁贴",
                stateLine("SystemUI 桥接", bool(qs, "bridgeReady"))
                        + line("活动磁贴快照", intValue(qs, "availableTileCount") + " / "
                        + intValue(qs, "tileCount") + " 可用", true));
        if (getIntent().getBooleanExtra(EXTRA_SCROLL_BOTTOM, false)) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void addSection(String titleValue, String detail) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(color(com.google.android.material.R.attr.colorOutlineVariant));
        card.setCardBackgroundColor(color(
                com.google.android.material.R.attr.colorSurfaceContainerLow));
        card.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setAllCornerSizes(dp(8)).build());
        LinearLayout body = vertical(dp(16));
        TextView title = text(titleValue, 17,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(title);
        TextView details = text(detail.trim(), 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        details.setLineSpacing(dp(3), 1f);
        body.addView(details, marginTop(dp(8)));
        card.addView(body);
        sections.addView(card, cardMargin());
    }

    private void beginExport() {
        if (currentReport == null) return;
        try {
            pendingExportJson = currentReport.toString(2);
        } catch (Throwable error) {
            Toast.makeText(this, "无法准备诊断报告：" + safeMessage(error),
                    Toast.LENGTH_LONG).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "mixflip-diagnostics-" + stamp + ".json")
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null
                || pendingExportJson == null) {
            pendingExportJson = null;
            return;
        }
        Uri uri = data.getData();
        String snapshot = pendingExportJson;
        pendingExportJson = null;
        new Thread(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IllegalStateException("无法打开目标文件");
                output.write(snapshot.getBytes(StandardCharsets.UTF_8));
                output.flush();
                runOnUiThread(() -> Toast.makeText(this,
                        "诊断报告已导出", Toast.LENGTH_SHORT).show());
            } catch (Throwable error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导出失败：" + safeMessage(error), Toast.LENGTH_LONG).show());
            }
        }, "diagnostics-export").start();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentReport != null) outState.putString(STATE_REPORT, currentReport.toString());
        outState.putString(STATE_PENDING_EXPORT, pendingExportJson);
    }

    private String hookLine(JSONObject hooks, String key, String label) {
        JSONObject item = hooks == null ? null : hooks.optJSONObject(key);
        return stateLine(label, bool(item, "ok"));
    }

    private static String stateLine(String label, boolean ok) {
        return line(label, ok ? "就绪" : "未就绪", ok);
    }

    private static String line(String label, String value, boolean ok) {
        return (ok ? "✓ " : "○ ") + label + "：" + value + "\n";
    }

    private static boolean bool(JSONObject object, String key) {
        return object != null && object.optBoolean(key, false);
    }

    private static int intValue(JSONObject object, String key) {
        return object == null ? 0 : object.optInt(key, 0);
    }

    private static String value(JSONObject object, String key) {
        return object == null ? "" : object.optString(key, "");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private MaterialButton outlinedButton(String value, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(value);
        button.setOnClickListener(listener);
        return button;
    }

    private MaterialButton textButton(String value, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(value);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, float size, int textColor) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(textColor);
        return view;
    }

    private LinearLayout vertical(int padding) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(padding, padding, padding, padding);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private LinearLayout.LayoutParams marginTop(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = value;
        return params;
    }

    private LinearLayout.LayoutParams cardMargin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        return params;
    }

    private int color(int attribute) {
        return com.google.android.material.color.MaterialColors.getColor(
                this, attribute, Color.BLACK);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
