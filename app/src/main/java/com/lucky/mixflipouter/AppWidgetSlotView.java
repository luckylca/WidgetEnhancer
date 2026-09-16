package com.lucky.mixflipouter;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Hosts one system AppWidget inside the FlipHome process. Handles lazy id
 * allocation (persisted back into the module config), the system bind-consent
 * flow and swapping the placeholder for the real AppWidgetHostView.
 */
final class AppWidgetSlotView extends FrameLayout {
    private static final String TAG = "MixFlipCustom";
    private static final long BIND_WATCH_INTERVAL_MS = 2_000L;
    private static final int REQUEST_BIND_CONSENT = 0x4d49;
    /** FlipHome's launcher_widget_radius; matches native outer-screen widgets. */
    private static final float OUTER_WIDGET_CORNER_DP = 20f;
    /** Debug builds may force hosting outside FlipHome (device tests). */
    static volatile boolean debugForceHosted;

    private final String widgetId;
    private final WidgetComponent component;
    private final boolean interactive;
    private final boolean hosted;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView placeholder;
    private AppWidgetHostView hostView;
    private boolean preparing;
    private int nullInfoTicks;
    /** Provider-declared design size in dp; -1 when unknown (falls back to filling the slot). */
    private int naturalWidthDp = -1;
    private int naturalHeightDp = -1;
    /** Launcher cells wide the widget is designed for. */
    private int naturalCellsWide = 1;
    private android.graphics.Path cornerPath;
    /** Scaled container of the host view; carries the transform + corner clip. */
    private android.view.View scaleWrapper;

    private final Runnable bindWatcher = new Runnable() {
        @Override
        public void run() {
            if (hostView != null) return;
            if (tryAttach()) return;
            // A persisted id whose provider info never shows up is dead (e.g. it was
            // released as an orphan). Reset it so the slot re-allocates instead of
            // sitting on the placeholder forever.
            if (++nullInfoTicks >= 15) {
                nullInfoTicks = 0;
                Log.i(TAG, "AppWidget id " + component.appWidgetId + " never bound; re-allocating");
                resetAndReallocate();
                return;
            }
            mainHandler.postDelayed(this, BIND_WATCH_INTERVAL_MS);
        }
    };

    AppWidgetSlotView(Context context, String widgetId, WidgetComponent component,
                      boolean interactive) {
        super(context);
        this.widgetId = widgetId;
        this.component = component;
        this.interactive = interactive;
        this.hosted = debugForceHosted || Contract.TARGET_PACKAGE.equals(context.getPackageName());
        if (!hosted) {
            showPlaceholder("应用小部件\n" + AppWidgetLayoutEngine.sizeLabel(component.content));
            return;
        }
        AppWidgetHostBridge.initialize(context);
        prepare();
    }

    private void prepare() {
        if (component.appWidgetId >= 0) {
            startBindWatch();
            return;
        }
        if (preparing) return;
        preparing = true;
        showPlaceholder("正在准备小部件…");
        new Thread(() -> {
            int allocated = AppWidgetHostBridge.allocateId();
            if (allocated >= 0) {
                int persisted = reportAllocatedId(allocated);
                component.appWidgetId = persisted >= 0 ? persisted : allocated;
            }
            reconcileAsync();
            mainHandler.post(() -> {
                preparing = false;
                if (component.appWidgetId >= 0) startBindWatch();
                else showPlaceholder("小部件初始化失败");
            });
        }, "mixflip-appwidget-alloc").start();
    }

    private int reportAllocatedId(int appWidgetId) {
        try {
            Bundle extras = new Bundle();
            extras.putString("component_id", component.id);
            extras.putInt("app_widget_id", appWidgetId);
            Bundle result = getContext().getContentResolver().call(
                    Contract.PROVIDER_URI, "set_appwidget_id", widgetId, extras);
            if (result != null && result.getBoolean("ok")) {
                return result.getInt("app_widget_id", appWidgetId);
            }
        } catch (Throwable error) {
            Log.w(TAG, "report appWidgetId failed", error);
        }
        return -1;
    }

    private void resetAndReallocate() {
        if (preparing) return;
        preparing = true;
        new Thread(() -> {
            try {
                Bundle extras = new Bundle();
                extras.putString("component_id", component.id);
                extras.putInt("app_widget_id", -1);
                extras.putBoolean("force", true);
                getContext().getContentResolver().call(
                        Contract.PROVIDER_URI, "set_appwidget_id", widgetId, extras);
                component.appWidgetId = -1;
            } catch (Throwable error) {
                Log.w(TAG, "reset appWidgetId failed", error);
            }
            mainHandler.post(() -> {
                preparing = false;
                prepare();
            });
        }, "mixflip-appwidget-reset").start();
    }

    private void reconcileAsync() {
        try {
            List<WidgetConfig> configs = WidgetConfig.list(getContext());
            AppWidgetHostBridge.reconcile(configs);
        } catch (Throwable ignored) {
        }
    }

    private void startBindWatch() {
        mainHandler.removeCallbacks(bindWatcher);
        if (tryAttach()) return;
        ComponentName provider = provider();
        if (provider != null && AppWidgetHostBridge.bindIfAllowed(
                component.appWidgetId, provider) && tryAttach()) {
            return;
        }
        showConsentPlaceholder();
        bindWatcher.run();
    }

    private boolean tryAttach() {
        if (hostView != null) return true;
        AppWidgetProviderInfo info = AppWidgetHostBridge.infoFor(component.appWidgetId);
        if (info == null) return false;
        AppWidgetHostView view = AppWidgetHostBridge.createView(component.appWidgetId, info);
        if (view == null) return false;
        hostView = view;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int[] natural = AppWidgetLayoutEngine.naturalSizeDp(info.minWidth, info.minHeight,
                metrics.density,
                Math.round(metrics.widthPixels / metrics.density),
                Math.round(metrics.heightPixels / metrics.density));
        naturalWidthDp = natural == null ? -1 : natural[0];
        naturalHeightDp = natural == null ? -1 : natural[1];
        int targetCells = android.os.Build.VERSION.SDK_INT >= 31 ? info.targetCellWidth : 0;
        naturalCellsWide = AppWidgetLayoutEngine.naturalCellsWide(targetCells, naturalWidthDp);
        removeAllViews();
        setBackground(null);
        if (hasNaturalSize()) {
            float density = getResources().getDisplayMetrics().density;
            // Render at the provider's design size inside a wrapper; the
            // wrapper carries the scale transform and the corner clip, so the
            // visible card corners are rounded even when the card does not
            // reach the slot's own corners.
            FrameLayout wrapper = new FrameLayout(getContext()) {
                @Override
                protected void dispatchDraw(android.graphics.Canvas canvas) {
                    if (!component.cornerEnabled || getWidth() <= 0 || getHeight() <= 0) {
                        super.dispatchDraw(canvas);
                        return;
                    }
                    float scale = getScaleX() > 0f ? getScaleX() : 1f;
                    float radius = cornerRadiusPx(AppWidgetSlotView.this) / scale;
                    // The visible card starts inside the host view's default
                    // padding (either laid out there, or drawn with a matching
                    // baked-in inset), so clip the content box, not the bounds.
                    float left = hostView.getPaddingLeft();
                    float top = hostView.getPaddingTop();
                    float right = getWidth() - hostView.getPaddingRight();
                    float bottom = getHeight() - hostView.getPaddingBottom();
                    android.graphics.Path path = new android.graphics.Path();
                    path.addRoundRect(left, top, right, bottom, radius, radius,
                            android.graphics.Path.Direction.CW);
                    canvas.save();
                    canvas.clipPath(path);
                    super.dispatchDraw(canvas);
                    canvas.restore();
                }
            };
            wrapper.addView(view, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            scaleWrapper = wrapper;
            addView(wrapper, new LayoutParams(
                    Math.round(naturalWidthDp * density),
                    Math.round(naturalHeightDp * density),
                    Gravity.TOP | Gravity.START));
        } else {
            scaleWrapper = null;
            addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        applyTransform();
        applySizeOptions();
        Log.i(TAG, "AppWidget attached: " + info.provider + " id=" + component.appWidgetId
                + " natural=" + naturalWidthDp + "x" + naturalHeightDp
                + "dp cellsWide=" + naturalCellsWide + " corner=" + component.cornerEnabled);
        return true;
    }

    private boolean hasNaturalSize() {
        return naturalWidthDp > 0 && naturalHeightDp > 0;
    }

    /** Clips the slot to the native outer-screen widget corner radius. */
    static void applyCorner(WidgetComponent component, android.view.View view) {
        if (!component.cornerEnabled) return;
        float radius = cornerRadiusPx(view);
        view.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View target, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, target.getWidth(), target.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    static float cornerRadiusPx(android.view.View view) {
        return OUTER_WIDGET_CORNER_DP * view.getResources().getDisplayMetrics().density;
    }

    /**
     * Canvas-level rounded clipping. Outline-based clipping alone proved
     * unreliable inside FlipHome's view hierarchy, so the slot clips its
     * children with an explicit path during draw.
     */
    @Override
    protected void dispatchDraw(android.graphics.Canvas canvas) {
        // When the host view lives in the scaled wrapper, the wrapper clips
        // the visible card corners; slot-level clipping would only round
        // empty space and chamfer the wrapper's own corners.
        if (!component.cornerEnabled || scaleWrapper != null
                || getWidth() <= 0 || getHeight() <= 0) {
            super.dispatchDraw(canvas);
            return;
        }
        float radius = cornerRadiusPx(this);
        if (cornerPath == null) cornerPath = new android.graphics.Path();
        cornerPath.rewind();
        cornerPath.addRoundRect(0, 0, getWidth(), getHeight(), radius, radius,
                android.graphics.Path.Direction.CW);
        canvas.save();
        canvas.clipPath(cornerPath);
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    /**
     * Scales the widget per its natural launcher width (see
     * AppWidgetLayoutEngine.slotScale): content that fits is centered, content
     * that overflows is left/top aligned and clipped by the slot.
     */
    private void applyTransform() {
        if (hostView == null || !hasNaturalSize()) return;
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float naturalWidth = naturalWidthDp * density;
        float naturalHeight = naturalHeightDp * density;
        float scale = AppWidgetLayoutEngine.slotScale(getWidth(), getHeight(),
                naturalWidth, naturalHeight, naturalCellsWide);
        float scaledWidth = naturalWidth * scale;
        float scaledHeight = naturalHeight * scale;
        android.view.View transformed = scaleWrapper != null ? scaleWrapper : hostView;
        transformed.setPivotX(0f);
        transformed.setPivotY(0f);
        transformed.setScaleX(scale);
        transformed.setScaleY(scale);
        transformed.setTranslationX(scaledWidth > getWidth()
                ? 0f : (getWidth() - scaledWidth) / 2f);
        transformed.setTranslationY(scaledHeight > getHeight()
                ? 0f : (getHeight() - scaledHeight) / 2f);
    }

    private void showConsentPlaceholder() {
        showPlaceholder("点击完成小部件授权");
        if (!interactive) return;
        setOnClickListener(view -> launchConsent());
    }

    private void launchConsent() {
        ComponentName provider = provider();
        if (provider == null || component.appWidgetId < 0) return;
        if (AppWidgetHostBridge.bindIfAllowed(component.appWidgetId, provider)) {
            tryAttach();
            return;
        }
        nullInfoTicks = 0;
        try {
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, component.appWidgetId);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider);
            // MIUI's AllowBindAppWidgetActivity closes immediately unless it is
            // started for result from a live activity; application-context and
            // plain NEW_TASK launches are rejected outright.
            android.app.Activity activity = findActivity(getContext());
            if (activity == null) activity = AppWidgetHostBridge.topActivity();
            if (activity != null) {
                activity.startActivityForResult(intent, REQUEST_BIND_CONSENT);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().getApplicationContext().startActivity(intent);
            }
        } catch (Throwable error) {
            Log.w(TAG, "launch bind consent failed", error);
            showPlaceholder("授权页打开失败\n请在主屏添加一次该小部件");
        }
    }

    private static android.app.Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof android.content.ContextWrapper) {
            if (current instanceof android.app.Activity) {
                return (android.app.Activity) current;
            }
            current = ((android.content.ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private ComponentName provider() {
        String flattened = component.actionValue;
        if (flattened == null || flattened.isEmpty()) return null;
        return ComponentName.unflattenFromString(flattened);
    }

    private void showPlaceholder(String message) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0x1AFFFFFF);
        shape.setCornerRadius(component.cornerEnabled
                ? OUTER_WIDGET_CORNER_DP * getResources().getDisplayMetrics().density : 24);
        setBackground(shape);
        if (placeholder == null) {
            placeholder = new TextView(getContext());
            placeholder.setTextColor(0xCCFFFFFF);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f
                    * getResources().getDisplayMetrics().density);
            addView(placeholder, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        placeholder.setText(message);
    }

    private void applySizeOptions() {
        if (hostView == null || getWidth() <= 0 || getHeight() <= 0) return;
        // Report the provider's design size so it lays out at the size it was
        // built for; our transform handles fitting it into the slot.
        if (hasNaturalSize()) {
            AppWidgetHostBridge.updateOptions(component.appWidgetId,
                    naturalWidthDp, naturalHeightDp);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        AppWidgetHostBridge.updateOptions(component.appWidgetId,
                Math.round(getWidth() / density), Math.round(getHeight() / density));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        applyTransform();
        applySizeOptions();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (hosted && hostView == null && component.appWidgetId >= 0) {
            mainHandler.removeCallbacks(bindWatcher);
            bindWatcher.run();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(bindWatcher);
        super.onDetachedFromWindow();
    }
}
