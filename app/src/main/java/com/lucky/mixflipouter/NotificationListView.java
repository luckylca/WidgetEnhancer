package com.lucky.mixflipouter;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Notification layer rendered inside the FlipHome process: newest three
 * notifications, one per fixed-height row (widget height / 3) stacked from
 * the top — rows never stretch to fill the widget when fewer are bound.
 * Tap opens the source app, swiping a row left dismisses the notification
 * through the module's notification listener. Fully transparent background.
 */
final class NotificationListView extends FrameLayout {
    interface Callback {
        void onOpen(String key);
        void onDismiss(String key);
    }

    private static final int ROWS = NotificationStateStore.VISIBLE_ENTRIES;
    private static final float DISMISS_FRACTION = 0.4f;

    private final LinearLayout rowContainer;
    private final RowView[] rows = new RowView[ROWS];
    private final TextView emptyView;
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final int touchSlop;
    private final boolean interactive;
    private Callback callback;
    private long appliedRevision = -1;
    private Bundle pendingData;
    private RowView activeRow;
    private float downX;
    private float downY;
    private boolean swiping;

    NotificationListView(Context context, boolean interactive) {
        super(context);
        this.interactive = interactive;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        rowContainer = new LinearLayout(context);
        rowContainer.setOrientation(LinearLayout.VERTICAL);
        addView(rowContainer, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        for (int i = 0; i < ROWS; i++) {
            rows[i] = new RowView(context);
            // Fixed row height (set in onMeasure to container/ROWS); rows
            // stack from the top instead of stretching to fill the widget.
            rowContainer.addView(rows[i], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        emptyView = new TextView(context);
        emptyView.setText("暂无通知");
        emptyView.setTextColor(WallpaperColorState.secondaryTextColor());
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(GONE);
        addView(emptyView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setClickable(interactive);
    }

    private final WallpaperColorState.Listener wallpaperColorListener =
            new WallpaperColorState.Listener() {
                @Override
                public void onWallpaperColorChanged(boolean darkWallpaper) {
                    emptyView.setTextColor(WallpaperColorState.secondaryTextColor());
                }
            };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        WallpaperColorState.addListener(wallpaperColorListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        WallpaperColorState.removeListener(wallpaperColorListener);
        super.onDetachedFromWindow();
    }

    void setCallback(Callback value) {
        callback = value;
    }

    void setData(Bundle data) {
        if (data == null) return;
        if (activeRow != null) {
            pendingData = data;
            return;
        }
        applyData(data);
    }

    int debugBoundRowCount() {
        int bound = 0;
        for (RowView row : rows) {
            if (row.getVisibility() == VISIBLE && row.key != null) bound++;
        }
        return bound;
    }

    boolean debugSwiping() {
        return swiping;
    }

    float debugActiveOffset() {
        return activeRow == null ? Float.NaN : activeRow.getSwipeOffset();
    }

    private void applyData(Bundle data) {
        long revision = data.getLong("revision", -1);
        if (revision == appliedRevision) return;
        int count = Math.min(ROWS, data.getInt("count", 0));
        boolean applied = true;
        for (int i = 0; i < ROWS; i++) {
            RowView row = rows[i];
            row.resetSwipe();
            try {
                if (i < count) {
                    row.bind(
                            data.getString("key_" + i, ""),
                            data.getString("pkg_" + i, ""),
                            data.getString("title_" + i, ""),
                            data.getString("text_" + i, ""),
                            data.getBoolean("clearable_" + i, false));
                    row.setVisibility(VISIBLE);
                } else {
                    row.setVisibility(GONE);
                }
            } catch (Throwable error) {
                applied = false;
                row.setVisibility(GONE);
            }
        }
        if (applied) appliedRevision = revision;
        emptyView.setVisibility(count == 0 ? VISIBLE : GONE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (height > 0) {
            int rowHeight = Math.max(1, height / ROWS);
            int iconSize = Math.max(1, Math.round(rowHeight * 0.36f));
            float titleSize = Math.max(8f, rowHeight * 0.185f);
            float contentSize = Math.max(8f, rowHeight * 0.15f);
            for (RowView row : rows) {
                row.applyMetrics(iconSize, titleSize, contentSize, rowHeight);
            }
            emptyView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    Math.max(10f, rowHeight * 0.3f));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!interactive) return super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activeRow = rowAt(event.getY());
            downX = event.getX();
            downY = event.getY();
            swiping = false;
            return activeRow != null || super.dispatchTouchEvent(event);
        }
        if (activeRow == null) return super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE) {
            float deltaX = event.getX() - downX;
            float deltaY = event.getY() - downY;
            if (!swiping && Math.abs(deltaX) > touchSlop
                    && Math.abs(deltaX) > Math.abs(deltaY)) {
                swiping = true;
            }
            if (swiping) {
                activeRow.setSwipeOffset(Math.min(0f, deltaX));
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            RowView row = activeRow;
            activeRow = null;
            if (row == null) return true;
            if (action == MotionEvent.ACTION_UP && !swiping
                    && Math.abs(event.getX() - downX) <= touchSlop
                    && Math.abs(event.getY() - downY) <= touchSlop) {
                finishGesture();
                if (callback != null && row.key != null) callback.onOpen(row.key);
                return true;
            }
            boolean dismiss = action == MotionEvent.ACTION_UP && swiping
                    && row.clearable && row.getSwipeOffset() < -getWidth() * DISMISS_FRACTION;
            if (dismiss) {
                row.animateDismiss(() -> {
                    finishGesture();
                    if (callback != null && row.key != null) callback.onDismiss(row.key);
                });
            } else {
                row.animateBack(this::finishGesture);
            }
            return true;
        }
        return true;
    }

    private void finishGesture() {
        if (pendingData != null) {
            Bundle data = pendingData;
            pendingData = null;
            applyData(data);
        }
    }

    private RowView rowAt(float y) {
        for (RowView row : rows) {
            if (row.getVisibility() != VISIBLE || row.key == null) continue;
            float top = rowContainer.getTop() + row.getTop();
            if (y >= top && y <= top + row.getHeight()) return row;
        }
        return null;
    }

    private Drawable iconFor(String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        Drawable cached = iconCache.get(packageName);
        if (cached != null) return cached;
        try {
            Drawable icon = getContext().getPackageManager().getApplicationIcon(packageName);
            if (icon != null) iconCache.put(packageName, icon);
            return icon;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private final class RowView extends FrameLayout {
        private final FrameLayout card;
        private final android.graphics.drawable.GradientDrawable cardBackground;
        private final ImageView icon;
        private final TextView title;
        private final TextView content;
        private String key;
        private boolean clearable;
        private int iconSize = 1;
        private float titleSize = 12f;
        private float contentSize = 10f;

        RowView(Context context) {
            super(context);
            // Each notification sits on its own rounded card, matching the
            // native outer-screen widget look.
            card = new FrameLayout(context);
            cardBackground = new android.graphics.drawable.GradientDrawable();
            cardBackground.setCornerRadius(AppWidgetSlotView.cornerRadiusPx(this));
            card.setBackground(cardBackground);
            addView(card, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(1, 1);
            iconParams.gravity = Gravity.CENTER_VERTICAL;
            card.addView(icon, iconParams);

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            textColumn.setGravity(Gravity.CENTER_VERTICAL);
            title = new TextView(context);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setIncludeFontPadding(false);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            textColumn.addView(title, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            content = new TextView(context);
            content.setSingleLine(true);
            content.setEllipsize(TextUtils.TruncateAt.END);
            content.setIncludeFontPadding(false);
            textColumn.addView(content, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(textColumn, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            textColumn.setTag("textColumn");
            // Capsule keeps the fixed light-card look: white background, dark text.
            cardBackground.setColor(0xFFFFFFFF);
            title.setTextColor(0xFF1A1A1A);
            content.setTextColor(0x991A1A1A);
        }

        void bind(String rowKey, String packageName, String titleText, String contentText,
                  boolean rowClearable) {
            key = rowKey == null || rowKey.isEmpty() ? null : rowKey;
            clearable = rowClearable;
            Drawable drawable = iconFor(packageName);
            icon.setImageDrawable(drawable);
            icon.setVisibility(drawable == null ? GONE : VISIBLE);
            String line1 = titleText == null || titleText.isEmpty()
                    ? appLabel(packageName) : titleText;
            title.setText(line1);
            if (contentText == null || contentText.isEmpty() || contentText.equals(line1)) {
                content.setVisibility(GONE);
            } else {
                content.setVisibility(VISIBLE);
                content.setText(contentText);
            }
        }

        void applyMetrics(int newIconSize, float newTitleSize, float newContentSize,
                          int rowHeight) {
            int targetIcon = Math.max(1, newIconSize);
            LinearLayout.LayoutParams rowParams = (LinearLayout.LayoutParams) getLayoutParams();
            if (rowParams != null && rowParams.height != rowHeight) {
                rowParams.height = rowHeight;
                setLayoutParams(rowParams);
            }
            int horizontal = Math.max(4, Math.round(rowHeight * 0.08f));
            FrameLayout.LayoutParams cardParams = (FrameLayout.LayoutParams) card.getLayoutParams();
            cardParams.leftMargin = horizontal;
            cardParams.rightMargin = horizontal;
            cardParams.topMargin = Math.round(rowHeight * 0.05f);
            cardParams.bottomMargin = Math.round(rowHeight * 0.05f);
            card.setLayoutParams(cardParams);
            if (iconSize == targetIcon && titleSize == newTitleSize
                    && contentSize == newContentSize) return;
            iconSize = targetIcon;
            titleSize = newTitleSize;
            contentSize = newContentSize;
            FrameLayout.LayoutParams iconParams = (FrameLayout.LayoutParams) icon.getLayoutParams();
            iconParams.width = iconSize;
            iconParams.height = iconSize;
            iconParams.leftMargin = horizontal;
            icon.setLayoutParams(iconParams);
            View textColumn = findViewWithTag("textColumn");
            FrameLayout.LayoutParams textParams =
                    (FrameLayout.LayoutParams) textColumn.getLayoutParams();
            textParams.leftMargin = horizontal * 2 + iconSize;
            textParams.rightMargin = horizontal;
            textColumn.setLayoutParams(textParams);
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize);
            content.setTextSize(TypedValue.COMPLEX_UNIT_PX, contentSize);
            LinearLayout.LayoutParams contentParams =
                    (LinearLayout.LayoutParams) content.getLayoutParams();
            contentParams.topMargin = Math.round(rowHeight * 0.05f);
            content.setLayoutParams(contentParams);
        }

        void setSwipeOffset(float offset) {
            setTranslationX(offset);
            setAlpha(1f + offset / Math.max(1f, getWidth()) * 0.8f);
        }

        float getSwipeOffset() {
            return getTranslationX();
        }

        void resetSwipe() {
            animate().cancel();
            setTranslationX(0f);
            setAlpha(1f);
        }

        void animateDismiss(Runnable end) {
            animate().translationX(-getWidth()).alpha(0f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(end).start();
        }

        void animateBack(Runnable end) {
            animate().translationX(0f).alpha(1f).setDuration(150)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(end).start();
        }

        private String appLabel(String packageName) {
            if (packageName == null || packageName.isEmpty()) return "通知";
            try {
                PackageManager manager = getContext().getPackageManager();
                CharSequence label = manager.getApplicationLabel(
                        manager.getApplicationInfo(packageName, 0));
                return label == null ? packageName : label.toString();
            } catch (Throwable ignored) {
                return packageName;
            }
        }
    }
}
