package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
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
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Type-aware editor that exposes only the options each Widget actually needs. */
public final class WidgetEditorActivity extends Activity {
    static final String EXTRA_DEBUG_SCROLL_Y = "debug_scroll_y";
    static final String EXTRA_DEBUG_TYPE_ID = "debug_type_id";
    static final String EXTRA_DEBUG_NOTIFICATION_COUNT = "debug_notification_count";
    static final String EXTRA_SYSTEM_WIDGET_SELECTION = "system_widget_selection";
    private static final int PICK_IMAGE = 1001;
    private static final int PICK_VIDEO = 1002;
    private static final int PICK_APP = 1003;
    private static final int PICK_QS_TILE = 1004;
    private static final int PICK_APPWIDGET = 1005;
    private static final int PICK_MAML_ZIP = 1006;
    private static final int MAX_SHORTCUTS = ButtonLayoutEngine.MAX_BUTTONS;

    private static final String[] SYSTEM_ACTION_LABELS = {
            "音量增大", "音量减小", "静音切换", "手电筒切换",
            "勿扰模式切换", "自动旋转切换", "锁屏", "系统控制中心磁贴…"
    };
    private static final String[] SYSTEM_ACTION_VALUES = {
            ActionSpec.VOLUME_UP, ActionSpec.VOLUME_DOWN, ActionSpec.MUTE_TOGGLE,
            ActionSpec.FLASHLIGHT_TOGGLE, ActionSpec.DO_NOT_DISTURB_TOGGLE,
            ActionSpec.AUTO_ROTATE_TOGGLE, ActionSpec.LOCK_SCREEN, ActionSpec.QS_TILE
    };

    private final Map<String, EditText> shortcutLabels = new LinkedHashMap<>();
    private WidgetRepository repository;
    private WidgetConfig config;
    private WidgetTypeRegistry.Type type;
    private EditText nameInput;
    private MaterialSwitch enabledSwitch;
    private MaterialSwitch loopSwitch;
    private MaterialSwitch muteSwitch;
    private TextView mediaStatus;
    private MediaCropView mediaPreview;
    private MaterialButton portraitButton;
    private MaterialButton landscapeButton;
    private LinearLayout shortcutList;
    private AppWidgetGridEditorView gridEditor;
    private FrameLayout previewHolder;
    private com.google.android.material.slider.Slider lyricSizeSlider;
    private TextView lyricSizeValue;
    private ScrollView scroll;
    private String pendingShortcutId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = new WidgetRepository(this);
        String debugType = getIntent().getStringExtra(EXTRA_DEBUG_TYPE_ID);
        if (WidgetTypeRegistry.get(debugType) != null) {
            config = WidgetTypeRegistry.create(debugType);
            config.id = "debug-preview";
            if (WidgetTypeRegistry.NOTIFICATIONS.equals(debugType)
                    && getIntent().hasExtra(EXTRA_DEBUG_NOTIFICATION_COUNT)) {
                config.notificationCount = WidgetConfig.clampNotificationCount(
                        getIntent().getIntExtra(EXTRA_DEBUG_NOTIFICATION_COUNT, 3));
            }
            if (WidgetTypeRegistry.SHORTCUTS.equals(debugType)) {
                config.components.add(WidgetComponent.button(
                        "按钮 2", ActionSpec.FLASHLIGHT_TOGGLE, "", 0, 0, 1, 1, 1));
                config.components.add(WidgetComponent.button(
                        "按钮 3", ActionSpec.VOLUME_UP, "", 0, 0, 1, 1, 2));
                WidgetTypeRegistry.buildShortcutLayout(config);
            }
        } else {
            String widgetId = getIntent().getStringExtra(Contract.EXTRA_WIDGET_ID);
            config = repository.get(widgetId == null ? Contract.DEFAULT_WIDGET_ID : widgetId);
            if (config == null) config = repository.list().get(0);
        }
        config.typeId = WidgetTypeRegistry.resolve(config);
        type = WidgetTypeRegistry.get(config.typeId);
        if (type == null) type = WidgetTypeRegistry.get(WidgetTypeRegistry.SHORTCUTS);
        setContentView(createContent());
        SystemBars.apply(this);
        loadValues();
        handleSystemWidgetSelection(getIntent());
        if (getIntent().getBooleanExtra("debug_open_system_widget_picker", false)) {
            scroll.post(this::openSystemWidgetPicker);
        }
        if (getIntent().getBooleanExtra("debug_show_maml_cache_picker", false)) {
            scroll.post(this::showMamlCachePicker);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSystemWidgetSelection(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        consumeSystemWidgetPickerResult();
    }

    private View createContent() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(42));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("编辑小部件", 27,
                color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView typeName = text(type.name, 15,
                color(androidx.appcompat.R.attr.colorPrimary));
        typeName.setTypeface(null, android.graphics.Typeface.BOLD);
        typeName.setPadding(0, dp(5), 0, dp(14));
        root.addView(typeName);

        nameInput = edit("小部件名称");
        root.addView(nameInput, matchWrap());

        enabledSwitch = new MaterialSwitch(this);
        enabledSwitch.setText("在系统外屏列表中启用");
        root.addView(enabledSwitch, matchWrap());

        if (WidgetTypeRegistry.MEDIA.equals(type.id)) {
            createMediaEditor(root);
        } else if (WidgetTypeRegistry.MUSIC.equals(type.id)) {
            createMusicEditor(root);
        } else if (WidgetTypeRegistry.SHORTCUTS.equals(type.id)) {
            createShortcutEditor(root);
        } else if (WidgetTypeRegistry.NOTIFICATIONS.equals(type.id)) {
            createNotificationEditor(root);
        } else if (WidgetTypeRegistry.APPWIDGET.equals(type.id)) {
            createAppWidgetEditor(root);
        } else if (WidgetTypeRegistry.MAML.equals(type.id)) {
            createMamlEditor(root);
        }

        MaterialButton save = button("保存", v -> saveValues());
        save.setTextSize(17);
        LinearLayout.LayoutParams saveParams = matchWrap();
        saveParams.height = dp(54);
        saveParams.topMargin = dp(24);
        root.addView(save, saveParams);

        int debugScrollY = getIntent().getIntExtra(EXTRA_DEBUG_SCROLL_Y, 0);
        if (debugScrollY > 0) scroll.post(() -> scroll.scrollTo(0, debugScrollY));
        return scroll;
    }

    private void createMediaEditor(LinearLayout root) {
        section(root, "媒体");
        mediaPreview = new MediaCropView(this);
        mediaPreview.setComponent(mediaComponent());
        root.addView(mediaPreview, new LinearLayout.LayoutParams(-1, dp(420)));

        section(root, "显示方向");
        MaterialButtonToggleGroup direction = new MaterialButtonToggleGroup(this);
        direction.setSingleSelection(true);
        direction.setSelectionRequired(true);
        portraitButton = outlinedButton("竖屏", v -> setMediaRotation(0));
        portraitButton.setId(View.generateViewId());
        portraitButton.setCheckable(true);
        landscapeButton = outlinedButton("横屏", v -> setMediaRotation(90));
        landscapeButton.setId(View.generateViewId());
        landscapeButton.setCheckable(true);
        direction.addView(portraitButton, weighted());
        direction.addView(landscapeButton, weighted());
        root.addView(direction, matchWrap());
        updateDirectionButtons();
        root.addView(textButton("重置位置与缩放", v -> mediaPreview.resetTransform()), matchWrap());

        mediaStatus = text("尚未选择媒体", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        mediaStatus.setPadding(0, dp(10), 0, dp(8));
        root.addView(mediaStatus);

        LinearLayout actions = horizontal();
        actions.addView(outlinedButton("选择图片", v -> pick("image/*", PICK_IMAGE)), weighted());
        LinearLayout.LayoutParams videoParams = weighted();
        videoParams.setMarginStart(dp(8));
        actions.addView(outlinedButton("选择视频", v -> pick("video/*", PICK_VIDEO)), videoParams);
        root.addView(actions, matchWrap());
        root.addView(textButton("清除媒体", v -> clearMedia()), matchWrap());

        loopSwitch = new MaterialSwitch(this);
        loopSwitch.setText("循环播放视频");
        root.addView(loopSwitch, matchWrap());
        muteSwitch = new MaterialSwitch(this);
        muteSwitch.setText("视频静音");
        root.addView(muteSwitch, matchWrap());
    }

    private void createMusicEditor(LinearLayout root) {
        section(root, "外屏预览");
        addLivePreview(root);

        TextView lyricStatus = text("歌词来源：网易云音乐", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        lyricStatus.setPadding(0, dp(12), 0, 0);
        root.addView(lyricStatus);

        section(root, "歌词大小");
        lyricSizeValue = text("100%", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        root.addView(lyricSizeValue, matchWrap());
        lyricSizeSlider = new com.google.android.material.slider.Slider(this);
        lyricSizeSlider.setValueFrom(0.7f);
        lyricSizeSlider.setValueTo(1.6f);
        lyricSizeSlider.setStepSize(0.1f);
        lyricSizeSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.lyricScale = value;
            lyricSizeValue.setText(Math.round(value * 100) + "%（当前句歌词大小）");
            WidgetTypeRegistry.ensureThreeLineMusicLayout(config);
            scheduleLivePreviewRefresh();
        });
        root.addView(lyricSizeSlider, matchWrap());
    }

    private void scheduleLivePreviewRefresh() {
        if (previewHolder == null) return;
        previewHolder.removeCallbacks(livePreviewRefresh);
        previewHolder.postDelayed(livePreviewRefresh, 200L);
    }

    private final Runnable livePreviewRefresh = this::renderLivePreview;

    private void createShortcutEditor(LinearLayout root) {
        section(root, "外屏预览");
        addLivePreview(root);

        section(root, "快捷按钮");
        shortcutList = new LinearLayout(this);
        shortcutList.setOrientation(LinearLayout.VERTICAL);
        root.addView(shortcutList, matchWrap());
        MaterialButton add = outlinedButton("＋ 添加按钮", v -> addShortcut());
        LinearLayout.LayoutParams addParams = matchWrap();
        addParams.topMargin = dp(10);
        root.addView(add, addParams);
    }

    private void createNotificationEditor(LinearLayout root) {
        section(root, "显示通知数量");
        TextView hint = text("选择最近显示的通知条数", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        root.addView(hint, matchWrap());

        MaterialButtonToggleGroup countSelector = new MaterialButtonToggleGroup(this);
        countSelector.setSingleSelection(true);
        countSelector.setSelectionRequired(true);
        int selectedId = View.NO_ID;
        for (int count = 1; count <= 3; count++) {
            MaterialButton option = outlinedButton(count + " 条", v -> {});
            option.setCheckable(true);
            option.setId(View.generateViewId());
            option.setTag(count);
            countSelector.addView(option, weighted());
            if (count == config.notificationCount) selectedId = option.getId();
        }
        if (selectedId != View.NO_ID) countSelector.check(selectedId);
        countSelector.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            View selected = group.findViewById(checkedId);
            if (selected == null || !(selected.getTag() instanceof Integer)) return;
            int nextCount = (Integer) selected.getTag();
            if (config.notificationCount == nextCount) return;
            config.notificationCount = nextCount;
            renderLivePreview();
        });
        root.addView(countSelector, matchWrap());

        section(root, "外屏预览");
        addLivePreview(root);
    }

    private void createMamlEditor(LinearLayout root) {
        section(root, "预览");
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF17181D);
        root.addView(preview, new LinearLayout.LayoutParams(-1, dp(380)));
        File previewFile = repository.mamlPreviewFile(config.id);
        if (previewFile.isFile()) {
            preview.setImageBitmap(BitmapFactory.decodeFile(previewFile.getAbsolutePath()));
        }
        TextView hint = text("由系统原生渲染，到外屏「小部件」列表里添加即可。", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(0, dp(12), 0, 0);
        root.addView(hint);
    }

    private void createAppWidgetEditor(LinearLayout root) {
        section(root, "外屏格子（按住槽位拖动摆放）");
        gridEditor = new AppWidgetGridEditorView(this);
        gridEditor.setCallback(new AppWidgetGridEditorView.Callback() {
            @Override
            public String slotLabel(WidgetComponent slot) {
                return WidgetEditorActivity.this.slotLabel(slot);
            }

            @Override
            public void onSlotTap(WidgetComponent slot) {
                showSlotActions(slot);
            }

            @Override
            public void onSlotsChanged() {
                refreshAppWidgetGrid();
            }
        });
        root.addView(gridEditor, matchWrap());

        MaterialButton add = outlinedButton("＋ 添加应用小部件", v -> addAppWidgetSlot());
        LinearLayout.LayoutParams addParams = matchWrap();
        addParams.topMargin = dp(14);
        root.addView(add, addParams);
        MaterialButton nativePicker = outlinedButton(
                "从系统桌面小部件面板选择", v -> openSystemWidgetPicker());
        LinearLayout.LayoutParams nativePickerParams = matchWrap();
        nativePickerParams.topMargin = dp(8);
        root.addView(nativePicker, nativePickerParams);
        MaterialButton importZip = outlinedButton("导入 ZIP 到格子", v -> beginImportSlotZip());
        LinearLayout.LayoutParams zipParams = matchWrap();
        zipParams.topMargin = dp(8);
        root.addView(importZip, zipParams);
        MaterialButton importCache = outlinedButton(
                "添加外屏缓存的小部件", v -> showMamlCachePicker());
        LinearLayout.LayoutParams cacheParams = matchWrap();
        cacheParams.topMargin = dp(8);
        root.addView(importCache, cacheParams);

        TextView hint = text("按住槽位拖动摆放，点按可调整尺寸、圆角或删除；首次在外屏显示需按提示授权。", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(0, dp(12), 0, 0);
        root.addView(hint);
    }

    private void showSlotActions(WidgetComponent slot) {
        java.util.ArrayList<String> items = new java.util.ArrayList<>();
        java.util.ArrayList<Runnable> actions = new java.util.ArrayList<>();
        items.add("调整尺寸");
        actions.add(() -> chooseSlotSize(slot));
        items.add("圆角：" + (slot.cornerEnabled ? "开" : "关"));
        actions.add(() -> {
            slot.cornerEnabled = !slot.cornerEnabled;
            showSlotActions(slot);
        });
        items.add("删除");
        actions.add(() -> deleteAppWidgetSlot(slot));
        new MaterialAlertDialogBuilder(this)
                .setTitle(slotLabel(slot))
                .setItems(items.toArray(new String[0]),
                        (dialog, which) -> actions.get(which).run())
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshAppWidgetGrid() {
        if (gridEditor == null) return;
        WidgetTypeRegistry.buildAppWidgetLayout(config);
        gridEditor.setConfig(config);
    }

    private void addLivePreview(LinearLayout root) {
        previewHolder = new FrameLayout(this);
        previewHolder.setBackgroundColor(0xFF59645C);
        root.addView(previewHolder, new LinearLayout.LayoutParams(-1, dp(380)));
    }

    private void addAppWidgetSlot() {
        startActivityForResult(new Intent(this, AppWidgetPickerActivity.class), PICK_APPWIDGET);
    }

    private void openSystemWidgetPicker() {
        if (config == null || !WidgetTypeRegistry.APPWIDGET.equals(type.id)) return;
        config.enabled = enabledSwitch.isChecked();
        config.name = nameInput.getText().toString().trim();
        WidgetTypeRegistry.normalize(config);
        repository.save(config);

        String requestId = java.util.UUID.randomUUID().toString();
        getSharedPreferences(Contract.SYSTEM_WIDGET_PICKER_PREFS, MODE_PRIVATE).edit()
                .putString(Contract.PREF_PENDING_SYSTEM_WIDGET_REQUEST, requestId)
                .putString(Contract.PREF_PENDING_SYSTEM_WIDGET_ID, config.id)
                .putLong(Contract.PREF_PENDING_SYSTEM_WIDGET_AT, System.currentTimeMillis())
                .apply();

        Intent picker = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(Contract.PERSONAL_ASSISTANT_PACKAGE,
                        "com.miui.personalassistant.picker.business.home.pages.PickerHomeActivity"))
                .putExtra("openSource", 2)
                .putExtra("picker_tip_source", 10)
                .putExtra(Contract.EXTRA_SYSTEM_WIDGET_PICK_REQUEST, requestId);
        try {
            startActivity(picker);
        } catch (Throwable error) {
            getSharedPreferences(Contract.SYSTEM_WIDGET_PICKER_PREFS, MODE_PRIVATE).edit()
                    .remove(Contract.PREF_PENDING_SYSTEM_WIDGET_REQUEST)
                    .remove(Contract.PREF_PENDING_SYSTEM_WIDGET_ID)
                    .remove(Contract.PREF_PENDING_SYSTEM_WIDGET_AT)
                    .apply();
            Toast.makeText(this, "无法打开系统桌面小部件面板", Toast.LENGTH_LONG).show();
        }
    }

    private void handleSystemWidgetSelection(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_SYSTEM_WIDGET_SELECTION, false)
                || !intent.hasExtra(Contract.EXTRA_SYSTEM_WIDGET_PROVIDER)) return;
        String provider = intent.getStringExtra(Contract.EXTRA_SYSTEM_WIDGET_PROVIDER);
        int cols = intent.getIntExtra(Contract.EXTRA_SYSTEM_WIDGET_COLS, 2);
        int rows = intent.getIntExtra(Contract.EXTRA_SYSTEM_WIDGET_ROWS, 2);
        intent.removeExtra(Contract.EXTRA_SYSTEM_WIDGET_PROVIDER);
        intent.removeExtra(Contract.EXTRA_SYSTEM_WIDGET_COLS);
        intent.removeExtra(Contract.EXTRA_SYSTEM_WIDGET_ROWS);
        intent.removeExtra(EXTRA_SYSTEM_WIDGET_SELECTION);
        applySystemWidgetSelection(provider, cols, rows);
    }

    private void consumeSystemWidgetPickerResult() {
        android.content.SharedPreferences picker = getSharedPreferences(
                Contract.SYSTEM_WIDGET_PICKER_PREFS, MODE_PRIVATE);
        String widgetId = picker.getString(Contract.PREF_SYSTEM_WIDGET_RESULT_ID, null);
        String kind = picker.getString(Contract.PREF_SYSTEM_WIDGET_RESULT_KIND, null);
        String provider = picker.getString(Contract.PREF_SYSTEM_WIDGET_RESULT_PROVIDER, null);
        if (widgetId == null || kind == null) return;
        String mamlId = picker.getString(Contract.PREF_SYSTEM_WIDGET_RESULT_MAML_ID, null);
        String mamlName = picker.getString(Contract.PREF_SYSTEM_WIDGET_RESULT_MAML_NAME, null);
        int cols = picker.getInt(Contract.PREF_SYSTEM_WIDGET_RESULT_COLS, 2);
        int rows = picker.getInt(Contract.PREF_SYSTEM_WIDGET_RESULT_ROWS, 2);
        clearSystemWidgetPickerResult(picker);
        if (!widgetId.equals(config.id)) return;
        if ("maml".equals(kind)) {
            if (mamlId != null && mamlName != null) {
                importCachedMaml(new MamlCacheStore.Item(mamlId, mamlName));
            }
            return;
        }
        applySystemWidgetSelection(provider, cols, rows);
    }

    private void clearSystemWidgetPickerResult(android.content.SharedPreferences picker) {
        picker.edit().remove(Contract.PREF_SYSTEM_WIDGET_RESULT_ID)
                .remove(Contract.PREF_SYSTEM_WIDGET_RESULT_KIND)
                .remove(Contract.PREF_SYSTEM_WIDGET_RESULT_PROVIDER)
                .remove(Contract.PREF_SYSTEM_WIDGET_RESULT_COLS)
                .remove(Contract.PREF_SYSTEM_WIDGET_RESULT_ROWS)
                .remove(Contract.PREF_SYSTEM_WIDGET_RESULT_MAML_ID)
                .remove(Contract.PREF_SYSTEM_WIDGET_RESULT_MAML_NAME).apply();
    }

    private void applySystemWidgetSelection(String provider, int cols, int rows) {
        if (provider == null || provider.isEmpty()
                || !WidgetTypeRegistry.APPWIDGET.equals(type.id)) return;
        if (!AppWidgetLayoutEngine.validSize(cols, rows)) {
            cols = 2;
            rows = 2;
        }
        config.components.add(WidgetComponent.appWidget(provider, cols, rows));
        refreshAppWidgetGrid();
        Toast.makeText(this, "已添加系统小部件到外屏格子，保存后生效", Toast.LENGTH_LONG).show();
    }

    private void beginImportSlotZip() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), PICK_MAML_ZIP);
    }

    private void showMamlCachePicker() {
        java.util.List<MamlCacheStore.Item> items = MamlCacheStore.list(this);
        if (items.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("外屏缓存小部件")
                    .setMessage("还没有同步到缓存小部件。请确认 LSPosed 已勾选 com.miui.home，打开一次外屏小部件列表并稍等片刻后再试。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView list = new ScrollView(this);
        list.setFillViewport(false);
        list.addView(rows, new ScrollView.LayoutParams(-1, -2));
        androidx.appcompat.app.AlertDialog[] pickerDialog =
                new androidx.appcompat.app.AlertDialog[1];
        for (MamlCacheStore.Item item : items) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));

            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // MAML packages often store transparent thumbnails with dark artwork.
            image.setBackgroundColor(Color.WHITE);
            File previewFile = MamlCacheStore.previewFile(this, item.id);
            if (previewFile.isFile()) {
                Bitmap bitmap = BitmapFactory.decodeFile(previewFile.getAbsolutePath());
                if (bitmap != null) image.setImageBitmap(bitmap);
            }
            row.addView(image, new LinearLayout.LayoutParams(dp(88), dp(104)));

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setGravity(Gravity.CENTER_VERTICAL);
            details.setPadding(dp(12), 0, 0, 0);
            TextView name = text(item.name, 16,
                    color(com.google.android.material.R.attr.colorOnSurface));
            name.setMaxLines(2);
            TextView subtitle = text(previewFile.isFile()
                            ? "外屏缓存 · 点按添加" : "外屏缓存 · 包内无预览图",
                    13, color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            subtitle.setPadding(0, dp(5), 0, 0);
            details.addView(name, matchWrap());
            details.addView(subtitle, matchWrap());
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setContentDescription(item.name + "，外屏缓存小部件");
            row.setOnClickListener(v -> {
                if (pickerDialog[0] != null) pickerDialog[0].dismiss();
                importCachedMaml(item);
            });
            rows.addView(row, matchWrap());
            View divider = new View(this);
            divider.setBackgroundColor(0x22000000);
            rows.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }

        pickerDialog[0] = new MaterialAlertDialogBuilder(this)
                .setTitle("选择外屏缓存小部件")
                .setView(list, dp(8), 0, dp(8), 0)
                .setNegativeButton("取消", null)
                .create();
        pickerDialog[0].show();
    }

    private void importCachedMaml(MamlCacheStore.Item item) {
        WidgetComponent slot = WidgetComponent.mamlSlot(item.name, 2, 2);
        config.components.add(slot);
        refreshAppWidgetGrid();
        Toast.makeText(this, "正在添加缓存小部件…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                int[] size = MamlImporter.importSlot(repository, config.id, slot.id,
                        MamlCacheStore.packageFile(this, item.id));
                runOnUiThread(() -> {
                    slot.content = AppWidgetLayoutEngine.formatSize(size[0], size[1]);
                    repository.save(config);
                    refreshAppWidgetGrid();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    config.components.remove(slot);
                    refreshAppWidgetGrid();
                    Toast.makeText(this, "添加失败：" + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "maml-cache-import").start();
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, null,
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null) return name.replaceAll("\\.(zip|mtz)$", "");
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void chooseSlotSize(WidgetComponent component) {
        int[][] presets = AppWidgetLayoutEngine.SIZE_PRESETS;
        String[] labels = new String[presets.length];
        for (int i = 0; i < presets.length; i++) {
            labels[i] = presets[i][0] + " × " + presets[i][1];
        }
        int[] current = AppWidgetLayoutEngine.parseSize(component.content);
        int checked = -1;
        for (int i = 0; i < presets.length; i++) {
            if (current != null && presets[i][0] == current[0] && presets[i][1] == current[1]) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择尺寸（宽 × 高）")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    component.content = AppWidgetLayoutEngine.formatSize(
                            presets[which][0], presets[which][1]);
                    dialog.dismiss();
                    refreshAppWidgetGrid();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteAppWidgetSlot(WidgetComponent component) {
        config.components.remove(component);
        if (ActionSpec.HOST_MAML.equals(component.actionType)) {
            repository.mamlSlotFile(config.id, component.id).delete();
            repository.mamlSlotPreviewFile(config.id, component.id).delete();
        }
        refreshAppWidgetGrid();
    }

    private String slotLabel(WidgetComponent slot) {
        if (ActionSpec.HOST_MAML.equals(slot.actionType)) {
            return slot.actionValue.isEmpty() ? "ZIP 小部件" : slot.actionValue;
        }
        return appWidgetLabel(slot.actionValue);
    }

    private String appWidgetLabel(String flattened) {
        if (flattened == null || flattened.isEmpty()) return "未绑定小部件";
        android.content.ComponentName provider = android.content.ComponentName
                .unflattenFromString(flattened);
        if (provider == null) return flattened;
        try {
            android.appwidget.AppWidgetManager manager = android.appwidget.AppWidgetManager
                    .getInstance(this);
            for (android.appwidget.AppWidgetProviderInfo info : manager.getInstalledProviders()) {
                if (provider.equals(info.provider)) {
                    String label = info.loadLabel(getPackageManager());
                    return label == null || label.isEmpty() ? provider.getPackageName() : label;
                }
            }
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(provider.getPackageName(), 0)).toString();
        } catch (Throwable ignored) {
            return provider.getPackageName();
        }
    }

    private void loadValues() {
        enabledSwitch.setChecked(config.enabled);
        nameInput.setText(config.name);
        if (loopSwitch != null) loopSwitch.setChecked(config.loop);
        if (muteSwitch != null) muteSwitch.setChecked(config.mute);
        if (lyricSizeSlider != null) {
            lyricSizeSlider.setValue(config.lyricScale);
            lyricSizeValue.setText(Math.round(config.lyricScale * 100) + "%（当前句歌词大小）");
        }
        if (mediaPreview != null) loadMediaPreview();
        updateMediaStatus();
        renderShortcuts();
        renderLivePreview();
        refreshAppWidgetGrid();
    }

    private void saveValues() {
        syncShortcutLabels();
        config.enabled = enabledSwitch.isChecked();
        config.name = nameInput.getText().toString().trim();
        if (loopSwitch != null) config.loop = loopSwitch.isChecked();
        if (muteSwitch != null) config.mute = muteSwitch.isChecked();
        WidgetTypeRegistry.normalize(config);
        repository.save(config);
        Toast.makeText(this, "已保存并刷新外屏", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void addShortcut() {
        syncShortcutLabels();
        int count = shortcutComponents().size();
        if (count >= MAX_SHORTCUTS) {
            Toast.makeText(this, "最多添加 " + MAX_SHORTCUTS + " 个按钮", Toast.LENGTH_SHORT).show();
            return;
        }
        config.components.add(WidgetComponent.button("按钮 " + (count + 1),
                ActionSpec.LAUNCH_APP, "", 0, 0, 1, 1, count));
        WidgetTypeRegistry.buildShortcutLayout(config);
        renderShortcuts();
    }

    private void renderShortcuts() {
        if (shortcutList == null) return;
        WidgetTypeRegistry.buildShortcutLayout(config);
        renderLivePreview();
        shortcutList.removeAllViews();
        shortcutLabels.clear();
        ArrayList<WidgetComponent> buttons = shortcutComponents();
        for (int index = 0; index < buttons.size(); index++) {
            WidgetComponent component = buttons.get(index);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, index == 0 ? 0 : dp(14), 0, dp(14));

            EditText label = edit("按钮名称");
            label.setText(component.content);
            shortcutLabels.put(component.id, label);
            item.addView(label, matchWrap());

            TextView binding = text(bindingLabel(component), 13,
                    color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            binding.setPadding(0, dp(2), 0, dp(6));
            item.addView(binding);

            LinearLayout actions = horizontal();
            actions.addView(outlinedButton("系统操作", v -> chooseSystemAction(component)), weighted());
            LinearLayout.LayoutParams appParams = weighted();
            appParams.setMarginStart(dp(8));
            actions.addView(outlinedButton("选择应用", v -> chooseApp(component)), appParams);
            item.addView(actions, matchWrap());
            item.addView(textButton("删除", v -> deleteShortcut(component)), matchWrap());

            View divider = new View(this);
            divider.setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant));
            item.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            shortcutList.addView(item, matchWrap());
        }
    }

    private void renderLivePreview() {
        if (previewHolder == null) return;
        previewHolder.removeAllViews();
        MediaWidgetView preview = new MediaWidgetView(this, config, false);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(220), dp(360),
                Gravity.CENTER);
        previewHolder.addView(preview, params);
    }

    private void chooseSystemAction(WidgetComponent component) {
        syncShortcutLabels();
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择系统操作")
                .setItems(SYSTEM_ACTION_LABELS, (dialog, which) -> {
                    String action = SYSTEM_ACTION_VALUES[which];
                    if (ActionSpec.QS_TILE.equals(action)) {
                        pendingShortcutId = component.id;
                        startActivityForResult(new Intent(this, QSTilePickerActivity.class), PICK_QS_TILE);
                        return;
                    }
                    component.actionType = action;
                    component.actionValue = "";
                    if (component.content.trim().isEmpty() || component.content.startsWith("按钮 ")) {
                        component.content = SYSTEM_ACTION_LABELS[which];
                    }
                    renderShortcuts();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseApp(WidgetComponent component) {
        syncShortcutLabels();
        pendingShortcutId = component.id;
        startActivityForResult(new Intent(this, AppPickerActivity.class), PICK_APP);
    }

    private void deleteShortcut(WidgetComponent component) {
        syncShortcutLabels();
        config.components.remove(component);
        WidgetTypeRegistry.buildShortcutLayout(config);
        renderShortcuts();
    }

    private void syncShortcutLabels() {
        for (WidgetComponent component : shortcutComponents()) {
            EditText input = shortcutLabels.get(component.id);
            if (input != null) component.content = input.getText().toString().trim();
        }
    }

    private ArrayList<WidgetComponent> shortcutComponents() {
        ArrayList<WidgetComponent> buttons = new ArrayList<>();
        for (WidgetComponent component : config.components) {
            if (WidgetComponent.TYPE_BUTTON.equals(component.type)) buttons.add(component);
        }
        buttons.sort((left, right) -> Integer.compare(left.zIndex, right.zIndex));
        return buttons;
    }

    private String bindingLabel(WidgetComponent component) {
        if (ActionSpec.LAUNCH_APP.equals(component.actionType)) {
            return component.actionValue.isEmpty() ? "未绑定" : "应用 · " + component.actionValue;
        }
        if (ActionSpec.QS_TILE.equals(component.actionType)) {
            return component.actionValue.isEmpty() ? "未绑定" : "系统磁贴 · " + component.actionValue;
        }
        for (int i = 0; i < SYSTEM_ACTION_VALUES.length; i++) {
            if (SYSTEM_ACTION_VALUES[i].equals(component.actionType)) {
                return "系统操作 · " + SYSTEM_ACTION_LABELS[i].replace("…", "");
            }
        }
        return "未绑定";
    }

    private void pick(String mime, int requestCode) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == PICK_MAML_ZIP) {
            if (data.getData() == null) return;
            Uri uri = data.getData();
            String label = queryDisplayName(uri);
            WidgetComponent slot = WidgetComponent.mamlSlot(
                    label == null || label.isEmpty() ? "系统小部件" : label, 2, 2);
            config.components.add(slot);
            refreshAppWidgetGrid();
            Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    int[] size = MamlImporter.importSlot(this, repository, config.id, slot.id, uri);
                    runOnUiThread(() -> {
                        // The package dictates the slot size (widget_AxB entry).
                        slot.content = AppWidgetLayoutEngine.formatSize(size[0], size[1]);
                        repository.save(config);
                        refreshAppWidgetGrid();
                    });
                } catch (Throwable error) {
                    runOnUiThread(() -> {
                        config.components.remove(slot);
                        refreshAppWidgetGrid();
                        Toast.makeText(this, "导入失败：" + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }, "maml-slot-import").start();
            return;
        }
        if (requestCode == PICK_APPWIDGET) {
            String provider = data.getStringExtra(AppWidgetPickerActivity.EXTRA_PROVIDER);
            int cols = data.getIntExtra(AppWidgetPickerActivity.EXTRA_COLS, 2);
            int rows = data.getIntExtra(AppWidgetPickerActivity.EXTRA_ROWS, 2);
            if (provider != null && !provider.isEmpty()) {
                config.components.add(WidgetComponent.appWidget(provider, cols, rows));
                refreshAppWidgetGrid();
            }
            return;
        }
        if (requestCode == PICK_APP || requestCode == PICK_QS_TILE) {
            WidgetComponent component = findShortcut(pendingShortcutId);
            pendingShortcutId = null;
            if (component == null) return;
            if (requestCode == PICK_APP) {
                component.actionType = ActionSpec.LAUNCH_APP;
                component.actionValue = data.getStringExtra(AppPickerActivity.EXTRA_COMPONENT);
                String label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL);
                if (label != null && !label.isEmpty()) component.content = label;
            } else {
                component.actionType = ActionSpec.QS_TILE;
                component.actionValue = data.getStringExtra(QSTilePickerActivity.EXTRA_SPEC);
                String label = data.getStringExtra(QSTilePickerActivity.EXTRA_LABEL);
                if (label != null && !label.isEmpty()) component.content = label;
            }
            renderShortcuts();
            return;
        }
        if ((requestCode != PICK_IMAGE && requestCode != PICK_VIDEO) || data.getData() == null) return;
        Uri uri = data.getData();
        String kind = requestCode == PICK_IMAGE
                ? WidgetComponent.TYPE_IMAGE : WidgetComponent.TYPE_VIDEO;
        String mime = getContentResolver().getType(uri);
        mediaStatus.setText("正在导入…");
        new Thread(() -> copyMedia(uri, kind, mime), "media-import").start();
    }

    private WidgetComponent findShortcut(String id) {
        if (id == null) return null;
        for (WidgetComponent component : config.components) {
            if (id.equals(component.id) && WidgetComponent.TYPE_BUTTON.equals(component.type)) return component;
        }
        return null;
    }

    private void copyMedia(Uri uri, String kind, String mime) {
        boolean ok = false;
        try {
            repository.importMedia(config.id, uri);
            ok = true;
        } catch (Throwable ignored) {
            repository.clearMedia(config.id);
        }
        boolean result = ok;
        runOnUiThread(() -> {
            if (!result) {
                mediaStatus.setText("导入失败，请更换文件");
                return;
            }
            config.mediaType = kind;
            config.mimeType = mime == null
                    ? (WidgetComponent.TYPE_IMAGE.equals(kind) ? "image/*" : "video/*") : mime;
            WidgetTypeRegistry.buildMediaLayout(config);
            mediaPreview.setBitmap(null);
            mediaPreview.setComponent(mediaComponent());
            updateDirectionButtons();
            repository.save(config);
            updateMediaStatus();
            loadMediaPreview();
        });
    }

    private void clearMedia() {
        repository.clearMedia(config.id);
        config.mediaType = "none";
        config.mimeType = "application/octet-stream";
        WidgetTypeRegistry.buildMediaLayout(config);
        repository.save(config);
        if (mediaPreview != null) {
            mediaPreview.setComponent(null);
            mediaPreview.setBitmap(null);
        }
        updateDirectionButtons();
        updateMediaStatus();
    }

    private void updateMediaStatus() {
        if (mediaStatus == null) return;
        boolean video = WidgetComponent.TYPE_VIDEO.equals(config.mediaType);
        if (video) mediaStatus.setText("当前媒体：视频");
        else if (WidgetComponent.TYPE_IMAGE.equals(config.mediaType)) mediaStatus.setText("当前媒体：图片");
        else mediaStatus.setText("尚未选择媒体");
        if (loopSwitch != null) loopSwitch.setVisibility(video ? View.VISIBLE : View.GONE);
        if (muteSwitch != null) muteSwitch.setVisibility(video ? View.VISIBLE : View.GONE);
    }

    private void loadMediaPreview() {
        if (mediaPreview == null || "none".equals(config.mediaType)) return;
        String expectedType = config.mediaType;
        new Thread(() -> {
            Bitmap bitmap = loadMediaSource(expectedType);
            runOnUiThread(() -> {
                if (!isDestroyed() && expectedType.equals(config.mediaType) && bitmap != null) {
                    mediaPreview.setComponent(mediaComponent());
                    mediaPreview.setBitmap(bitmap);
                } else if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            });
        }, "editor-media-preview").start();
    }

    private Bitmap loadMediaSource(String mediaType) {
        File media = repository.mediaFile(config.id);
        if (!media.isFile()) return null;
        if (WidgetComponent.TYPE_VIDEO.equals(mediaType)) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(media.getAbsolutePath());
                int width = metadataDimension(retriever,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                int height = metadataDimension(retriever,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                Bitmap scaled = null;
                if (width > 0 && height > 0) {
                    float sample = Math.min(1f, Math.min(1760f / width, 2880f / height));
                    scaled = retriever.getScaledFrameAtTime(0,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            Math.max(1, Math.round(width * sample)),
                            Math.max(1, Math.round(height * sample)));
                }
                return scaled != null ? scaled
                        : retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Throwable ignored) {
                return null;
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(media.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 1760 || bounds.outHeight / sample > 2880) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(media.getAbsolutePath(), options);
    }

    private int metadataDimension(MediaMetadataRetriever retriever, int key) {
        try {
            return Math.max(0, Integer.parseInt(retriever.extractMetadata(key)));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private WidgetComponent mediaComponent() {
        for (WidgetComponent component : config.components) {
            if (WidgetComponent.TYPE_IMAGE.equals(component.type)
                    || WidgetComponent.TYPE_VIDEO.equals(component.type)) return component;
        }
        return null;
    }

    private void setMediaRotation(int rotation) {
        if (mediaPreview == null || mediaComponent() == null) return;
        mediaPreview.setMediaRotation(rotation);
        updateDirectionButtons();
    }

    private void updateDirectionButtons() {
        if (portraitButton == null || landscapeButton == null) return;
        WidgetComponent component = mediaComponent();
        boolean landscape = component != null && component.mediaRotation == 90;
        portraitButton.setChecked(!landscape);
        landscapeButton.setChecked(landscape);
        portraitButton.setEnabled(component != null);
        landscapeButton.setEnabled(component != null);
    }

    private void section(LinearLayout root, String value) {
        TextView title = text(value, 20, color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(22), 0, dp(10));
        root.addView(title);
    }

    private EditText edit(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(15);
        return input;
    }

    private TextView text(String value, float size, int textColor) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(textColor);
        return view;
    }

    private MaterialButton button(String value, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setOnClickListener(listener);
        return button;
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

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private int color(int attribute) {
        return com.google.android.material.color.MaterialColors.getColor(this, attribute, Color.BLACK);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
