package com.lucky.mixflipouter;

import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Hosts an imported MAML widget package inside one grid cell of an appwidget
 * page. Runs in the FlipHome process: copies the package into FlipHome's res
 * dir and embeds the native FlipMaMlHostView so the widget renders and reacts
 * exactly like a built-in one.
 */
final class MamlSlotView extends FrameLayout {
    private static final String TAG = "MixFlipCustom";
    private static final String COMPAT_CLASS =
            "com.miui.fliphome.widget.ui.maml.FlipMaMlWidgetCompat";
    private static final String HOST_VIEW_CLASS =
            "com.miui.fliphome.widget.ui.maml.FlipMaMlHostView";

    private final boolean hosted;
    private TextView placeholder;

    MamlSlotView(Context context, String widgetId, WidgetComponent component,
                 boolean interactive) {
        super(context);
        hosted = Contract.TARGET_PACKAGE.equals(context.getPackageName());
        AppWidgetSlotView.applyCorner(component, this);
        if (!hosted) {
            showPlaceholder(component.actionValue.isEmpty()
                    ? "ZIP 小部件\n" + AppWidgetLayoutEngine.sizeLabel(component.content)
                    : component.actionValue + "\n"
                            + AppWidgetLayoutEngine.sizeLabel(component.content));
            return;
        }
        showPlaceholder("加载中…");
        new Thread(() -> {
            try {
                String resPath = installAndResolve(context, widgetId, component);
                View host = createHostView(context, resPath);
                post(() -> {
                    removeAllViews();
                    addView(host, new LayoutParams(
                            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                });
            } catch (Throwable error) {
                Log.w(TAG, "MAML slot failed", error);
                post(() -> showPlaceholder("小部件加载失败"));
            }
        }, "mixflip-maml-slot").start();
    }

    private String installAndResolve(Context context, String widgetId,
                                     WidgetComponent component) throws Exception {
        Context deviceContext = context.createDeviceProtectedStorageContext();
        File resDir = new File(deviceContext.getFilesDir(), "maml/res");
        String name = "mixflip_mamls_" + component.id;
        File mtz = new File(resDir, name + ".mtz");
        if (!mtz.isFile()) {
            resDir.mkdirs();
            File temporary = new File(resDir, name + ".tmp");
            try (InputStream in = context.getContentResolver().openInputStream(
                    Contract.mamlSlotUri(widgetId, component.id));
                 FileOutputStream out = new FileOutputStream(temporary, false)) {
                if (in == null) throw new IllegalStateException("找不到小部件包");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
                out.getFD().sync();
            } catch (Throwable error) {
                temporary.delete();
                throw error;
            }
            if (!temporary.renameTo(mtz)) {
                temporary.delete();
                throw new IllegalStateException("无法写入小部件包");
            }
        }
        deviceContext.getSharedPreferences("mixflip_maml_imports", 0)
                .edit().putBoolean(name, true).apply();
        Class<?> compat = Class.forName(COMPAT_CLASS, true, context.getClassLoader());
        String resPath = (String) compat.getMethod("getResPathAndUnZip",
                String.class, int.class, int.class, String.class)
                .invoke(null, name, 2, 3, mtz.getAbsolutePath());
        if (resPath == null || resPath.isEmpty()) {
            throw new IllegalStateException("包里没有 2x3 小部件");
        }
        return resPath;
    }

    private View createHostView(Context context, String resPath) throws Exception {
        Class<?> hostClass = Class.forName(HOST_VIEW_CLASS, true, context.getClassLoader());
        return (View) hostClass.getConstructor(Context.class, String.class)
                .newInstance(context, resPath);
    }

    private void showPlaceholder(String message) {
        if (placeholder == null) {
            placeholder = new TextView(getContext());
            placeholder.setTextColor(0xCCFFFFFF);
            placeholder.setGravity(Gravity.CENTER);
            addView(placeholder, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        placeholder.setText(message);
    }
}
