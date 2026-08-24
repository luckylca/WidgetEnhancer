package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

public final class WidgetEditorActivity extends Activity {
    private static final int PICK_IMAGE = 1001;
    private static final int PICK_VIDEO = 1002;
    private static final String[] TYPE_LABELS = {"启动应用", "打开 URI", "发送广播"};
    private static final String[] TYPE_VALUES = {"package", "uri", "broadcast"};

    private WidgetRepository repository;
    private WidgetConfig config;
    private EditText nameInput;
    private MaterialSwitch enabledSwitch;
    private MaterialSwitch loopSwitch;
    private MaterialSwitch muteSwitch;
    private TextView mediaStatus;
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
        TextView hint = text("留空的按键不会显示。应用可填包名或“包名/组件名”；URI 示例：alipays://、weixin://；广播填写 action。", 13, 0xFF777777);
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

        TextView footer = text("首次使用：在 LSPosed 中启用本模块，作用域只勾选“外屏桌面 / com.miui.fliphome”，然后重启手机。更新媒体后，重新开合一次外屏；若未刷新，再重启外屏桌面或手机。", 13, 0xFF666666);
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
        repository.save(config);
        Toast.makeText(this, "已保存；重新开合外屏以刷新", Toast.LENGTH_LONG).show();
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
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
                repository.save(config);
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
        repository.save(config);
        updateMediaStatus();
    }

    private void updateMediaStatus() {
        String type = config.mediaType;
        if ("image".equals(type)) mediaStatus.setText("当前：自定义图片");
        else if ("video".equals(type)) mediaStatus.setText("当前：自定义视频");
        else mediaStatus.setText("尚未选择媒体（也可以只显示按键）");
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
