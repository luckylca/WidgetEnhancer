package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

public final class DiagnosticReportTest {
    @Test
    public void widgetSummaryContainsOnlyAggregateData() throws Exception {
        WidgetConfig widget = new WidgetConfig();
        widget.id = "secret-widget-id";
        widget.name = "secret-widget-name";
        widget.mediaType = WidgetComponent.TYPE_IMAGE;
        WidgetComponent button = WidgetComponent.button("secret-label", ActionSpec.LAUNCH_APP,
                "com.secret.target", 0, 0, 100, 60, 1);
        widget.components.add(button);
        WidgetComponent malicious = new WidgetComponent();
        malicious.type = "secret-component-type";
        malicious.actionType = "secret-action-type";
        widget.components.add(malicious);

        JSONObject summary = DiagnosticReport.summarizeWidgets(
                Collections.singletonList(widget), 1234, false, id -> 4096);
        String serialized = summary.toString();

        assertEquals(WidgetRepository.SCHEMA_VERSION, summary.getInt("schemaVersion"));
        assertEquals(1, summary.getInt("count"));
        assertEquals(4096, summary.getLong("mediaBytes"));
        assertEquals(1, summary.getJSONObject("mediaTypes").getInt("image"));
        assertEquals(1, summary.getJSONObject("componentTypes").getInt("button"));
        assertEquals(1, summary.getJSONObject("componentTypes").getInt("unknown"));
        assertEquals(1, summary.getJSONObject("actionTypes").getInt("package"));
        assertEquals(1, summary.getJSONObject("actionTypes").getInt("unknown"));
        assertFalse(serialized.contains("secret-widget-id"));
        assertFalse(serialized.contains("secret-widget-name"));
        assertFalse(serialized.contains("secret-label"));
        assertFalse(serialized.contains("com.secret.target"));
        assertFalse(serialized.contains("secret-component-type"));
        assertFalse(serialized.contains("secret-action-type"));
    }

    @Test
    public void reportSchemaStartsAtOne() {
        assertEquals(1, DiagnosticReport.SCHEMA_VERSION);
    }
}
