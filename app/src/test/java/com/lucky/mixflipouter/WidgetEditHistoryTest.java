package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WidgetEditHistoryTest {
    @Test
    public void undoAndRedoRestoreComponentTreeAndSelection() {
        WidgetConfig config = new WidgetConfig();
        WidgetComponent component = new WidgetComponent();
        component.id = "selected";
        component.content = "before";
        config.components.add(component);
        WidgetEditHistory history = new WidgetEditHistory();

        WidgetEditHistory.Snapshot before = history.capture(config, component.id);
        component.content = "after";
        WidgetEditHistory.Snapshot after = history.capture(config, component.id);
        history.record(before, after);

        WidgetEditHistory.Snapshot undo = history.undo(after);
        assertEquals("selected", history.restore(config, undo));
        assertEquals("before", config.components.get(0).content);
        assertTrue(history.canRedo());

        WidgetEditHistory.Snapshot current = history.capture(config, "selected");
        WidgetEditHistory.Snapshot redo = history.redo(current);
        history.restore(config, redo);
        assertEquals("after", config.components.get(0).content);
    }

    @Test
    public void noOpIsNotRecordedAndNewEditClearsRedo() {
        WidgetConfig config = new WidgetConfig();
        config.components.add(new WidgetComponent());
        WidgetEditHistory history = new WidgetEditHistory();
        WidgetEditHistory.Snapshot first = history.capture(config, null);
        history.record(first, history.capture(config, null));
        assertFalse(history.canUndo());

        config.components.get(0).x = 20;
        WidgetEditHistory.Snapshot second = history.capture(config, null);
        history.record(first, second);
        WidgetEditHistory.Snapshot undo = history.undo(second);
        history.restore(config, undo);
        assertTrue(history.canRedo());

        WidgetEditHistory.Snapshot newBefore = history.capture(config, null);
        config.components.get(0).y = 30;
        history.record(newBefore, history.capture(config, null));
        assertFalse(history.canRedo());
    }

    @Test
    public void historyKeepsOnlyLatestFiftyEdits() {
        WidgetConfig config = new WidgetConfig();
        config.components.add(new WidgetComponent());
        WidgetEditHistory history = new WidgetEditHistory();
        for (int value = 1; value <= 55; value++) {
            WidgetEditHistory.Snapshot before = history.capture(config, null);
            config.components.get(0).x = value;
            history.record(before, history.capture(config, null));
        }

        int undoCount = 0;
        WidgetEditHistory.Snapshot current = history.capture(config, null);
        while (true) {
            WidgetEditHistory.Snapshot previous = history.undo(current);
            if (previous == null) break;
            history.restore(config, previous);
            current = history.capture(config, null);
            undoCount++;
        }

        assertEquals(50, undoCount);
        assertEquals(5f, config.components.get(0).x, 0.001f);
    }
}
