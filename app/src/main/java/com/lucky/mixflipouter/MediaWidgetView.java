package com.lucky.mixflipouter;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.InputStream;

@SuppressLint("ViewConstructor")
final class MediaWidgetView extends FrameLayout {
    private final WidgetConfig config;
    private VideoView videoView;

    MediaWidgetView(Context context, WidgetConfig config) {
        super(context);
        this.config = config;
        setBackgroundColor(Color.BLACK);
        applyOfficialWidgetOutline();
        createMediaLayer();
        createButtonLayer();
    }

    private void createMediaLayer() {
        LayoutParams full = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        if ("image".equals(config.mediaType)) {
            ImageView image = new ImageView(getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image, full);
            image.post(() -> image.setImageBitmap(loadScaledBitmap(Math.max(getWidth(), 1208), Math.max(getHeight(), 1392))));
        } else if ("video".equals(config.mediaType)) {
            videoView = new VideoView(getContext());
            videoView.setVideoURI(Contract.mediaUri(config.id));
            videoView.setOnPreparedListener(player -> preparePlayer(player));
            videoView.setOnErrorListener((player, what, extra) -> {
                showPlaceholder("视频无法播放\n请尝试 H.264 / MP4");
                return true;
            });
            addView(videoView, full);
        } else {
            showPlaceholder("MIX Flip 外屏扩展\n请在模块 App 中选择图片或视频");
        }
    }

    private void applyOfficialWidgetOutline() {
        int radiusId = getResources().getIdentifier(
                "launcher_widget_radius", "dimen", Contract.TARGET_PACKAGE);
        final float radius = radiusId == 0
                ? dp(20) : getResources().getDimension(radiusId);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    private void preparePlayer(MediaPlayer player) {
        player.setLooping(config.loop);
        float volume = config.mute ? 0f : 1f;
        player.setVolume(volume, volume);
        if (isShown()) videoView.start();
    }

    private void showPlaceholder(String message) {
        TextView text = new TextView(getContext());
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(18);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(32), dp(32), dp(32), dp(32));
        addView(text, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private void createButtonLayer() {
        int active = 0;
        for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
            if (!config.labels[i].trim().isEmpty() && !config.actionValues[i].trim().isEmpty()) active++;
        }
        if (active == 0) return;

        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(active == 1 ? 1 : 2);
        grid.setRowCount((active + 1) / 2);
        grid.setPadding(dp(18), dp(10), dp(18), dp(18));

        for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
            String label = config.labels[i].trim();
            String value = config.actionValues[i].trim();
            if (label.isEmpty() || value.isEmpty()) continue;
            Button button = new Button(getContext());
            button.setAllCaps(false);
            button.setText(label);
            button.setTextColor(Color.WHITE);
            button.setTextSize(14);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(8), 0, dp(8), 0);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xB3202020);
            background.setCornerRadius(dp(18));
            background.setStroke(dp(1), 0x55FFFFFF);
            button.setBackground(background);
            final int index = i;
            button.setOnClickListener(v -> performAction(index));

            GridLayout.LayoutParams cell = new GridLayout.LayoutParams();
            cell.width = 0;
            cell.height = dp(52);
            cell.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            cell.setMargins(dp(5), dp(5), dp(5), dp(5));
            grid.addView(button, cell);
        }

        LayoutParams controls = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        addView(grid, controls);
    }

    private void performAction(int index) {
        String type = config.actionTypes[index];
        String value = config.actionValues[index].trim();
        try {
            if ("uri".equals(type)) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(value));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } else if ("broadcast".equals(type)) {
                getContext().sendBroadcast(new Intent(value));
            } else {
                Intent intent;
                if (value.contains("/")) {
                    ComponentName component = ComponentName.unflattenFromString(value);
                    if (component == null) throw new IllegalArgumentException("组件名格式错误");
                    intent = new Intent().setComponent(component);
                } else {
                    intent = getContext().getPackageManager().getLaunchIntentForPackage(value);
                }
                if (intent == null) throw new IllegalArgumentException("找不到应用");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
        } catch (Throwable error) {
            Toast.makeText(getContext(), "按键执行失败：" + error.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap loadScaledBitmap(int targetWidth, int targetHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContext().getContentResolver().openInputStream(Contract.mediaUri(config.id))) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > targetWidth * 2 || bounds.outHeight / sample > targetHeight * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream in = getContext().getContentResolver().openInputStream(Contract.mediaUri(config.id))) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Throwable error) {
            return null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (videoView != null) videoView.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (videoView != null) videoView.stopPlayback();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (videoView == null) return;
        if (visibility == VISIBLE) videoView.start(); else videoView.pause();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
