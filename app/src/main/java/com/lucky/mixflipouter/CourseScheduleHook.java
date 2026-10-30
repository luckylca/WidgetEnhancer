package com.lucky.mixflipouter;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Reads XiaoAi's in-memory course table inside its own voice-assistant process. */
final class CourseScheduleHook {
    private static final String HELPER = "d60.n";
    private static final String WEB_STORAGE_KEY = "ai-class-schedule-fe-recover_content_home";
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean WEB_HOOK_INSTALLED = new AtomicBoolean();
    private static volatile Context appContext;
    private static volatile String lastSnapshot = "";
    private static volatile String lastWebSnapshot = "";

    static void install(XC_LoadPackage.LoadPackageParam param) {
        if (!Contract.VOICE_ASSIST_PACKAGE.equals(param.packageName)
                || !INSTALLED.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            Context context = (Context) hook.args[0];
                            if (context == null || !Contract.VOICE_ASSIST_PACKAGE.equals(
                                    context.getPackageName())) return;
                            appContext = context.getApplicationContext();
                            installCourseDataHooks(param.classLoader,
                                    param.processName == null
                                            || Contract.VOICE_ASSIST_PACKAGE.equals(
                                            param.processName));
                        }
                    });
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log("MixFlipCustom: XiaoAi course hook attach failed: " + error);
        }
    }

    private static void installCourseDataHooks(ClassLoader loader, boolean loadLocalCache) {
        try {
            Class<?> helper = XposedHelpers.findClass(HELPER, loader);
            hookAfter(helper, "handleCourseEntity", new Class<?>[]{
                    XposedHelpers.findClass("com.miui.voiceassist.data.entities.CourseBeanEntity",
                            loader), String.class});
            hookAfter(helper, "buildWeekTableCourseData", new Class<?>[0]);
            hookAfter(helper, "buildDayTableCourseData", new Class<?>[0]);
            if (loadLocalCache) {
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> syncFromLocalCache(helper), 1_000L);
            }
            XposedBridge.log("MixFlipCustom: XiaoAi local course data hooks installed");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: XiaoAi course data hook unavailable: " + error);
        }
        if (loadLocalCache) installWebScheduleHooks(loader);
    }

    /** The visible XiaoAi schedule is stored by its H5 page in WebView localStorage. */
    private static void installWebScheduleHooks(ClassLoader loader) {
        if (!WEB_HOOK_INSTALLED.compareAndSet(false, true)) return;
        XC_MethodHook readAfterPageLoad = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam hook) {
                WebView view = null;
                for (Object argument : hook.args) {
                    if (argument instanceof WebView) {
                        view = (WebView) argument;
                        break;
                    }
                }
                if (view != null) {
                    scheduleWebScheduleRead(view);
                }
            }
        };
        boolean installed = false;
        try {
            Class<?> commonWebView = XposedHelpers.findClass(
                    "com.xiaomi.voiceassistant.commonweb.CommonWebView", loader);
            Class<?> pageListener = XposedHelpers.findClass(
                    "com.xiaomi.voiceassistant.web.container.AiWebActivity$c", loader);
            XposedHelpers.findAndHookMethod(pageListener, "onPageFinished",
                    commonWebView, readAfterPageLoad);
            installed = true;
            XposedBridge.log("MixFlipCustom: XiaoAi H5 page-finished hook attached");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: XiaoAi H5 page hook unavailable: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadUrl",
                    String.class, Map.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            String url = hook.args.length > 0 ? string(hook.args[0]) : "";
                            if (isCourseScheduleUrl(url) && hook.thisObject instanceof WebView) {
                                scheduleWebScheduleRead((WebView) hook.thisObject);
                            }
                        }
                    });
            installed = true;
            XposedBridge.log("MixFlipCustom: XiaoAi base WebView load hook attached");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: XiaoAi WebView load hook unavailable: " + error);
        }
        try {
            Class<?> commonWebView = XposedHelpers.findClass(
                    "com.xiaomi.voiceassistant.commonweb.CommonWebView", loader);
            XposedBridge.hookAllMethods(commonWebView, "loadUrl", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    String url = hook.args.length > 0 ? string(hook.args[0]) : "";
                    if (isCourseScheduleUrl(url) && hook.thisObject instanceof WebView) {
                        scheduleWebScheduleRead((WebView) hook.thisObject);
                    }
                }
            });
            installed = true;
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: XiaoAi CommonWebView load hook unavailable: " + error);
        }
        if (installed) {
            XposedBridge.log("MixFlipCustom: XiaoAi H5 course storage hook installed");
        }
    }

    private static boolean isCourseScheduleUrl(String url) {
        return url != null && (url.contains("ai-class-schedule-fe")
                || url.contains("precache/ai-schedule"));
    }

    private static void scheduleWebScheduleRead(WebView view) {
        Handler handler = new Handler(Looper.getMainLooper());
        for (long delay : new long[]{1_000L, 3_500L, 8_000L, 15_000L}) {
            handler.postDelayed(() -> readWebSchedule(view), delay);
        }
    }

    private static void readWebSchedule(WebView view) {
        try {
            if (view == null || !isCourseScheduleUrl(view.getUrl())) return;
            String script = "(function(){try{return localStorage.getItem('"
                    + WEB_STORAGE_KEY + "')||'';}catch(e){return '';}})()";
            view.evaluateJavascript(script, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    String payload = decodeJavascriptString(value);
                    if (payload.isEmpty()) {
                        return;
                    }
                    Context context = view.getContext().getApplicationContext();
                    publishWebSchedule(payload, context);
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: XiaoAi H5 schedule read failed: " + error);
        }
    }

    private static String decodeJavascriptString(String encoded) {
        if (encoded == null || encoded.isEmpty() || "null".equals(encoded)) return "";
        try {
            Object value = new JSONTokener(encoded).nextValue();
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void publishWebSchedule(String encoded, Context webContext) {
        Context context = webContext == null ? appContext : webContext;
        if (context == null) return;
        try {
            JSONObject root = new JSONObject(encoded);
            JSONArray sourceCourses = firstArray(root, "courses", "courseList", "courseData");
            if (sourceCourses == null) {
                JSONObject data = root.optJSONObject("data");
                if (data != null) sourceCourses = firstArray(data,
                        "courses", "courseList", "courseData");
            }
            if (sourceCourses == null) {
                return;
            }
            int currentWeek = firstInteger(root, "currentWeek", "current_week", "presentWeek");
            int totalWeeks = firstInteger(root, "totalWeeks", "totalWeek", "total_week");
            JSONArray normalized = new JSONArray();
            for (int i = 0; i < sourceCourses.length(); i++) {
                JSONObject course = sourceCourses.optJSONObject(i);
                if (course == null) continue;
                String name = firstString(course, "name", "courseName", "title");
                int day = firstInteger(course, "day", "weekday", "weekDay");
                Object sections = firstValue(course, "sections", "section", "sectionList",
                        "sectionNum", "sectionNumbers", "sectionsList");
                if (name.isEmpty() || day < 1 || day > 7 || sections == null) continue;
                JSONObject item = new JSONObject();
                item.put("name", name);
                item.put("teacher", firstString(course, "teacher", "teacherName"));
                item.put("position", firstString(course, "position", "room", "classroom",
                        "location"));
                item.put("day", day);
                item.put("sections", numbers(sections));
                item.put("weeks", numbers(firstValue(course, "weeks", "weekList", "week",
                        "weekStr", "courseWeeks")));
                item.put("style", firstString(course, "style", "colorStyle"));
                JSONObject sectionTime = firstObject(course, "sectionsData", "sectionTime",
                        "timeData");
                item.put("start_time", sectionTime == null
                        ? firstString(course, "start_time", "startTime", "start")
                        : firstString(sectionTime, "s", "start", "startTime"));
                item.put("end_time", sectionTime == null
                        ? firstString(course, "end_time", "endTime", "end")
                        : firstString(sectionTime, "e", "end", "endTime"));
                normalized.put(item);
            }
            String fingerprint = currentWeek + ":" + totalWeeks + ":" + normalized;
            if (fingerprint.equals(lastWebSnapshot)) return;
            lastWebSnapshot = fingerprint;
            Bundle payload = new Bundle();
            payload.putString("courses_json", normalized.toString());
            payload.putInt("current_week", currentWeek);
            payload.putInt("total_weeks", totalWeeks);
            JSONArray finalCourses = normalized;
            int finalSourceCount = sourceCourses.length();
            int finalCurrentWeek = currentWeek;
            new Thread(() -> {
                try {
                    Bundle result = context.getContentResolver().call(Contract.PROVIDER_URI,
                            "publish_course_schedule", null, payload);
                    XposedBridge.log("MixFlipCustom: XiaoAi H5 schedule published, source="
                            + finalSourceCount + ", usable=" + finalCourses.length()
                            + ", week=" + finalCurrentWeek + ", accepted="
                            + (result != null && result.getBoolean("ok")));
                } catch (Throwable error) {
                    XposedBridge.log("MixFlipCustom: XiaoAi H5 schedule publish failed: " + error);
                }
            }, "mixflip-course-h5-publish").start();
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: XiaoAi H5 schedule parse failed: " + error);
        }
    }

    private static JSONArray firstArray(JSONObject object, String... names) {
        for (String name : names) {
            JSONArray value = object.optJSONArray(name);
            if (value != null) return value;
        }
        return null;
    }

    private static JSONObject firstObject(JSONObject object, String... names) {
        for (String name : names) {
            JSONObject value = object.optJSONObject(name);
            if (value != null) return value;
        }
        return null;
    }

    private static Object firstValue(JSONObject object, String... names) {
        for (String name : names) {
            Object value = object.opt(name);
            if (value != null && value != JSONObject.NULL) return value;
        }
        return null;
    }

    private static String firstString(JSONObject object, String... names) {
        Object value = firstValue(object, names);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int firstInteger(JSONObject object, String... names) {
        Object value = firstValue(object, names);
        return integer(value, 0);
    }

    /** Rebuilds the helper's in-memory view from its existing local cache, without requesting network data. */
    private static void syncFromLocalCache(Class<?> helperClass) {
        try {
            Object helper = findHelperSingleton(helperClass);
            if (helper == null) {
                XposedBridge.log("MixFlipCustom: XiaoAi helper singleton was not found");
                return;
            }
            Object raw = XposedHelpers.callMethod(helper, "isThereCacheData");
            if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
                XposedBridge.log("MixFlipCustom: XiaoAi has no cached local course table yet");
                return;
            }
            ClassLoader loader = helperClass.getClassLoader();
            Class<?> beanClass = XposedHelpers.findClass(
                    "com.miui.voiceassist.data.entities.CourseBeanEntity", loader);
            Class<?> gsonClass = XposedHelpers.findClass("com.google.gson.Gson", loader);
            Object gson = XposedHelpers.newInstance(gsonClass);
            Object bean = XposedHelpers.callMethod(gson, "fromJson", raw, beanClass);
            if (bean == null) return;
            Object parsed = XposedHelpers.callMethod(helper, "parseWeekTableCourseData", bean);
            if (parsed instanceof Boolean && (Boolean) parsed) {
                XposedHelpers.callMethod(helper, "buildWeekTableCourseData");
                XposedHelpers.callMethod(helper, "buildDayTableCourseData");
            }
            publishSnapshot(helper);
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: XiaoAi local cache read failed: " + error);
        }
    }

    private static Object findHelperSingleton(Class<?> helperClass) throws Exception {
        for (Field field : helperClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !helperClass.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (value != null) return value;
        }
        return null;
    }

    private static void hookAfter(Class<?> owner, String method, Class<?>[] parameters) {
        Object[] signature = new Object[parameters.length + 1];
        for (int i = 0; i < parameters.length; i++) signature[i] = parameters[i];
        signature[parameters.length] = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam hook) {
                try {
                    publishSnapshot(hook.thisObject);
                } catch (Throwable error) {
                    XposedBridge.log("MixFlipCustom: XiaoAi course snapshot failed: " + error);
                }
            }
        };
        XposedHelpers.findAndHookMethod(owner, method, signature);
    }

    private static void publishSnapshot(Object helper) throws Exception {
        Context context = appContext;
        if (context == null || helper == null) return;
        Object rawMap = XposedHelpers.callMethod(helper, "getMCurrentWeekCourseMap");
        if (!(rawMap instanceof Map)) return;
        Map<?, ?> days = (Map<?, ?>) rawMap;
        JSONArray courses = new JSONArray();
        for (Map.Entry<?, ?> dayEntry : days.entrySet()) {
            int day = integer(dayEntry.getKey(), 0);
            if (day < 1 || day > 7 || !(dayEntry.getValue() instanceof List)) continue;
            for (Object course : (List<?>) dayEntry.getValue()) {
                JSONObject item = courseJson(course, day);
                if (item != null) courses.put(item);
            }
        }
        int currentWeek = integer(XposedHelpers.callMethod(helper, "getMCurrentWeek"), 0);
        int totalWeeks = 0;
        try {
            Object setting = XposedHelpers.callMethod(helper, "getMCourseSetting");
            if (setting != null) totalWeeks = integer(XposedHelpers.callMethod(
                    setting, "getTotalWeek"), 0);
        } catch (Throwable ignored) {
        }
        String json = courses.toString();
        String fingerprint = currentWeek + ":" + totalWeeks + ":" + json;
        if (fingerprint.equals(lastSnapshot)) return;
        lastSnapshot = fingerprint;
        Bundle payload = new Bundle();
        payload.putString("courses_json", json);
        payload.putInt("current_week", currentWeek);
        payload.putInt("total_weeks", totalWeeks);
        new Thread(() -> {
            try {
                Bundle result = context.getContentResolver().call(Contract.PROVIDER_URI,
                        "publish_course_schedule", null, payload);
                XposedBridge.log("MixFlipCustom: XiaoAi course snapshot published, courses="
                        + (result == null ? -1 : result.getInt("course_count", -1))
                        + ", week=" + currentWeek);
            } catch (Throwable error) {
                XposedBridge.log("MixFlipCustom: XiaoAi course publish failed: " + error);
            }
        }, "mixflip-course-publish").start();
    }

    private static JSONObject courseJson(Object course, int day) throws Exception {
        if (course == null) return null;
        String name = string(XposedHelpers.callMethod(course, "getName"));
        if (name.isEmpty()) return null;
        JSONObject item = new JSONObject();
        item.put("name", name);
        item.put("teacher", string(XposedHelpers.callMethod(course, "getTeacher")));
        item.put("position", string(XposedHelpers.callMethod(course, "getPosition")));
        item.put("day", day);
        item.put("sections", numbers(XposedHelpers.callMethod(course, "getSections")));
        item.put("weeks", numbers(XposedHelpers.callMethod(course, "getWeeks")));
        item.put("style", string(XposedHelpers.callMethod(course, "getStyle")));
        Object sectionTimes = XposedHelpers.callMethod(course, "getSectionsData");
        if (sectionTimes != null) {
            item.put("start_time", string(XposedHelpers.callMethod(sectionTimes, "getS")));
            item.put("end_time", string(XposedHelpers.callMethod(sectionTimes, "getE")));
        }
        return item;
    }

    private static JSONArray numbers(Object raw) {
        JSONArray values = new JSONArray();
        Matcher matcher = NUMBER.matcher(string(raw));
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group());
                if (value > 0 && value <= 30) values.put(value);
            } catch (NumberFormatException ignored) {
            }
        }
        return values;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private CourseScheduleHook() {
    }
}
