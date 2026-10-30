package com.lucky.mixflipouter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;

/** Local cache published by the XiaoAi voice-assistant process through the Xposed bridge. */
final class CourseScheduleStore {
    private static final String PREFS = "xiaomi_course_schedule_cache";
    private static final String COURSES = "courses_json";
    private static final String UPDATED = "updated_at";
    private static final String CURRENT_WEEK = "current_week";

    static Bundle publish(Context context, Bundle extras) {
        Bundle result = new Bundle();
        if (extras == null) {
            result.putBoolean("ok", false);
            result.putString("message", "没有收到手机课程数据");
            return result;
        }
        String json = extras.getString(COURSES, "[]");
        if (json.length() > 512 * 1024) {
            result.putBoolean("ok", false);
            result.putString("message", "课程数据超出允许大小");
            return result;
        }
        try {
            JSONArray courses = new JSONArray(json);
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(COURSES, courses.toString())
                    .putInt(CURRENT_WEEK, Math.max(0, extras.getInt(CURRENT_WEEK, 0)))
                    .putInt("total_weeks", Math.max(0, extras.getInt("total_weeks", 0)))
                    .putLong(UPDATED, System.currentTimeMillis())
                    .apply();
            result.putBoolean("ok", true);
            result.putInt("course_count", courses.length());
            return result;
        } catch (Exception error) {
            result.putBoolean("ok", false);
            result.putString("message", "手机返回的课程数据格式无法识别");
            return result;
        }
    }

    static Bundle snapshot(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Bundle result = new Bundle();
        String json = prefs.getString(COURSES, "[]");
        long updatedAt = prefs.getLong(UPDATED, 0L);
        result.putString(COURSES, json);
        result.putLong(UPDATED, updatedAt);
        result.putInt(CURRENT_WEEK, prefs.getInt(CURRENT_WEEK, 0));
        result.putInt("total_weeks", prefs.getInt("total_weeks", 0));
        result.putBoolean("source_available", isVoiceAssistantInstalled(context));
        result.putBoolean("has_data", updatedAt > 0);
        if (updatedAt == 0L) {
            result.putString("error", isVoiceAssistantInstalled(context)
                    ? "请先在手机上打开小爱课程表，让课程数据同步到本地"
                    : "这台手机未检测到小米小爱语音助手");
        }
        return result;
    }

    private static boolean isVoiceAssistantInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(Contract.VOICE_ASSIST_PACKAGE, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private CourseScheduleStore() {
    }
}
