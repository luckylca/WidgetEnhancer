package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
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
    private static final int REQUEST_IMPORT_WIDGET = 3101;
    private WidgetRepository repository;
    private LinearLayout widgetList;
    private TextView countText;

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
    }

    private View createContent() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(104));
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

        LinearLayout sectionTitle = horizontal();
        TextView mine = text("我的小部件", 21,
                color(com.google.android.material.R.attr.colorOnSurface));
        mine.setTypeface(null, android.graphics.Typeface.BOLD);
        sectionTitle.addView(mine, weighted());
        countText = text("0 个", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        sectionTitle.addView(countText);
        root.addView(sectionTitle, marginTop(dp(28)));

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

        FrameLayout.LayoutParams navigationParams = new FrameLayout.LayoutParams(
                -1, dp(80), Gravity.BOTTOM);
        page.addView(AppNavigation.create(this, AppNavigation.WIDGETS), navigationParams);

        return page;
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

    private void edit(String id) {
        startActivity(new Intent(this, WidgetEditorActivity.class)
                .putExtra(Contract.EXTRA_WIDGET_ID, id));
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
