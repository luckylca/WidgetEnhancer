package com.lucky.mixflipouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses standard NetEase LRC timelines and merges optional translated tracks. */
final class LrcParser {
    private static final Pattern TIME = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile(
            "(?im)^\\[offset:([+-]?\\d+)]\\s*$");

    static List<Line> parse(String lyric, String translation, String romanization) {
        TreeMap<Integer, Line> lines = new TreeMap<>();
        merge(lines, lyric, Track.CONTENT);
        merge(lines, translation, Track.TRANSLATION);
        merge(lines, romanization, Track.ROMANIZATION);
        ArrayList<Line> result = new ArrayList<>();
        for (Line line : lines.values()) {
            if (!line.content.isEmpty() || !line.translation.isEmpty()
                    || !line.romanization.isEmpty()) {
                result.add(line);
            }
        }
        for (int i = 0; i < result.size(); i++) {
            Line line = result.get(i);
            line.end = i + 1 < result.size()
                    ? Math.max(line.start, result.get(i + 1).start)
                    : line.start + 5_000;
        }
        return result;
    }

    private static void merge(Map<Integer, Line> lines, String raw, Track track) {
        if (raw == null || raw.isEmpty()) return;
        int offset = offset(raw);
        String[] rows = raw.split("\\r?\\n");
        for (String row : rows) {
            Matcher matcher = TIME.matcher(row);
            ArrayList<Integer> starts = new ArrayList<>();
            int contentStart = -1;
            while (matcher.find()) {
                long minutes = parseLong(matcher.group(1));
                long seconds = parseLong(matcher.group(2));
                long fraction = fractionMillis(matcher.group(3));
                long timestamp = (minutes * 60L + seconds) * 1_000L + fraction + offset;
                starts.add((int) Math.max(0, Math.min(Integer.MAX_VALUE, timestamp)));
                contentStart = matcher.end();
            }
            if (starts.isEmpty() || contentStart < 0) continue;
            String text = row.substring(contentStart).trim();
            for (Integer start : starts) {
                Line line = lines.get(start);
                if (line == null) {
                    line = new Line(start);
                    lines.put(start, line);
                }
                if (track == Track.CONTENT) line.content = text;
                else if (track == Track.TRANSLATION) line.translation = text;
                else line.romanization = text;
            }
        }
    }

    private static int offset(String raw) {
        Matcher matcher = OFFSET.matcher(raw);
        if (!matcher.find()) return 0;
        long value = parseLong(matcher.group(1));
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private static long fractionMillis(String fraction) {
        if (fraction == null || fraction.isEmpty()) return 0;
        long value = parseLong(fraction);
        if (fraction.length() == 1) return value * 100;
        if (fraction.length() == 2) return value * 10;
        return value;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static final class Line {
        final int start;
        int end;
        String content = "";
        String translation = "";
        String romanization = "";

        Line(int start) {
            this.start = start;
        }
    }

    private enum Track { CONTENT, TRANSLATION, ROMANIZATION }

    private LrcParser() {
    }
}
