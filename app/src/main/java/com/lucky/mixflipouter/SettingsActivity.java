package com.lucky.mixflipouter;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.io.InputStream;
import java.util.List;

public final class SettingsActivity extends Activity {
    private static final int REQUEST_CAMERA_FOR_TORCH = 2101;
    private static final int REQUEST_IMPORT_WIDGET = 3101;
    private WidgetRepository repository;
    private LinearLayout widgetList;
    private TextView countText;
    private TextView hookStatus;
    private MaterialSwitch safeModeSwitch;

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
        renderWidgets();
        updateHookStatus();
    }

    private View createContent() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView eyebrow = text("MIX FLIP", 13,
                color(androidx.appcompat.R.attr.colorPrimary));
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(eyebrow);

        TextView title = text("我的外屏", 32,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(2), 0, dp(4));
        root.addView(title);

        TextView subtitle = text("管理外屏小部件", 15,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        root.addView(subtitle);

        root.addView(createSystemCard(), marginTop(dp(20)));

        LinearLayout sectionTitle = horizontal();
        TextView mine = text("我的小部件", 21,
                color(com.google.android.material.R.attr.colorOnSurface));
        mine.setTypeface(null, android.graphics.Typeface.BOLD);
        sectionTitle.addView(mine, weighted());
        countText = text("0 个", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        sectionTitle.addView(countText);
        root.addView(sectionTitle, marginTop(dp(26)));

        LinearLayout libraryActions = horizontal();
        MaterialButton create = outlinedButton("添加小部件", v -> showCreateDialog());
        libraryActions.addView(create, weighted());
        MaterialButton importWidget = outlinedButton("导入文件", v -> beginImport());
        LinearLayout.LayoutParams importParams = weighted();
        importParams.setMarginStart(dp(8));
        libraryActions.addView(importWidget, importParams);
        root.addView(libraryActions, marginTop(dp(12)));

        widgetList = new LinearLayout(this);
        widgetList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = matchWrap();
        listParams.topMargin = dp(12);
        root.addView(widgetList, listParams);

        return page;
    }

    private View createSystemCard() {
        MaterialCardView card = card();
        LinearLayout body = vertical(dp(18));

        TextView title = text("系统连接", 17,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(title);
        hookStatus = text("正在读取 Hook 状态…", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hookStatus.setPadding(0, dp(4), 0, dp(10));
        body.addView(hookStatus);

        safeModeSwitch = new MaterialSwitch(this);
        safeModeSwitch.setText("安全模式（立即停用全部自定义页面）");
        safeModeSwitch.setChecked(repository.isSafeMode());
        safeModeSwitch.setOnCheckedChangeListener((button, checked) -> {
            repository.setSafeMode(checked);
            Toast.makeText(this, checked
                    ? "安全模式已开启，配置仍会保留"
                    : "安全模式已关闭，自定义页面将恢复",
                    Toast.LENGTH_SHORT).show();
            renderWidgets();
        });
        body.addView(safeModeSwitch, matchWrap());

        LinearLayout actions = horizontal();
        MaterialButton permissions = outlinedButton("权限设置", v -> showPermissionDialog());
        actions.addView(permissions, weighted());
        MaterialButton diagnostics = outlinedButton("诊断", v -> startActivity(
                new Intent(this, DiagnosticsActivity.class)));
        LinearLayout.LayoutParams second = weighted();
        second.setMarginStart(dp(8));
        actions.addView(diagnostics, second);
        body.addView(actions, matchWrap());
        body.addView(outlinedButton("打开系统小部件", v -> openOfficialWidgets()), matchWrap());
        card.addView(body);
        return card;
    }

    private void showPermissionDialog() {
        String[] items = {
                "媒体会话访问", "手电筒权限", "勿扰模式访问",
                "修改系统设置", "高级磁贴适配器", "LSPosed"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("权限设置")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } else if (which == 1) {
                        if (checkSelfPermission(Manifest.permission.CAMERA)
                                != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{Manifest.permission.CAMERA},
                                    REQUEST_CAMERA_FOR_TORCH);
                        } else {
                            Toast.makeText(this, "手电筒权限已就绪", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 2) {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    } else if (which == 3) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } else if (which == 4) {
                        startActivity(new Intent(this, QSTilePickerActivity.class));
                    } else {
                        openLsposed();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_CAMERA_FOR_TORCH) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted
                    ? "手电筒按钮已可用"
                    : "未授权时手电筒按钮会显示不支持提示", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderWidgets() {
        List<WidgetConfig> widgets = repository.list();
        countText.setText(widgets.size() + " 个");
        widgetList.removeAllViews();
        for (WidgetConfig config : widgets) widgetList.addView(createWidgetCard(config), cardMargin());
    }

    private View createWidgetCard(WidgetConfig config) {
        MaterialCardView card = card();
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> edit(config.id));

        LinearLayout body = vertical(dp(14));
        LinearLayout summary = horizontal();

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_XY);
        preview.setBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceVariant));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(84), dp(138));
        summary.addView(preview, previewParams);
        loadPreview(preview, config.id, repository.revision());

        LinearLayout details = vertical(0);
        LinearLayout.LayoutParams detailsParams = weighted();
        detailsParams.setMarginStart(dp(16));
        summary.addView(details, detailsParams);

        TextView name = text(config.name, 19,
                color(com.google.android.material.R.attr.colorOnSurface));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setMaxLines(2);
        details.addView(name);

        TextView media = text(typeLabel(config), 14,
                color(androidx.appcompat.R.attr.colorPrimary));
        media.setPadding(0, dp(5), 0, 0);
        details.addView(media);

        TextView state = text(config.enabled ? "已启用 · 可在系统中添加" : "已停用 · 不显示在系统列表",
                13, color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        state.setPadding(0, dp(6), 0, dp(6));
        details.addView(state);

        MaterialSwitch enabled = new MaterialSwitch(this);
        enabled.setText(config.enabled ? "启用" : "停用");
        enabled.setChecked(config.enabled);
        enabled.setOnClickListener(v -> {
            config.enabled = enabled.isChecked();
            repository.save(config);
            renderWidgets();
        });
        details.addView(enabled, matchWrap());
        body.addView(summary, matchWrap());

        LinearLayout actions = horizontal();
        MaterialButton edit = textButton("编辑", v -> edit(config.id));
        actions.addView(edit, weighted());
        MaterialButton delete = textButton("删除", v -> confirmDelete(config));
        delete.setTextColor(color(android.R.attr.colorError));
        actions.addView(delete, weighted());
        body.addView(actions, marginTop(dp(8)));
        card.addView(body);
        return card;
    }

    private void loadPreview(ImageView image, String widgetId, long revision) {
        new Thread(() -> {
            Bitmap bitmap = null;
            try (InputStream input = getContentResolver().openInputStream(
                    Contract.previewUri(widgetId, revision))) {
                bitmap = BitmapFactory.decodeStream(input);
            } catch (Throwable ignored) {
            }
            Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (!isDestroyed() && result != null) image.setImageBitmap(result);
            });
        }, "preview-" + widgetId).start();
    }

    private void showCreateDialog() {
        List<WidgetTypeRegistry.Type> types = WidgetTypeRegistry.all();
        String[] labels = new String[types.size()];
        for (int i = 0; i < types.size(); i++) labels[i] = types.get(i).name;
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择小部件类型")
                .setItems(labels, (dialog, which) -> {
                    WidgetTypeRegistry.Type selected = types.get(which);
                    WidgetConfig draft = WidgetTypeRegistry.create(selected.id);
                    WidgetConfig created = repository.createFromTemplate(draft);
                    edit(created.id);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void beginImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMPORT_WIDGET);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT_WIDGET) {
            Toast.makeText(this, "正在校验并导入…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    WidgetConfig imported = WidgetPackage.importWidget(this, repository, uri);
                    runOnUiThread(() -> {
                        renderWidgets();
                        Toast.makeText(this, "已导入“" + imported.name + "”", Toast.LENGTH_SHORT).show();
                        edit(imported.id);
                    });
                } catch (Throwable error) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "导入失败：" + safeMessage(error), Toast.LENGTH_LONG).show());
                }
            }, "widget-import").start();
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private void confirmDelete(WidgetConfig config) {
        if (repository.list().size() <= 1) {
            Toast.makeText(this, "至少保留一个小部件", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除“" + config.name + "”？")
                .setMessage("配置和已复制到模块中的媒体都会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (repository.delete(config.id)) renderWidgets();
                })
                .show();
    }

    private void updateHookStatus() {
        try {
            Bundle health = getContentResolver().call(
                    Contract.PROVIDER_URI, "get_health", null, null);
            boolean catalogue = health != null && health.getBoolean("catalogue_ok");
            boolean runtime = health != null && health.getBoolean("runtime_ok");
            boolean compatibility = health != null && health.getBoolean("compatibility_ok");
            boolean liveRefresh = health != null && health.getBoolean("live_refresh_ok");
            boolean lyrics = health != null && health.getBoolean("lyrics_ok");
            boolean qs = health != null && health.getBoolean("qs_ok");
            hookStatus.setText((compatibility ? "✓" : "○") + " 兼容检查    "
                    + (catalogue ? "✓" : "○") + " 系统列表\n"
                    + (runtime ? "✓" : "○") + " 外屏运行时    "
                    + (liveRefresh ? "✓" : "○") + " 即时刷新\n"
                    + (lyrics ? "✓" : "○") + " 网易云歌词适配    "
                    + (qs ? "✓" : "○") + " 高级磁贴适配");
            hookStatus.setTextColor(color(catalogue
                    ? androidx.appcompat.R.attr.colorPrimary
                    : com.google.android.material.R.attr.colorOnSurfaceVariant));
        } catch (Throwable error) {
            hookStatus.setText("尚未收到 FlipHome Hook 状态");
        }
    }

    private void edit(String id) {
        startActivity(new Intent(this, WidgetEditorActivity.class)
                .putExtra(Contract.EXTRA_WIDGET_ID, id));
    }

    private void openOfficialWidgets() {
        try {
            Intent intent = new Intent().setComponent(new ComponentName(
                    Contract.TARGET_PACKAGE,
                    "com.miui.fliphome.settings.widget.WidgetSettingsActivity"));
            startActivity(intent);
        } catch (Throwable error) {
            Toast.makeText(this, "无法打开系统小部件页面", Toast.LENGTH_SHORT).show();
        }
    }

    private void openLsposed() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("io.github.lsposed.manager");
        if (launch != null) startActivity(launch);
        else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private String typeLabel(WidgetConfig config) {
        WidgetTypeRegistry.Type type = WidgetTypeRegistry.get(WidgetTypeRegistry.resolve(config));
        return type == null ? "小部件" : type.name;
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(color(com.google.android.material.R.attr.colorOutlineVariant));
        card.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow));
        card.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setAllCornerSizes(dp(24)).build());
        return card;
    }

    private MaterialButton outlinedButton(String value, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(value);
        button.setOnClickListener(listener);
        return button;
    }

    private MaterialButton textButton(String value, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this, null,
                android.R.attr.borderlessButtonStyle);
        button.setText(value);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private LinearLayout.LayoutParams marginTop(int margin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = margin;
        return params;
    }

    private LinearLayout.LayoutParams cardMargin() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(12);
        return params;
    }

    private int color(int attribute) {
        return com.google.android.material.color.MaterialColors.getColor(this, attribute, Color.BLACK);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
