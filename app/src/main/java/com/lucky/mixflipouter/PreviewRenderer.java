package com.lucky.mixflipouter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/** Builds the exact 440 x 720 artwork size used by FlipHome's official previews. */
final class PreviewRenderer {
    private static final int WIDTH = 440;
    private static final int HEIGHT = 720;
    private static final int RENDER_VERSION = 6;

    static File ensure(Context context, WidgetConfig config, File media, long revision) {
        String id = config == null ? "missing" : safeFilePart(config.id);
        long modified = media.isFile() ? media.lastModified() : 0;
        String mediaType = config == null ? "none" : config.mediaType;
        String typeId = config == null ? "missing" : WidgetTypeRegistry.resolve(config);
        File output = new File(context.getCacheDir(), String.format(Locale.US,
                "widget-preview-v%d-%s-%d-%d-%s-%s.png",
                RENDER_VERSION, id, revision, modified, mediaType, typeId));
        if (output.isFile() && output.length() > 0) return output;

        Bitmap source = null;
        try {
            if (media.isFile() && "image".equals(mediaType)) {
                source = decodeSampled(media);
            } else if (media.isFile() && "video".equals(mediaType)) {
                source = videoFrame(media);
            }
            render(context, output, source, config);
        } catch (Throwable ignored) {
            output.delete();
            render(context, output, null, config);
        } finally {
            if (source != null && !source.isRecycled()) source.recycle();
        }
        return output;
    }

    private static void render(Context context, File output, Bitmap source, WidgetConfig config) {
        Bitmap preview = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(preview);
        Path shape = officialShape();

        if (source != null) {
            Paint media = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix transform = new Matrix();
            float scale = Math.max(WIDTH / (float) source.getWidth(), HEIGHT / (float) source.getHeight());
            float left = (WIDTH - source.getWidth() * scale) / 2f;
            float top = (HEIGHT - source.getHeight() * scale) / 2f;
            transform.setScale(scale, scale);
            transform.postTranslate(left, top);
            shader.setLocalMatrix(transform);
            media.setShader(shader);
            canvas.drawPath(shape, media);
        } else if (config != null && WidgetTypeRegistry.SHORTCUTS.equals(
                WidgetTypeRegistry.resolve(config))) {
            drawPlaceholder(context, canvas, config);
        } else {
            Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
            background.setShader(new LinearGradient(0, 0, WIDTH, HEIGHT,
                    new int[]{0xff151820, 0xff29222e, 0xff17171c},
                    null, Shader.TileMode.CLAMP));
            canvas.drawPath(shape, background);
            drawPlaceholder(context, canvas, config);
        }

        if (config != null && "video".equals(config.mediaType)) drawVideoBadge(canvas);

        File temporary = new File(output.getParentFile(), output.getName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary, false)) {
            if (!preview.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IllegalStateException("Unable to encode preview");
            }
            stream.getFD().sync();
        } catch (Throwable error) {
            temporary.delete();
            throw new IllegalStateException(error);
        } finally {
            preview.recycle();
        }
        if (!temporary.renameTo(output)) {
            temporary.delete();
            throw new IllegalStateException("Unable to publish preview");
        }
    }

    private static Bitmap decodeSampled(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > WIDTH * 2 || bounds.outHeight / sample > HEIGHT * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static Bitmap videoFrame(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void drawPlaceholder(Context context, Canvas canvas, WidgetConfig config) {
        String typeId = WidgetTypeRegistry.resolve(config);
        if (WidgetTypeRegistry.MUSIC.equals(typeId)) {
            drawMusicPreview(canvas);
            return;
        }
        if (WidgetTypeRegistry.SHORTCUTS.equals(typeId)) {
            drawShortcutPreview(context, canvas, config);
            return;
        }
        Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        accent.setColor(0xffff6900);
        canvas.drawCircle(WIDTH / 2f, 272, 76, accent);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.WHITE);
        text.setFakeBoldText(true);
        text.setTextSize(39);
        String name = config == null ? "自定义外屏" : config.name;
        if (name.length() > 8) name = name.substring(0, 8) + "…";
        canvas.drawText(name, WIDTH / 2f, 430, text);
        text.setFakeBoldText(false);
        text.setTextSize(25);
        text.setColor(0xffc8c8ce);
        canvas.drawText("选择图片或视频", WIDTH / 2f, 477, text);
    }

    private static void drawMusicPreview(Canvas canvas) {
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xffbfc0c8);
        text.setTextSize(24);
        canvas.drawText("暂无播放", WIDTH / 2f, 86, text);
        text.setColor(0xff92939a);
        text.setTextSize(22);
        canvas.drawText("上一句歌词", WIDTH / 2f, 250, text);
        text.setColor(Color.WHITE);
        text.setFakeBoldText(true);
        text.setTextSize(34);
        canvas.drawText("当前歌词", WIDTH / 2f, 340, text);
        text.setFakeBoldText(false);
        text.setColor(0xffa9aab3);
        text.setTextSize(22);
        canvas.drawText("下一句歌词", WIDTH / 2f, 438, text);
        Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
        progress.setColor(0x55ffffff);
        canvas.drawRoundRect(40, 636, 400, 642, 3, 3, progress);
        progress.setColor(Color.WHITE);
        canvas.drawRoundRect(40, 636, 165, 642, 3, 3, progress);
    }

    private static void drawShortcutPreview(Context context, Canvas canvas, WidgetConfig config) {
        if (config == null) return;
        int count = 0;
        for (WidgetComponent component : config.components) {
            if (WidgetComponent.TYPE_BUTTON.equals(component.type) && component.visible) count++;
        }
        count = Math.min(count, ButtonLayoutEngine.MAX_BUTTONS);
        if (count == 0) return;
        ButtonLayoutEngine.Layout layout = ButtonLayoutEngine.layout(count, WIDTH, HEIGHT);
        int visibleIndex = 0;
        for (WidgetComponent component : config.components) {
            if (!WidgetComponent.TYPE_BUTTON.equals(component.type) || !component.visible) continue;
            if (visibleIndex >= layout.items.size()) break;
            ButtonLayoutEngine.Item item = layout.items.get(visibleIndex++);
            float iconSize = item.iconSize;
            float left = item.centerX - iconSize / 2f;
            float top = item.centerY - iconSize / 2f;
            Drawable appIcon = ShortcutIconRenderer.loadAppIcon(context, component);
            if (appIcon != null) {
                appIcon.setBounds(Math.round(left), Math.round(top),
                        Math.round(left + iconSize), Math.round(top + iconSize));
                appIcon.draw(canvas);
            } else {
                ShortcutIconRenderer.drawSystemIcon(
                        canvas, component.actionType, left, top, iconSize);
            }
        }
    }

    private static void drawVideoBadge(Canvas canvas) {
        float x = 359;
        float y = 632;
        Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        badge.setColor(0xb8000000);
        canvas.drawCircle(x, y, 38, badge);
        badge.setStyle(Paint.Style.STROKE);
        badge.setStrokeWidth(2.5f);
        badge.setColor(0x66ffffff);
        canvas.drawCircle(x, y, 36.5f, badge);

        Path play = new Path();
        play.moveTo(x - 9, y - 16);
        play.lineTo(x + 17, y);
        play.lineTo(x - 9, y + 16);
        play.close();
        badge.setStyle(Paint.Style.FILL);
        badge.setColor(Color.WHITE);
        canvas.drawPath(play, badge);
    }

    /** Xiaomi's preview outline, transcribed from settings_widget_preview_bg.xml. */
    private static Path officialShape() {
        Path path = new Path();
        path.moveTo(0, 136.7f);
        path.cubicTo(0, 88.85f, 0, 64.93f, 9.31f, 46.65f);
        path.cubicTo(17.5f, 30.57f, 30.57f, 17.5f, 46.65f, 9.31f);
        path.cubicTo(64.93f, 0, 88.85f, 0, 136.7f, 0);
        path.lineTo(303.3f, 0);
        path.cubicTo(351.15f, 0, 375.08f, 0, 393.35f, 9.31f);
        path.cubicTo(409.43f, 17.5f, 422.5f, 30.57f, 430.69f, 46.65f);
        path.cubicTo(440, 64.93f, 440, 88.85f, 440, 136.7f);
        path.lineTo(440, 583.3f);
        path.cubicTo(440, 631.15f, 440, 655.08f, 430.69f, 673.35f);
        path.cubicTo(422.5f, 689.43f, 409.43f, 702.5f, 393.35f, 710.69f);
        path.cubicTo(375.08f, 720, 351.15f, 720, 303.3f, 720);
        path.lineTo(136.7f, 720);
        path.cubicTo(88.85f, 720, 64.93f, 720, 46.65f, 710.69f);
        path.cubicTo(30.57f, 702.5f, 17.5f, 689.43f, 9.31f, 673.35f);
        path.cubicTo(0, 655.08f, 0, 631.15f, 0, 583.3f);
        path.close();
        return path;
    }

    private static String safeFilePart(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private PreviewRenderer() {
    }
}
