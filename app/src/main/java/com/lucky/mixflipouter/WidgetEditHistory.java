package com.lucky.mixflipouter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;

/** Bounded component-tree history. Media files and repository writes stay outside this stack. */
final class WidgetEditHistory {
    private static final int MAX_ENTRIES = 50;
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redo = new ArrayDeque<>();

    Snapshot capture(WidgetConfig config, String selectedId) {
        JSONArray components = new JSONArray();
        try {
            for (WidgetComponent component : config.components) components.put(component.toJson());
        } catch (Throwable error) {
            return null;
        }
        return new Snapshot(components.toString(), selectedId == null ? "" : selectedId);
    }

    void record(Snapshot before, Snapshot after) {
        if (before == null || after == null || before.componentsJson.equals(after.componentsJson)) return;
        if (undo.size() >= MAX_ENTRIES) undo.removeFirst();
        undo.addLast(before);
        redo.clear();
    }

    Snapshot undo(Snapshot current) {
        if (current == null || undo.isEmpty()) return null;
        if (redo.size() >= MAX_ENTRIES) redo.removeFirst();
        redo.addLast(current);
        return undo.removeLast();
    }

    Snapshot redo(Snapshot current) {
        if (current == null || redo.isEmpty()) return null;
        if (undo.size() >= MAX_ENTRIES) undo.removeFirst();
        undo.addLast(current);
        return redo.removeLast();
    }

    String restore(WidgetConfig config, Snapshot snapshot) {
        if (snapshot == null) return null;
        try {
            JSONArray array = new JSONArray(snapshot.componentsJson);
            config.components.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) config.components.add(WidgetComponent.fromJson(item));
            }
            return snapshot.selectedId;
        } catch (Throwable ignored) {
            return null;
        }
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    void clear() {
        undo.clear();
        redo.clear();
    }

    static final class Snapshot {
        final String componentsJson;
        final String selectedId;

        Snapshot(String componentsJson, String selectedId) {
            this.componentsJson = componentsJson;
            this.selectedId = selectedId;
        }
    }
}
