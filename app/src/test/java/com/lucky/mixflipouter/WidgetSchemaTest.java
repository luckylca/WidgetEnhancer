package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

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

    @Test
    public void playbackProgressComponentRoundTrips() throws Exception {
        WidgetComponent progress = new WidgetComponent();
        progress.type = WidgetComponent.TYPE_PLAYBACK_PROGRESS;
        progress.width = 360;
        progress.height = 30;

        WidgetComponent restored = WidgetComponent.fromJson(progress.toJson());
        assertEquals(WidgetComponent.TYPE_PLAYBACK_PROGRESS, restored.type);
        assertEquals(360f, restored.width, 0.001f);
    }

    @Test
    public void albumArtComponentRoundTrips() throws Exception {
        WidgetComponent artwork = new WidgetComponent();
        artwork.type = WidgetComponent.TYPE_ALBUM_ART;
        artwork.cornerRadius = 20;
        WidgetComponent restored = WidgetComponent.fromJson(artwork.toJson());
        assertEquals(WidgetComponent.TYPE_ALBUM_ART, restored.type);
        assertEquals(20f, restored.cornerRadius, 0.001f);
    }

    @Test
    public void builtInTemplatesAreEditableWidgetConfigData() {
        List<WidgetTemplates.Template> templates = WidgetTemplates.all();
        assertEquals(5, templates.size());
        WidgetTemplates.Template music = templates.stream()
                .filter(template -> "music".equals(template.id))
                .findFirst().orElseThrow(AssertionError::new);
        assertTrue(music.config.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_ALBUM_ART.equals(component.type)));
        assertTrue(music.config.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)));
        assertTrue(music.config.components.stream().anyMatch(component ->
                ActionSpec.MEDIA_PLAY_PAUSE.equals(component.actionType)));
        assertEquals("上一曲", music.config.labels[0]);
        assertEquals(ActionSpec.MEDIA_PLAY_PAUSE, music.config.actionTypes[1]);
        WidgetTemplates.Template photo = templates.stream()
                .filter(template -> "photo".equals(template.id))
                .findFirst().orElseThrow(AssertionError::new);
        assertFalse(photo.config.enabled);
        assertEquals(WidgetComponent.TYPE_IMAGE, photo.config.mediaType);
    }

    @Test
    public void packageSanitizerClampsGeometryAndDegradesMissingMedia() {
        WidgetConfig config = new WidgetConfig();
        config.name = "  imported  ";
        config.mediaType = WidgetComponent.TYPE_IMAGE;
        config.mimeType = "image/jpeg";
        config.components.add(WidgetComponent.media(WidgetComponent.TYPE_IMAGE));
        WidgetComponent text = new WidgetComponent();
        text.type = WidgetComponent.TYPE_TEXT;
        text.x = -500;
        text.y = 900;
        text.width = 900;
        text.height = 900;
        config.components.add(text);

        WidgetPackage.sanitize(config, false);

        assertEquals("imported", config.name);
        assertEquals("none", config.mediaType);
        assertFalse(config.enabled);
        assertFalse(config.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_IMAGE.equals(component.type)));
        assertEquals(0f, text.x, 0.001f);
        assertTrue(text.y < WidgetConfig.CANVAS_HEIGHT);
        assertTrue(text.width <= WidgetConfig.CANVAS_WIDTH);
        assertTrue(text.height <= WidgetConfig.CANVAS_HEIGHT - text.y);
    }

    @Test
    public void packageSanitizerRejectsUnsafeStyleAndActionValues() {
        WidgetConfig config = new WidgetConfig();
        WidgetComponent button = WidgetComponent.button(
                "动作", "future_privileged_action", "secret", 1, 1, 100, 80, 100_000);
        button.opacity = Float.NaN;
        button.cornerRadius = Float.POSITIVE_INFINITY;
        button.textSize = 50_000;
        button.color = "not-a-color";
        button.fillMode = "future-mode";
        button.textAlign = "diagonal";
        config.components.add(button);

        WidgetPackage.sanitize(config, false);

        assertEquals("", button.actionType);
        assertEquals("", button.actionValue);
        assertTrue(Float.isFinite(button.opacity));
        assertTrue(button.cornerRadius <= 40f);
        assertEquals(200f, button.textSize, 0.001f);
        assertEquals("#FFFFFFFF", button.color);
        assertEquals("cover", button.fillMode);
        assertEquals("center", button.textAlign);
        assertEquals(10_000, button.zIndex);
    }
}
