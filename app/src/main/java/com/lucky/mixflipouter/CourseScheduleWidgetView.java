package com.lucky.mixflipouter;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** High-contrast course card for the small Flip outer display. */
final class CourseScheduleWidgetView extends View {
    private static final float DESIGN_WIDTH = 440f;
    private static final float DESIGN_HEIGHT = 720f;
    private static final long REFRESH_VIEW_MS = 60_000L;
    private static final int[] ACCENTS = {
            0xff8bd5ca, 0xffffc777, 0xffc3a6ff, 0xffff8f9c, 0xff8bb8ff
    };

    private final WidgetConfig config;
    private final boolean preview;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<CourseScheduleClient.Course> courses = new ArrayList<>();
    private long updatedAt;
    private String error = "";
    private String scheduleState = "";
    private int currentWeek;
    private boolean hasData;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            readSchedule();
            if (isAttachedToWindow()) handler.postDelayed(this, REFRESH_VIEW_MS);
        }
    };

    CourseScheduleWidgetView(Context context, WidgetConfig config) {
        this(context, config, false);
    }

    CourseScheduleWidgetView(Context context, WidgetConfig config, boolean preview) {
        super(context);
        this.config = config;
        this.preview = preview;
        setBackgroundColor(Color.TRANSPARENT);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
            }
        });
        setClipToOutline(true);
        // This view sits over a native FlipHome widget, so handle taps here instead
        // of letting them reach the original card underneath.
        setClickable(true);
        setOnClickListener(view -> openFullSchedule());
        if (preview) {
            courses = sampleCourses();
            updatedAt = System.currentTimeMillis();
            currentWeek = 1;
            hasData = true;
            scheduleState = "preview";
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!preview) handler.post(refresh);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(refresh);
        super.onDetachedFromWindow();
    }

    private void readSchedule() {
        if (config == null) return;
        try {
            Bundle result = getContext().getContentResolver().call(
                    Contract.PROVIDER_URI, "get_course_schedule", config.id, null);
            if (result == null) return;
            courses = CourseScheduleClient.fromJson(result.getString("courses_json", "[]"));
            updatedAt = result.getLong("updated_at", 0L);
            currentWeek = result.getInt("current_week", 0);
            hasData = result.getBoolean("has_data", false);
            error = result.getString("error", "");
            scheduleState = hasData ? "loaded" : "waiting";
            postInvalidate();
        } catch (Throwable failure) {
            error = "课程数据暂时不可用";
            scheduleState = "failed";
            postInvalidate();
        }
    }

    private void openFullSchedule() {
        try {
            Uri scheduleUrl = Uri.parse(
                    "https://i.ai.mi.com/h5/ai-class-schedule-fe-new/#/home?source=query");
            Uri deepLink = new Uri.Builder()
                    .scheme("voiceassist")
                    .authority("aiweb")
                    .appendQueryParameter("url", scheduleUrl.toString())
                    .appendQueryParameter("flag", "268435456")
                    .appendQueryParameter("noBack", "false")
                    .appendQueryParameter("statusBarColor", "FFFFFF")
                    .appendQueryParameter("statusBarTextBlack", "true")
                    .appendQueryParameter("navigationBarColor", "FFFFFF")
                    .appendQueryParameter("needFloatWindow", "false")
                    .build();
            Intent intent = new Intent(Intent.ACTION_VIEW, deepLink)
                    .setPackage(Contract.VOICE_ASSIST_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Throwable failure) {
            Log.w("MixFlipCustom", "Unable to open XiaoAi course schedule", failure);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        canvas.save();
        canvas.scale(getWidth() / DESIGN_WIDTH, getHeight() / DESIGN_HEIGHT);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff111419);
        rect.set(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT);
        canvas.drawRoundRect(rect, 28, 28, paint);

        Date now = new Date();
        int weekDay = weekday(now);
        int week = currentWeek;
        drawText(canvas, "小爱课程表", 40, 36, 59, Color.WHITE, true, Paint.Align.LEFT);
        drawText(canvas, formatDate(now), 25, 38, 103, 0xffd0d2d8, false, Paint.Align.LEFT);
        String weekLabel = week > 0 ? "第 " + week + " 周" : "周次未同步";
        drawText(canvas, weekLabel, 24, DESIGN_WIDTH - 38, 103,
                0xff9be3d5, true, Paint.Align.RIGHT);

        int todayCount = 0;
        ArrayList<CourseScheduleClient.Course> todaysCourses = new ArrayList<>();
        for (CourseScheduleClient.Course course : courses) {
            if (course.day != weekDay || (week > 0 && !isInWeek(course, week))) continue;
            todaysCourses.add(course);
        }
        todaysCourses.sort((left, right) -> Integer.compare(
                firstSection(left), firstSection(right)));
        todayCount = todaysCourses.size();
        drawText(canvas, "今日课程", 29, 38, 157, 0xfff4f4f6, true, Paint.Align.LEFT);
        drawText(canvas, todayCount + " 门", 22, DESIGN_WIDTH - 38, 157,
                0xff9ea4af, false, Paint.Align.RIGHT);

        if (!hasData && !preview) {
            drawMessage(canvas, "等待读取手机课表", shortText(error.isEmpty()
                    ? "请先在小爱语音助手中打开课程表" : error, 30));
        } else if (week <= 0 && !preview) {
            drawMessage(canvas, "当前没有有效周次", "课程周次由手机小爱课程表提供");
        } else if (courses.isEmpty()) {
            String message;
            String detail;
            message = "本周没有课程安排";
            detail = "课程数据来自手机小爱课程表";
            drawMessage(canvas, message, detail);
        } else if (todaysCourses.isEmpty()) {
            drawMessage(canvas, "今天暂无课程", "可以安心安排自己的时间");
        } else {
            drawCourses(canvas, todaysCourses);
        }

        String footer = "preview".equals(scheduleState) ? "样式预览 · 示例课程"
                : updatedAt > 0 ? "更新于 " + new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(updatedAt)) : "课程数据来自手机小爱课程表";
        drawText(canvas, footer, 18, 38, 705, 0xffaeb3bd, false, Paint.Align.LEFT);
        canvas.restore();
    }

    private void drawCourses(Canvas canvas, List<CourseScheduleClient.Course> todaysCourses) {
        int shown = Math.min(3, todaysCourses.size());
        float cardTop = 168;
        float cardBottom = 662;
        float gap = 10;
        float cardHeight = (cardBottom - cardTop - gap * (shown - 1)) / shown;
        for (int index = 0; index < shown; index++) {
            CourseScheduleClient.Course course = todaysCourses.get(index);
            float top = cardTop + index * (cardHeight + gap);
            rect.set(26, top, DESIGN_WIDTH - 26, top + cardHeight);
            paint.setColor(0xff1c2129);
            canvas.drawRoundRect(rect, 22, 22, paint);
            rect.set(26, top + 20, 34, top + cardHeight - 20);
            paint.setColor(ACCENTS[Math.floorMod(course.name.hashCode(), ACCENTS.length)]);
            canvas.drawRoundRect(rect, 4, 4, paint);

            float centerY = top + cardHeight * 0.46f;
            float right = DESIGN_WIDTH - 43;
            float titleSize = Math.min(52f, 46f + Math.max(0f, cardHeight - 160f) * 0.03f);
            drawText(canvas, course.name, titleSize, 54, centerY,
                    Color.WHITE, true, Paint.Align.LEFT, 245f);
            drawText(canvas, sectionOnlyLabel(course), 25, right, centerY,
                    ACCENTS[Math.floorMod(course.name.hashCode(), ACCENTS.length)], true,
                    Paint.Align.RIGHT, 130f);
            String details = join(course.position, course.teacher);
            drawText(canvas, details.isEmpty() ? "小爱课程表" : details,
                    27, 54, centerY + 47, 0xffd0d2d8, false, Paint.Align.LEFT, 245f);
            String time = timeLabel(course);
            if (!time.isEmpty()) {
                drawText(canvas, time, 20, right, centerY + 47,
                        0xffaeb3bd, false, Paint.Align.RIGHT, 130f);
            }
        }
        if (todaysCourses.size() > shown) {
            drawText(canvas, "还有 " + (todaysCourses.size() - shown) + " 门课程",
                    20, DESIGN_WIDTH / 2f, 690,
                    0xffaeb3bd, false, Paint.Align.CENTER);
        }
    }

    private void drawMessage(Canvas canvas, String title, String subtitle) {
        drawText(canvas, title, 30, DESIGN_WIDTH / 2f, 360, 0xfff1f2f5,
                true, Paint.Align.CENTER);
        drawText(canvas, shortText(subtitle, 34), 20, DESIGN_WIDTH / 2f, 402,
                0xffaeb3bd, false, Paint.Align.CENTER);
    }

    private void drawText(Canvas canvas, String value, float size, float x, float y,
                          int color, boolean bold, Paint.Align align) {
        drawText(canvas, value, size, x, y, color, bold, align, 354f);
    }

    private void drawText(Canvas canvas, String value, float size, float x, float y,
                          int color, boolean bold, Paint.Align align, float maxWidth) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(bold ? android.graphics.Typeface.create("sans-serif-medium", 0)
                : android.graphics.Typeface.create("sans-serif", 0));
        paint.setTextAlign(align);
        String drawn = value == null ? "" : value;
        if (paint.measureText(drawn) > maxWidth) {
            int characters = paint.breakText(drawn, true, maxWidth - 16f, null);
            drawn = drawn.substring(0, Math.max(1, characters)) + "…";
        }
        canvas.drawText(drawn, x, y, paint);
    }

    private String formatDate(Date date) {
        return new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(date);
    }

    private int weekday(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        int value = calendar.get(Calendar.DAY_OF_WEEK);
        return value == Calendar.SUNDAY ? 7 : value - 1;
    }

    private boolean isInWeek(CourseScheduleClient.Course course, int week) {
        return course.weeks.isEmpty() || course.weeks.contains(week);
    }

    private int firstSection(CourseScheduleClient.Course course) {
        return course.sections.isEmpty() ? Integer.MAX_VALUE : course.sections.get(0);
    }

    private String sectionOnlyLabel(CourseScheduleClient.Course course) {
        if (course.sections.isEmpty()) return "课程";
        StringBuilder label = new StringBuilder("第 ");
        int index = 0;
        while (index < course.sections.size()) {
            int first = course.sections.get(index);
            int last = first;
            while (index + 1 < course.sections.size()
                    && course.sections.get(index + 1) == last + 1) {
                last = course.sections.get(++index);
            }
            if (label.length() > 2) label.append("、");
            label.append(first);
            if (last > first) label.append('–').append(last);
            index++;
        }
        label.append(" 节");
        return label.toString();
    }

    private String timeLabel(CourseScheduleClient.Course course) {
        if (course.startTime.isEmpty()) return "";
        return course.endTime.isEmpty()
                ? course.startTime : course.startTime + '–' + course.endTime;
    }

    private String join(String first, String second) {
        String left = first == null ? "" : first.trim();
        String right = second == null ? "" : second.trim();
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + "  ·  " + right;
    }

    private String shortText(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private List<CourseScheduleClient.Course> sampleCourses() {
        ArrayList<CourseScheduleClient.Course> sample = new ArrayList<>();
        int day = weekday(new Date());
        sample.add(new CourseScheduleClient.Course("高等数学", "张老师", "教学楼 A · 302",
                day, list(1, 2), list(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), "", "08:00", "09:30"));
        sample.add(new CourseScheduleClient.Course("大学英语", "李老师", "外语楼 201",
                day, list(3, 4), list(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), "", "10:00", "11:30"));
        sample.add(new CourseScheduleClient.Course("数据结构", "王老师", "实验楼 406",
                day, list(7, 8), list(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), "", "14:00", "15:30"));
        return sample;
    }

    private List<Integer> list(Integer... values) {
        ArrayList<Integer> result = new ArrayList<>();
        java.util.Collections.addAll(result, values);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
