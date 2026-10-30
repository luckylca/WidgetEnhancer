package com.lucky.mixflipouter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Data model and parser for the on-device XiaoAi course-table snapshot. */
final class CourseScheduleClient {
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    static List<Course> fromJson(String encoded) {
        ArrayList<Course> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                ArrayList<Integer> sections = integerValues(item.opt("sections"));
                ArrayList<Integer> weeks = integerValues(item.opt("weeks"));
                String name = item.optString("name", "").trim();
                int day = item.optInt("day", 0);
                if (!name.isEmpty() && day >= 1 && day <= 7 && !sections.isEmpty()) {
                    result.add(new Course(name, item.optString("teacher", ""),
                            item.optString("position", ""), day, sections, weeks,
                            item.optString("style", ""), item.optString("start_time", ""),
                            item.optString("end_time", "")));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static ArrayList<Integer> integerValues(Object raw) {
        Set<Integer> values = new LinkedHashSet<>();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) addText(values, String.valueOf(array.opt(i)));
        } else if (raw != null) {
            addText(values, String.valueOf(raw));
        }
        ArrayList<Integer> result = new ArrayList<>(values);
        result.sort(Integer::compareTo);
        return result;
    }

    private static void addText(Set<Integer> values, String text) {
        Matcher matcher = NUMBER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group());
                if (value > 0 && value <= 30) values.add(value);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    static final class Course {
        final String name;
        final String teacher;
        final String position;
        final int day;
        final List<Integer> sections;
        final List<Integer> weeks;
        final String style;
        final String startTime;
        final String endTime;

        Course(String name, String teacher, String position, int day,
               List<Integer> sections, List<Integer> weeks, String style,
               String startTime, String endTime) {
            this.name = name;
            this.teacher = teacher;
            this.position = position;
            this.day = day;
            this.sections = new ArrayList<>(sections);
            this.weeks = new ArrayList<>(weeks);
            this.style = style;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    private CourseScheduleClient() {
    }
}
