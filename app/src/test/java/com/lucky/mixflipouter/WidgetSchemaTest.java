package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;

public final class WidgetSchemaTest {
    @Test
    public void componentRoundTripPreservesGeometryStyleAndAction() throws Exception {
        WidgetComponent source = WidgetComponent.button(
                "打开", "package", "com.example.app", 12, 34, 210, 72, 9);
        source.opacity = 0.65f;
        source.color = "#FF00FF00";

        WidgetComponent restored = WidgetComponent.fromJson(source.toJson());

        assertEquals(WidgetComponent.TYPE_BUTTON, restored.type);
        assertEquals(12f, restored.x, 0.001f);
        assertEquals(210f, restored.width, 0.001f);
        assertEquals(0.65f, restored.opacity, 0.001f);
        assertEquals("package", restored.actionType);
        assertEquals("com.example.app", restored.actionValue);
    }

    @Test
    public void legacyWidgetMigratesMediaAndButtonsIntoComponents() throws Exception {
        JSONObject legacy = new JSONObject();
        legacy.put("id", "legacy");
        legacy.put("name", "旧配置");
        legacy.put("mediaType", "video");
        legacy.put("mimeType", "video/mp4");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject()
                .put("label", "网易云")
                .put("type", "package")
                .put("value", "com.netease.cloudmusic"));
        legacy.put("actions", actions);

        WidgetConfig migrated = WidgetConfig.fromJson(legacy);

        assertFalse(migrated.components.isEmpty());
        assertEquals(WidgetComponent.TYPE_VIDEO, migrated.components.get(0).type);
        assertEquals(WidgetComponent.TYPE_BUTTON, migrated.components.get(1).type);
        assertEquals("网易云", migrated.components.get(1).content);
    }

    @Test
    public void widgetRoundTripKeepsComponentTree() throws Exception {
        WidgetConfig source = new WidgetConfig();
        source.id = "roundtrip";
        WidgetComponent text = new WidgetComponent();
        text.type = WidgetComponent.TYPE_TIME;
        text.content = "HH:mm";
        text.x = 70;
        text.y = 110;
        text.zIndex = 3;
        source.components.add(text);

        WidgetConfig restored = WidgetConfig.fromJson(source.toJson());

        assertEquals(1, restored.components.size());
        assertEquals(WidgetComponent.TYPE_TIME, restored.components.get(0).type);
        assertEquals("HH:mm", restored.components.get(0).content);
        assertEquals(3, restored.components.get(0).zIndex);
    }

    @Test
    public void legacyEditorMergePreservesCanvasOnlyComponentsAndButtonGeometry() {
        WidgetConfig config = new WidgetConfig();
        config.mediaType = "image";
        WidgetComponent text = new WidgetComponent();
        text.type = WidgetComponent.TYPE_TEXT;
        text.content = "保留我";
        text.x = 77;
        WidgetComponent button = WidgetComponent.button(
                "旧标题", "package", "old.package", 31, 402, 210, 72, 6);
        config.components.add(text);
        config.components.add(button);
        config.labels[0] = "新标题";
        config.actionTypes[0] = "uri";
        config.actionValues[0] = "https://example.com";

        config.mergeLegacyEditorState();

        assertTrue(config.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_TEXT.equals(component.type)
                        && "保留我".equals(component.content)
                        && component.x == 77));
        WidgetComponent mergedButton = config.components.stream()
                .filter(component -> WidgetComponent.TYPE_BUTTON.equals(component.type))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(31f, mergedButton.x, 0.001f);
        assertEquals("新标题", mergedButton.content);
        assertEquals("uri", mergedButton.actionType);
    }

    @Test
    public void parameterlessSystemActionCreatesButtonWithoutTargetValue() {
        WidgetConfig config = new WidgetConfig();
        config.labels[0] = "音量＋";
        config.actionTypes[0] = ActionSpec.VOLUME_UP;
        config.actionValues[0] = "";

        config.rebuildComponentsFromLegacy();

        assertEquals(1, config.components.size());
        assertEquals(WidgetComponent.TYPE_BUTTON, config.components.get(0).type);
        assertEquals(ActionSpec.VOLUME_UP, config.components.get(0).actionType);
        assertFalse(ActionSpec.requiresValue(ActionSpec.VOLUME_UP));
    }

    @Test
    public void mediaControlsAreParameterlessAndClassified() {
        assertTrue(ActionSpec.isMediaControl(ActionSpec.MEDIA_PREVIOUS));
        assertTrue(ActionSpec.isMediaControl(ActionSpec.MEDIA_PLAY_PAUSE));
        assertTrue(ActionSpec.isMediaControl(ActionSpec.MEDIA_NEXT));
        assertFalse(ActionSpec.requiresValue(ActionSpec.MEDIA_PLAY_PAUSE));
        assertFalse(ActionSpec.isMediaControl(ActionSpec.VOLUME_UP));
    }

    @Test
    public void playbackTextComponentsRoundTrip() throws Exception {
        WidgetComponent title = new WidgetComponent();
        title.type = WidgetComponent.TYPE_SONG_TITLE;
        title.content = "暂无播放";
        WidgetComponent artist = new WidgetComponent();
        artist.type = WidgetComponent.TYPE_ARTIST;
        artist.content = "歌手";

        assertEquals(WidgetComponent.TYPE_SONG_TITLE,
                WidgetComponent.fromJson(title.toJson()).type);
        assertEquals(WidgetComponent.TYPE_ARTIST,
                WidgetComponent.fromJson(artist.toJson()).type);
    }

    @Test
    public void lyricTimelineResolvesCurrentAndUpcomingLines() {
        ArrayList<LyricsStateStore.Line> lines = new ArrayList<>();
        LyricsStateStore.Line first = new LyricsStateStore.Line();
        first.start = 1_000;
        LyricsStateStore.Line second = new LyricsStateStore.Line();
        second.start = 3_500;
        LyricsStateStore.Line third = new LyricsStateStore.Line();
        third.start = 8_000;
        lines.add(first);
        lines.add(second);
        lines.add(third);

        assertEquals(-1, LyricsStateStore.findLine(lines, 999));
        assertEquals(0, LyricsStateStore.findLine(lines, 1_000));
        assertEquals(1, LyricsStateStore.findLine(lines, 7_999));
        assertEquals(2, LyricsStateStore.findLine(lines, 12_000));
    }

    @Test
    public void lyricComponentsRoundTripWithoutSchemaMigration() throws Exception {
        WidgetComponent current = new WidgetComponent();
        current.type = WidgetComponent.TYPE_LYRIC_CURRENT;
        WidgetComponent next = new WidgetComponent();
        next.type = WidgetComponent.TYPE_LYRIC_NEXT;

        assertEquals(WidgetComponent.TYPE_LYRIC_CURRENT,
                WidgetComponent.fromJson(current.toJson()).type);
        assertEquals(WidgetComponent.TYPE_LYRIC_NEXT,
                WidgetComponent.fromJson(next.toJson()).type);
    }
}
