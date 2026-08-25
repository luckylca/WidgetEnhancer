package com.lucky.mixflipouter;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;

public final class AboutActivity extends Activity {
    private static final int REQUEST_CAMERA_FOR_TORCH = 2101;
    private static final String REPOSITORY_URL = "https://github.com/luckylca/WidgetEnhancer";

    private WidgetRepository repository;
    private TextView moduleStatus;
    private TextView mediaAccessStatus;
    private TextView cameraStatus;
    private TextView dndStatus;
    private TextView writeSettingsStatus;
    private TextView quickSettingsStatus;
    private TextView lsposedStatus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = new WidgetRepository(this);
        setContentView(createContent());
        SystemBars.apply(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View createContent() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = vertical(0);
        root.setPadding(dp(20), dp(18), dp(20), dp(104));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView title = text("关于", 30,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        root.addView(text("状态、权限与维护", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant)), marginTop(dp(2)));

        LinearLayout identity = horizontal();
        identity.setPadding(dp(4), dp(18), dp(4), dp(8));
        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.ic_launcher);
        appIcon.setContentDescription("应用图标");
        identity.addView(appIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout appCopy = vertical(0);
        LinearLayout.LayoutParams appCopyParams = weighted();
        appCopyParams.setMarginStart(dp(14));
        identity.addView(appCopy, appCopyParams);
        TextView appName = text("MIX Flip 外屏扩展", 19,
                color(com.google.android.material.R.attr.colorOnSurface));
        appName.setTypeface(null, android.graphics.Typeface.BOLD);
        appCopy.addView(appName);
        appCopy.addView(text(versionLabel(), 13,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant)), marginTop(dp(2)));
        root.addView(identity);

        root.addView(sectionTitle("模块"), marginTop(dp(20)));
        LinearLayout moduleGroup = addGroup(root);
        moduleStatus = addRow(moduleGroup, "运行状态", "正在检查…", null, false);
        addDivider(moduleGroup);
        addSafeModeRow(moduleGroup);

        root.addView(sectionTitle("权限与系统访问"), marginTop(dp(22)));
        LinearLayout permissions = addGroup(root);
        mediaAccessStatus = addRow(permissions, "媒体会话访问", "正在检查…",
                v -> startSafely(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)), true);
        addDivider(permissions);
        cameraStatus = addRow(permissions, "相机与手电筒", "正在检查…",
                v -> requestCameraAccess(), true);
        addDivider(permissions);
        dndStatus = addRow(permissions, "勿扰模式访问", "正在检查…",
                v -> startSafely(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)), true);
        addDivider(permissions);
        writeSettingsStatus = addRow(permissions, "修改系统设置", "正在检查…",
                v -> startSafely(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()))), true);
        addDivider(permissions);
        quickSettingsStatus = addRow(permissions, "高级磁贴适配器", "正在检查…",
                v -> startActivity(new Intent(this, QSTilePickerActivity.class)), true);
        addDivider(permissions);
        lsposedStatus = addRow(permissions, "LSPosed", "正在检查…",
                v -> openLsposed(), true);

        root.addView(sectionTitle("维护"), marginTop(dp(22)));
        LinearLayout maintenance = addGroup(root);
        addRow(maintenance, "详细诊断", "Hook、媒体、歌词与数据状态",
                v -> startActivity(new Intent(this, DiagnosticsActivity.class)), true);
        addDivider(maintenance);
        addRow(maintenance, "系统小部件管理", "打开 FlipHome 设置",
                v -> openOfficialWidgets(), true);

        root.addView(sectionTitle("项目"), marginTop(dp(22)));
        LinearLayout project = addGroup(root);
        addRow(project, "GitHub", "luckylca/WidgetEnhancer",
                v -> startSafely(new Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL))), true);

        FrameLayout.LayoutParams navigationParams = new FrameLayout.LayoutParams(
                -1, dp(80), Gravity.BOTTOM);
        page.addView(AppNavigation.create(this, AppNavigation.ABOUT), navigationParams);
        return page;
    }

    private LinearLayout addGroup(LinearLayout parent) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(color(
                com.google.android.material.R.attr.colorSurfaceContainerLow));
        card.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setAllCornerSizes(dp(8)).build());
        LinearLayout group = vertical(0);
        card.addView(group);
        parent.addView(card, marginTop(dp(8)));
        return group;
    }

    private TextView addRow(LinearLayout group, String titleValue, String detailValue,
                            View.OnClickListener listener, boolean showArrow) {
        LinearLayout row = horizontal();
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(9), dp(12), dp(9));
        if (listener != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(listener);
        }
        LinearLayout copy = vertical(0);
        row.addView(copy, weighted());
        TextView title = text(titleValue, 15,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        copy.addView(title);
        TextView detail = text(detailValue, 13,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        copy.addView(detail, marginTop(dp(2)));
        if (showArrow) {
            ImageView arrow = new ImageView(this);
            arrow.setImageResource(R.drawable.ic_chevron_right_24);
            arrow.setColorFilter(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(22), dp(22));
            arrowParams.setMarginStart(dp(10));
            row.addView(arrow, arrowParams);
        }
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return detail;
    }

    private void addSafeModeRow(LinearLayout group) {
        LinearLayout row = horizontal();
        row.setMinimumHeight(dp(68));
        row.setPadding(dp(16), dp(8), dp(12), dp(8));
        LinearLayout copy = vertical(0);
        row.addView(copy, weighted());
        TextView title = text("安全模式", 15,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        copy.addView(title);
        copy.addView(text("临时停用自定义外屏页面", 13,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant)), marginTop(dp(2)));
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setChecked(repository.isSafeMode());
        toggle.setContentDescription("安全模式");
        toggle.setOnCheckedChangeListener((button, checked) -> {
            repository.setSafeMode(checked);
            Toast.makeText(this, checked ? "安全模式已开启" : "安全模式已关闭",
                    Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addDivider(LinearLayout group) {
        View divider = new View(this);
        divider.setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMarginStart(dp(16));
        group.addView(divider, params);
    }

    private void refreshStatus() {
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        NotificationManager manager = getSystemService(NotificationManager.class);
        boolean dnd = manager != null && manager.isNotificationPolicyAccessGranted();
        boolean media = NotificationManagerCompat.getEnabledListenerPackages(this)
                .contains(getPackageName());
        setStatus(mediaAccessStatus, media, "已授权", "需要授权");
        setStatus(cameraStatus, camera, "已授权", "需要授权");
        setStatus(dndStatus, dnd, "已授权", "需要授权");
        boolean canWrite = Settings.System.canWrite(this);
        setStatus(writeSettingsStatus, canWrite, "已授权", "需要授权");
        Intent lsposed = lsposedLaunchIntent();
        setStatus(lsposedStatus, lsposed != null, "管理器已安装", "未检测到管理器");

        try {
            Bundle health = getContentResolver().call(
                    Contract.PROVIDER_URI, "get_health", null, null);
            boolean compatibility = health != null && health.getBoolean("compatibility_ok");
            boolean catalogue = health != null && health.getBoolean("catalogue_ok");
            boolean qs = health != null && health.getBoolean("qs_ok");
            boolean ready = compatibility && catalogue && !repository.isSafeMode();
            moduleStatus.setText(repository.isSafeMode()
                    ? "安全模式已开启"
                    : ready ? "模块运行正常" : "等待 FlipHome 连接");
            moduleStatus.setTextColor(color(ready
                    ? androidx.appcompat.R.attr.colorPrimary
                    : com.google.android.material.R.attr.colorOnSurfaceVariant));
            setStatus(quickSettingsStatus, qs, "桥接已连接", "桥接未连接");
        } catch (Throwable error) {
            moduleStatus.setText(repository.isSafeMode()
                    ? "安全模式已开启" : "模块状态暂不可用");
            setStatus(quickSettingsStatus, false, "桥接已连接", "状态不可用");
        }
    }

    private void setStatus(TextView view, boolean ready, String yes, String no) {
        view.setText(ready ? yes : no);
        view.setTextColor(color(ready
                ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
    }

    private void requestCameraAccess() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "相机与手电筒权限已就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_FOR_TORCH);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_CAMERA_FOR_TORCH) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, granted ? "相机权限已授权" : "手电筒快捷按钮将不可用",
                Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void openOfficialWidgets() {
        startSafely(new Intent().setComponent(new ComponentName(
                Contract.TARGET_PACKAGE,
                "com.miui.fliphome.settings.widget.WidgetSettingsActivity")));
    }

    private void openLsposed() {
        Intent launch = lsposedLaunchIntent();
        if (launch != null) startActivity(launch);
        else startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private Intent lsposedLaunchIntent() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
        return launch != null ? launch
                : getPackageManager().getLaunchIntentForPackage("io.github.lsposed.manager");
    }

    private void startSafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (Throwable error) {
            Toast.makeText(this, "无法打开对应页面", Toast.LENGTH_SHORT).show();
        }
    }

    private String versionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "版本 " + info.versionName + "  ·  构建 " + info.getLongVersionCode();
        } catch (Throwable error) {
            return "版本信息不可用";
        }
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 16,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        return title;
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

    private int color(int attribute) {
        return com.google.android.material.color.MaterialColors.getColor(
                this, attribute, Color.BLACK);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
