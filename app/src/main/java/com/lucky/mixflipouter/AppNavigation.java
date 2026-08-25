package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;

final class AppNavigation {
    static final int WIDGETS = 7001;
    static final int ABOUT = 7002;

    static View create(Activity activity, int selected) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
        bar.setBackgroundColor(color(activity,
                com.google.android.material.R.attr.colorSurfaceContainer, Color.WHITE));
        bar.addView(tab(activity, WIDGETS, "小部件", R.drawable.ic_widgets_24,
                selected == WIDGETS, selected), weighted());
        bar.addView(tab(activity, ABOUT, "关于", R.drawable.ic_info_24,
                selected == ABOUT, selected), weighted());
        return bar;
    }

    private static View tab(Activity activity, int id, String label, int iconResource,
                            boolean active, int selected) {
        LinearLayout tab = new LinearLayout(activity);
        tab.setId(id);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(!active);
        tab.setFocusable(true);
        tab.setContentDescription(label);

        FrameLayout indicator = new FrameLayout(activity);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(activity, 18));
        background.setColor(active
                ? color(activity, com.google.android.material.R.attr.colorSecondaryContainer,
                0xFFEADDFF)
                : Color.TRANSPARENT);
        indicator.setBackground(background);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconResource);
        int foreground = color(activity, active
                ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY);
        icon.setColorFilter(foreground);
        indicator.addView(icon, new FrameLayout.LayoutParams(
                dp(activity, 24), dp(activity, 24), Gravity.CENTER));
        tab.addView(indicator, new LinearLayout.LayoutParams(dp(activity, 64), dp(activity, 32)));

        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextSize(12);
        text.setTextColor(foreground);
        text.setGravity(Gravity.CENTER);
        text.setTypeface(null, active
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.topMargin = dp(activity, 2);
        tab.addView(text, textParams);

        if (!active) tab.setOnClickListener(v -> navigate(activity, id, selected));
        return tab;
    }

    private static void navigate(Activity activity, int target, int selected) {
        if (target == selected) return;
        if (target == WIDGETS) {
            if (activity instanceof AboutActivity) activity.finish();
            else activity.startActivity(new Intent(activity, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } else {
            activity.startActivity(new Intent(activity, AboutActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
        activity.overridePendingTransition(0, 0);
    }

    private static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, -1, 1f);
    }

    private static int color(Activity activity, int attribute, int fallback) {
        return MaterialColors.getColor(activity, attribute, fallback);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private AppNavigation() {
    }
}
