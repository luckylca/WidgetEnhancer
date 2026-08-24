package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;

public final class WidgetEditorActivity extends Activity {
    private static final int PICK_IMAGE = 1001;
    private static final int PICK_VIDEO = 1002;
    private static final int PICK_APP = 1003;
    private static final String[] TYPE_LABELS = {
            "启动应用", "打开 URI", "发送广播",
            "音量＋", "音量－", "静音切换",
            "打开手电筒", "关闭手电筒", "切换手电筒", "锁屏",
            "上一曲", "播放 / 暂停", "下一曲"
    };
    private static final String[] TYPE_VALUES = {
            ActionSpec.LAUNCH_APP, ActionSpec.OPEN_URI, ActionSpec.SEND_BROADCAST,
            ActionSpec.VOLUME_UP, ActionSpec.VOLUME_DOWN, ActionSpec.MUTE_TOGGLE,
            ActionSpec.FLASHLIGHT_ON, ActionSpec.FLASHLIGHT_OFF,
            ActionSpec.FLASHLIGHT_TOGGLE, ActionSpec.LOCK_SCREEN,
            ActionSpec.MEDIA_PREVIOUS, ActionSpec.MEDIA_PLAY_PAUSE, ActionSpec.MEDIA_NEXT
    };

    private WidgetRepository repository;
    private WidgetConfig config;
    private EditText nameInput;
    private MaterialSwitch enabledSwitch;
    private MaterialSwitch loopSwitch;
    private MaterialSwitch muteSwitch;
    private TextView mediaStatus;
    private WidgetCanvasView canvasView;
    private TextView selectionStatus;
    private final EditText[] labels = new EditText[Contract.BUTTON_COUNT];
    private final Spinner[] types = new Spinner[Contract.BUTTON_COUNT];
    private final EditText[] values = new EditText[Contract.BUTTON_COUNT];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = new WidgetRepository(this);
        String widgetId = getIntent().getStringExtra(Contract.EXTRA_WIDGET_ID);
        config = repository.get(widgetId == null ? Contract.DEFAULT_WIDGET_ID : widgetId);
        if (config == null) config = repository.list().get(0);
        setContentView(createContent());
        loadValues();
    }

    private View createContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("编辑小部件", 26, color(com.google.android.material.R.attr.colorOnSurface));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView intro = text("保存后会动态出现在系统外屏小部件的“自定义”分组。", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        intro.setPadding(0, dp(8), 0, dp(14));
        root.addView(intro);

        nameInput = edit("小部件名称");
        root.addView(nameInput, matchWrap());

        enabledSwitch = new MaterialSwitch(this);
        enabledSwitch.setText("启用自定义外屏页面");
        enabledSwitch.setTextSize(16);
        root.addView(enabledSwitch, matchWrap());

        section(root, "可视化画布");
        TextView canvasHint = text("点选组件后拖动；拖右下角橙色圆点可缩放。虚线表示已锁定。", 13,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        canvasHint.setPadding(0, 0, 0, dp(10));
        root.addView(canvasHint);

        FrameLayout canvasHolder = new FrameLayout(this);
        canvasView = new WidgetCanvasView(this);
        FrameLayout.LayoutParams canvasParams = new FrameLayout.LayoutParams(dp(220), dp(360), Gravity.CENTER);
        canvasHolder.addView(canvasView, canvasParams);
        root.addView(canvasHolder, new LinearLayout.LayoutParams(-1, dp(372)));
        canvasView.setListener(new WidgetCanvasView.Listener() {
            @Override public void onSelectionChanged(WidgetComponent component) {
                updateSelectionStatus(component);
            }
            @Override public void onComponentChanged(WidgetComponent component) {
                updateSelectionStatus(component);
            }
        });

        selectionStatus = text("未选择组件", 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        selectionStatus.setGravity(Gravity.CENTER);
        root.addView(selectionStatus, matchWrap());

        LinearLayout addComponents = horizontal();
        addComponents.addView(button("＋文本", v -> addTextComponent()), weighted());
        addComponents.addView(button("＋时间", v -> addTimeComponent()), weighted());
        addComponents.addView(button("＋按钮", v -> addButtonComponent()), weighted());
        root.addView(addComponents, matchWrap());
        LinearLayout mediaComponents = horizontal();
        mediaComponents.addView(button("＋歌曲", v -> addPlaybackTextComponent(
                WidgetComponent.TYPE_SONG_TITLE, "歌曲名称", 40, 220, 360, 72, 36)), weighted());
        mediaComponents.addView(button("＋歌手", v -> addPlaybackTextComponent(
                WidgetComponent.TYPE_ARTIST, "歌手", 40, 292, 360, 60, 26)), weighted());
        root.addView(mediaComponents, matchWrap());
        LinearLayout playbackVisuals = horizontal();
        playbackVisuals.addView(button("＋封面", v -> addAlbumArtComponent()), weighted());
        playbackVisuals.addView(button("＋进度", v -> addPlaybackProgressComponent()), weighted());
        root.addView(playbackVisuals, matchWrap());
        LinearLayout lyricComponents = horizontal();
        lyricComponents.addView(button("＋歌词", v -> addPlaybackTextComponent(
                WidgetComponent.TYPE_LYRIC_CURRENT, "当前歌词", 28, 370, 384, 76, 30)), weighted());
        lyricComponents.addView(button("＋下一句", v -> addPlaybackTextComponent(
                WidgetComponent.TYPE_LYRIC_NEXT, "下一句歌词", 28, 446, 384, 66, 23)), weighted());
        root.addView(lyricComponents, matchWrap());

        LinearLayout editComponents = horizontal();
        editComponents.addView(button("属性", v -> editSelectedComponent()), weighted());
        editComponents.addView(button("复制", v -> duplicateSelected()), weighted());
        editComponents.addView(button("删除", v -> deleteSelected()), weighted());
        editComponents.addView(button("锁定", v -> canvasView.toggleSelectedLock()), weighted());
        root.addView(editComponents, matchWrap());

        LinearLayout layerActions = horizontal();
        layerActions.addView(button("下移一层", v -> canvasView.moveLayer(-1)), weighted());
        layerActions.addView(button("上移一层", v -> canvasView.moveLayer(1)), weighted());
        root.addView(layerActions, matchWrap());
        root.addView(button("为选中按钮选择应用", v -> chooseAppForSelected()), matchWrap());

        section(root, "背景媒体");
        mediaStatus = text("尚未选择媒体", 14, 0xFF666666);
        mediaStatus.setPadding(0, 0, 0, dp(10));
        root.addView(mediaStatus);

        LinearLayout mediaButtons = horizontal();
        mediaButtons.addView(button("选择图片", v -> pick("image/*", PICK_IMAGE)), weighted());
        mediaButtons.addView(button("选择视频", v -> pick("video/*", PICK_VIDEO)), weighted());
        mediaButtons.addView(button("清除", v -> clearMedia()), weighted());
        root.addView(mediaButtons, matchWrap());

        loopSwitch = new MaterialSwitch(this);
        loopSwitch.setText("视频循环播放");
        root.addView(loopSwitch, matchWrap());
        muteSwitch = new MaterialSwitch(this);
        muteSwitch.setText("视频静音");
        root.addView(muteSwitch, matchWrap());

        section(root, "自定义按键（最多 4 个）");
        TextView hint = text("启动应用、URI、广播需要目标值；音量、手电筒和锁屏不需要填写目标。", 13, 0xFF777777);
        hint.setPadding(0, 0, 0, dp(8));
        root.addView(hint);
        for (int i = 0; i < Contract.BUTTON_COUNT; i++) addButtonEditor(root, i);

        MaterialButton save = button("保存设置", v -> saveValues());
        save.setTextSize(17);
        save.setTextColor(Color.WHITE);
        save.setBackgroundColor(0xFFFF6900);
        LinearLayout.LayoutParams saveLp = matchWrap();
        saveLp.topMargin = dp(18);
        saveLp.height = dp(54);
        root.addView(save, saveLp);

        MaterialButton moduleSettings = button("打开 LSPosed 模块设置", v -> openLsposed());
        root.addView(moduleSettings, matchWrap());

        TextView footer = text("首次使用：在 LSPosed 中启用本模块，外屏桌面 / com.miui.fliphome 为必选作用域；如需网易云同步歌词，再额外勾选 com.netease.cloudmusic。更新媒体后会尝试即时刷新。", 13, 0xFF666666);
        footer.setPadding(0, dp(16), 0, 0);
        root.addView(footer);
        return scroll;
    }

    private void addButtonEditor(LinearLayout root, int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        card.setBackgroundColor(0xFFF2F2F2);
        LinearLayout.LayoutParams cardLp = matchWrap();
        cardLp.bottomMargin = dp(9);

        TextView number = text("按键 " + (index + 1), 14, 0xFF333333);
        card.addView(number);
        LinearLayout row = horizontal();
        labels[index] = edit("显示名称");
        types[index] = new Spinner(this);
        types[index].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS));
        row.addView(labels[index], weighted());
        row.addView(types[index], weighted());
        card.addView(row, matchWrap());
        values[index] = edit("目标值");
        card.addView(values[index], matchWrap());
        root.addView(card, cardLp);
    }

    private void loadValues() {
        enabledSwitch.setChecked(config.enabled);
        nameInput.setText(config.name);
        loopSwitch.setChecked(config.loop);
        muteSwitch.setChecked(config.mute);
        canvasView.setWidget(config, null);
        loadCanvasPreview();
        updateMediaStatus();
        for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
            labels[i].setText(config.labels[i]);
            values[i].setText(config.actionValues[i]);
            types[i].setSelection(typeIndex(config.actionTypes[i]));
        }
    }

    private void saveValues() {
        config.enabled = enabledSwitch.isChecked();
        config.name = nameInput.getText().toString().trim();
        config.loop = loopSwitch.isChecked();
        config.mute = muteSwitch.isChecked();
        for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
            config.labels[i] = labels[i].getText().toString().trim();
            config.actionTypes[i] = TYPE_VALUES[types[i].getSelectedItemPosition()];
            config.actionValues[i] = values[i].getText().toString().trim();
        }
        config.mergeLegacyEditorState();
        repository.save(config);
        canvasView.setWidget(config, null);
        loadCanvasPreview();
        Toast.makeText(this, "已保存并通知外屏刷新", Toast.LENGTH_LONG).show();
    }

    private void pick(String mime, int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime);
        startActivityForResult(intent, request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == PICK_APP) {
            WidgetComponent component = canvasView.getSelected();
            if (component == null || !WidgetComponent.TYPE_BUTTON.equals(component.type)) return;
            component.actionType = ActionSpec.LAUNCH_APP;
            component.actionValue = data.getStringExtra(AppPickerActivity.EXTRA_COMPONENT);
            String label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL);
            if (label != null && !label.isEmpty()) component.content = label;
            canvasView.invalidate();
            updateSelectionStatus(component);
            syncLegacyFieldsFromComponents();
            return;
        }
        if (data.getData() == null) return;
        if (requestCode != PICK_IMAGE && requestCode != PICK_VIDEO) return;
        Uri uri = data.getData();
        String kind = requestCode == PICK_IMAGE ? "image" : "video";
        String mime = getContentResolver().getType(uri);
        mediaStatus.setText("正在导入…");
        new Thread(() -> copyMedia(uri, kind, mime), "media-import").start();
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
            if (result) {
                config.mediaType = kind;
                config.mimeType = mime == null ? ("image".equals(kind) ? "image/*" : "video/*") : mime;
                config.mergeLegacyEditorState();
                repository.save(config);
                canvasView.setWidget(config, null);
                loadCanvasPreview();
                updateMediaStatus();
                Toast.makeText(this, "媒体已导入", Toast.LENGTH_SHORT).show();
            } else {
                mediaStatus.setText("导入失败，请换一个文件重试");
            }
        });
    }

    private void clearMedia() {
        repository.clearMedia(config.id);
        config.mediaType = "none";
        config.mimeType = "application/octet-stream";
        config.mergeLegacyEditorState();
        repository.save(config);
        canvasView.setWidget(config, null);
        updateMediaStatus();
    }

    private void updateMediaStatus() {
        String type = config.mediaType;
        if ("image".equals(type)) mediaStatus.setText("当前：自定义图片");
        else if ("video".equals(type)) mediaStatus.setText("当前：自定义视频");
        else mediaStatus.setText("尚未选择媒体（也可以只显示按键）");
    }

    private void loadCanvasPreview() {
        if ("none".equals(config.mediaType)) {
            canvasView.setMediaPreview(null);
            return;
        }
        long revision = repository.revision();
        new Thread(() -> {
            Bitmap bitmap = null;
            try (InputStream input = getContentResolver().openInputStream(
                    Contract.previewUri(config.id, revision))) {
                bitmap = BitmapFactory.decodeStream(input);
            } catch (Throwable ignored) {
            }
            Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (!isDestroyed()) canvasView.setMediaPreview(result);
            });
        }, "editor-preview").start();
    }

    private void addTextComponent() {
        WidgetComponent component = new WidgetComponent();
        component.type = WidgetComponent.TYPE_TEXT;
        component.content = "自定义文本";
        component.x = 60;
        component.y = 150;
        component.width = 320;
        component.height = 80;
        component.textSize = 36;
        canvasView.addComponent(component);
    }

    private void addTimeComponent() {
        WidgetComponent component = new WidgetComponent();
        component.type = WidgetComponent.TYPE_TIME;
        component.content = "HH:mm";
        component.x = 40;
        component.y = 64;
        component.width = 360;
        component.height = 110;
        component.textSize = 58;
        canvasView.addComponent(component);
    }

    private void addPlaybackTextComponent(String type, String placeholder,
                                          float x, float y, float width, float height, float textSize) {
        WidgetComponent component = new WidgetComponent();
        component.type = type;
        component.content = placeholder;
        component.x = x;
        component.y = y;
        component.width = width;
        component.height = height;
        component.textSize = textSize;
        canvasView.addComponent(component);
    }

    private void addPlaybackProgressComponent() {
        WidgetComponent component = new WidgetComponent();
        component.type = WidgetComponent.TYPE_PLAYBACK_PROGRESS;
        component.x = 40;
        component.y = 530;
        component.width = 360;
        component.height = 30;
        component.color = "#FFFFFFFF";
        canvasView.addComponent(component);
    }

    private void addAlbumArtComponent() {
        WidgetComponent component = new WidgetComponent();
        component.type = WidgetComponent.TYPE_ALBUM_ART;
        component.x = 28;
        component.y = 190;
        component.width = 150;
        component.height = 150;
        component.cornerRadius = 20;
        component.fillMode = "cover";
        canvasView.addComponent(component);
    }

    private void addButtonComponent() {
        if (buttonComponents().size() >= Contract.BUTTON_COUNT) {
            Toast.makeText(this, "第一版最多支持 4 个按钮", Toast.LENGTH_SHORT).show();
            return;
        }
        WidgetComponent component = WidgetComponent.button("按钮", "package", "",
                80, 560, 280, 80, 0);
        canvasView.addComponent(component);
        syncLegacyFieldsFromComponents();
        editSelectedComponent();
    }

    private void chooseAppForSelected() {
        WidgetComponent selected = canvasView.getSelected();
        if (selected == null || !WidgetComponent.TYPE_BUTTON.equals(selected.type)) {
            Toast.makeText(this, "请先点选一个按钮组件", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(new Intent(this, AppPickerActivity.class), PICK_APP);
    }

    private void duplicateSelected() {
        WidgetComponent selected = canvasView.getSelected();
        if (selected == null) return;
        if (WidgetComponent.TYPE_BUTTON.equals(selected.type)
                && buttonComponents().size() >= Contract.BUTTON_COUNT) {
            Toast.makeText(this, "第一版最多支持 4 个按钮", Toast.LENGTH_SHORT).show();
            return;
        }
        canvasView.duplicateSelected();
        syncLegacyFieldsFromComponents();
    }

    private void deleteSelected() {
        WidgetComponent selected = canvasView.getSelected();
        if (selected == null) return;
        if (WidgetComponent.TYPE_IMAGE.equals(selected.type)
                || WidgetComponent.TYPE_VIDEO.equals(selected.type)) {
            clearMedia();
            return;
        }
        canvasView.deleteSelected();
        syncLegacyFieldsFromComponents();
    }

    private void editSelectedComponent() {
        WidgetComponent component = canvasView.getSelected();
        if (component == null) {
            Toast.makeText(this, "请先在画布中点选组件", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(4), dp(22), 0);

        EditText content = property(panel, "内容 / 时间格式", component.content);
        EditText color = property(panel, "颜色（例如 #FFFFFFFF）", component.color);
        EditText textSize = property(panel, "字号", Float.toString(component.textSize));
        EditText corner = property(panel, "圆角（画布单位）", Float.toString(component.cornerRadius));
        EditText opacity = property(panel, "透明度（0 到 1）", Float.toString(component.opacity));

        TextView fillLabel = text("图片 / 视频填充方式", 13, 0xFF666666);
        panel.addView(fillLabel);
        Spinner fillMode = new Spinner(this);
        String[] fillLabels = {"裁切填满", "完整显示", "拉伸"};
        String[] fillValues = {"cover", "contain", "stretch"};
        fillMode.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, fillLabels));
        fillMode.setSelection("contain".equals(component.fillMode) ? 1
                : "stretch".equals(component.fillMode) ? 2 : 0);
        panel.addView(fillMode, matchWrap());

        TextView actionLabel = text("按钮动作", 13, 0xFF666666);
        panel.addView(actionLabel);
        Spinner actionType = new Spinner(this);
        actionType.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS));
        actionType.setSelection(typeIndex(component.actionType));
        panel.addView(actionType, matchWrap());
        EditText actionValue = property(panel, "动作目标", component.actionValue);

        MaterialSwitch visible = new MaterialSwitch(this);
        visible.setText("显示组件");
        visible.setChecked(component.visible);
        panel.addView(visible, matchWrap());
        MaterialSwitch locked = new MaterialSwitch(this);
        locked.setText("锁定位置和尺寸");
        locked.setChecked(component.locked);
        panel.addView(locked, matchWrap());

        new MaterialAlertDialogBuilder(this)
                .setTitle("组件属性 · " + componentTypeLabel(component.type))
                .setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (dialog, which) -> {
                    component.content = content.getText().toString();
                    component.color = color.getText().toString().trim();
                    component.textSize = positiveFloat(textSize, component.textSize);
                    component.cornerRadius = Math.max(0, floatValue(corner, component.cornerRadius));
                    component.opacity = Math.max(0, Math.min(1, floatValue(opacity, component.opacity)));
                    component.fillMode = fillValues[fillMode.getSelectedItemPosition()];
                    component.actionType = TYPE_VALUES[actionType.getSelectedItemPosition()];
                    component.actionValue = actionValue.getText().toString().trim();
                    component.visible = visible.isChecked();
                    component.locked = locked.isChecked();
                    canvasView.invalidate();
                    updateSelectionStatus(component);
                    syncLegacyFieldsFromComponents();
                })
                .show();
    }

    private EditText property(LinearLayout panel, String hint, String value) {
        EditText input = edit(hint);
        input.setText(value);
        panel.addView(input, matchWrap());
        return input;
    }

    private void updateSelectionStatus(WidgetComponent component) {
        if (selectionStatus == null) return;
        if (component == null) {
            selectionStatus.setText("未选择组件");
            return;
        }
        selectionStatus.setText(componentTypeLabel(component.type)
                + "  ·  " + Math.round(component.x) + "," + Math.round(component.y)
                + "  ·  " + Math.round(component.width) + "×" + Math.round(component.height)
                + (component.locked ? "  ·  已锁定" : ""));
    }

    private String componentTypeLabel(String type) {
        if (WidgetComponent.TYPE_IMAGE.equals(type)) return "图片";
        if (WidgetComponent.TYPE_VIDEO.equals(type)) return "视频";
        if (WidgetComponent.TYPE_TIME.equals(type)) return "时间";
        if (WidgetComponent.TYPE_BUTTON.equals(type)) return "按钮";
        if (WidgetComponent.TYPE_SONG_TITLE.equals(type)) return "歌曲名称";
        if (WidgetComponent.TYPE_ARTIST.equals(type)) return "歌手";
        if (WidgetComponent.TYPE_LYRIC_CURRENT.equals(type)) return "当前歌词";
        if (WidgetComponent.TYPE_LYRIC_NEXT.equals(type)) return "下一句歌词";
        if (WidgetComponent.TYPE_PLAYBACK_PROGRESS.equals(type)) return "播放进度";
        if (WidgetComponent.TYPE_ALBUM_ART.equals(type)) return "专辑封面";
        return "文本";
    }

    private ArrayList<WidgetComponent> buttonComponents() {
        ArrayList<WidgetComponent> buttons = new ArrayList<>();
        for (WidgetComponent component : config.components) {
            if (WidgetComponent.TYPE_BUTTON.equals(component.type)) buttons.add(component);
        }
        buttons.sort(Comparator.comparingInt(component -> component.zIndex));
        return buttons;
    }

    private void syncLegacyFieldsFromComponents() {
        ArrayList<WidgetComponent> buttons = buttonComponents();
        for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
            if (i < buttons.size()) {
                WidgetComponent component = buttons.get(i);
                config.labels[i] = component.content;
                config.actionTypes[i] = component.actionType.isEmpty() ? "package" : component.actionType;
                config.actionValues[i] = component.actionValue;
            } else {
                config.labels[i] = "";
                config.actionTypes[i] = "package";
                config.actionValues[i] = "";
            }
            if (labels[i] != null) labels[i].setText(config.labels[i]);
            if (types[i] != null) types[i].setSelection(typeIndex(config.actionTypes[i]));
            if (values[i] != null) values[i].setText(config.actionValues[i]);
        }
    }

    private float positiveFloat(EditText input, float fallback) {
        float value = floatValue(input, fallback);
        return value > 0 ? value : fallback;
    }

    private float floatValue(EditText input, float fallback) {
        try {
            return Float.parseFloat(input.getText().toString().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void openLsposed() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("io.github.lsposed.manager");
        if (launch != null) startActivity(launch);
        else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private int typeIndex(String value) {
        for (int i = 0; i < TYPE_VALUES.length; i++) if (TYPE_VALUES[i].equals(value)) return i;
        return 0;
    }

    private void section(LinearLayout root, String name) {
        TextView title = text(name, 19, Color.BLACK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(22), 0, dp(10));
        root.addView(title);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private EditText edit(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setSingleLine(true);
        edit.setTextSize(14);
        return edit;
    }

    private MaterialButton button(String text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setAllCaps(false);
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
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(int attribute) {
        return com.google.android.material.color.MaterialColors.getColor(this, attribute, Color.BLACK);
    }
}
