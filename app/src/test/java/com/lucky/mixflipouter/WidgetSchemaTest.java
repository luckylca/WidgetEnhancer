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
    public void mediaTransformRoundTripPreservesCrop() throws Exception {
        WidgetComponent source = WidgetComponent.media(WidgetComponent.TYPE_VIDEO);
        source.mediaRotation = 90;
        source.mediaScale = 2.4f;
        source.mediaOffsetX = -0.35f;
        source.mediaOffsetY = 0.72f;

        WidgetComponent restored = WidgetComponent.fromJson(source.toJson());

        assertEquals(90, restored.mediaRotation);
        assertEquals(2.4f, restored.mediaScale, 0.001f);
        assertEquals(-0.35f, restored.mediaOffsetX, 0.001f);
        assertEquals(0.72f, restored.mediaOffsetY, 0.001f);
    }

    @Test
    public void legacyMediaDefaultsToCenteredCover() throws Exception {
        WidgetComponent restored = WidgetComponent.fromJson(new JSONObject()
                .put("type", WidgetComponent.TYPE_IMAGE));

        assertEquals(0, restored.mediaRotation);
        assertEquals(1f, restored.mediaScale, 0.001f);
        assertEquals(0f, restored.mediaOffsetX, 0.001f);
        assertEquals(0f, restored.mediaOffsetY, 0.001f);
    }

    @Test
    public void mediaTransformCalculatesCoverAndBoundedPan() {
        MediaTransform.Spec portrait = MediaTransform.calculate(
                1600, 900, 440, 720, 0, 1, 2, -2);
        assertEquals(720f, portrait.scaledHeight, 0.001f);
        assertTrue(portrait.scaledWidth > 440f);
        assertEquals(portrait.maxPanX, portrait.panX, 0.001f);
        assertEquals(0f, portrait.panY, 0.001f);

        MediaTransform.Spec rotated = MediaTransform.calculate(
                1600, 900, 440, 720, 90, 2, 0.5f, -0.5f);
        assertEquals(90, rotated.rotation);
        assertTrue(rotated.scaledWidth >= 440f);
        assertTrue(rotated.scaledHeight >= 720f);
        assertEquals(rotated.maxPanX * 0.5f, rotated.panX, 0.001f);
        assertEquals(rotated.maxPanY * -0.5f, rotated.panY, 0.001f);
    }

    @Test
    public void mediaLayoutRebuildKeepsTransformWhenKindChanges() {
        WidgetConfig config = new WidgetConfig();
        config.typeId = WidgetTypeRegistry.MEDIA;
        config.mediaType = WidgetComponent.TYPE_IMAGE;
        WidgetComponent media = WidgetComponent.media(WidgetComponent.TYPE_IMAGE);
        media.mediaRotation = 90;
        media.mediaScale = 3f;
        media.mediaOffsetX = 0.4f;
        config.components.add(media);

        config.mediaType = WidgetComponent.TYPE_VIDEO;
        WidgetTypeRegistry.buildMediaLayout(config);

        assertEquals(1, config.components.size());
        assertEquals(WidgetComponent.TYPE_VIDEO, config.components.get(0).type);
        assertEquals(90, config.components.get(0).mediaRotation);
        assertEquals(3f, config.components.get(0).mediaScale, 0.001f);
        assertEquals(0.4f, config.components.get(0).mediaOffsetX, 0.001f);
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
    public void mediaReplacementPreservesUnlimitedCanvasButtons() {
        WidgetConfig config = new WidgetConfig();
        WidgetComponent media = WidgetComponent.media(WidgetComponent.TYPE_IMAGE);
        media.mediaRotation = 90;
        media.mediaScale = 2f;
        media.mediaOffsetY = 0.5f;
        config.components.add(media);
        for (int index = 0; index < 6; index++) {
            config.components.add(WidgetComponent.button("按钮" + index, ActionSpec.VOLUME_UP,
                    "", 10, 20 + index * 30, 120, 60, index + 1));
        }

        config.mediaType = WidgetComponent.TYPE_VIDEO;
        config.syncMediaComponentFromLegacy();

        assertEquals(7, config.components.size());
        assertEquals(6, config.components.stream().filter(component ->
                WidgetComponent.TYPE_BUTTON.equals(component.type)).count());
        assertTrue(config.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_VIDEO.equals(component.type)));
        assertFalse(config.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_IMAGE.equals(component.type)));
        WidgetComponent replaced = config.components.stream().filter(component ->
                WidgetComponent.TYPE_VIDEO.equals(component.type)).findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals(90, replaced.mediaRotation);
        assertEquals(2f, replaced.mediaScale, 0.001f);
        assertEquals(0.5f, replaced.mediaOffsetY, 0.001f);
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
        assertTrue(ActionSpec.isDirectSystemControl(ActionSpec.DO_NOT_DISTURB_TOGGLE));
        assertTrue(ActionSpec.isDirectSystemControl(ActionSpec.AUTO_ROTATE_TOGGLE));
        assertFalse(ActionSpec.requiresValue(ActionSpec.DO_NOT_DISTURB_TOGGLE));
        assertTrue(ActionSpec.requiresValue(ActionSpec.QS_TILE));
    }

    @Test
    public void packageSanitizerKeepsDirectSystemControls() {
        WidgetConfig config = new WidgetConfig();
        config.components.add(WidgetComponent.button("勿扰", ActionSpec.DO_NOT_DISTURB_TOGGLE,
                "", 10, 10, 120, 60, 1));
        config.components.add(WidgetComponent.button("旋转", ActionSpec.AUTO_ROTATE_TOGGLE,
                "", 140, 10, 120, 60, 2));

        WidgetPackage.sanitize(config, false);

        assertEquals(ActionSpec.DO_NOT_DISTURB_TOGGLE,
                config.components.get(0).actionType);
        assertEquals(ActionSpec.AUTO_ROTATE_TOGGLE,
                config.components.get(1).actionType);
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
        WidgetComponent previous = new WidgetComponent();
        previous.type = WidgetComponent.TYPE_LYRIC_PREVIOUS;
        WidgetComponent next = new WidgetComponent();
        next.type = WidgetComponent.TYPE_LYRIC_NEXT;

        assertEquals(WidgetComponent.TYPE_LYRIC_PREVIOUS,
                WidgetComponent.fromJson(previous.toJson()).type);
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
    public void registeredWidgetTypesBuildFixedLayouts() {
        List<WidgetTypeRegistry.Type> types = WidgetTypeRegistry.all();
        assertTrue(types.stream().anyMatch(type -> WidgetTypeRegistry.MEDIA.equals(type.id)));
        assertTrue(types.stream().anyMatch(type -> WidgetTypeRegistry.MUSIC.equals(type.id)));
        assertTrue(types.stream().anyMatch(type -> WidgetTypeRegistry.SHORTCUTS.equals(type.id)));

        WidgetConfig music = WidgetTypeRegistry.create(WidgetTypeRegistry.MUSIC);
        assertEquals(WidgetTypeRegistry.MUSIC, music.typeId);
        assertTrue(music.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_ALBUM_ART.equals(component.type)));
        assertTrue(music.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(component.type)));
        assertTrue(music.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)));
        assertFalse(music.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_BUTTON.equals(component.type)));

        WidgetConfig shortcuts = WidgetTypeRegistry.create(WidgetTypeRegistry.SHORTCUTS);
        shortcuts.components.get(0).actionType = ActionSpec.VOLUME_UP;
        shortcuts.components.add(WidgetComponent.button(
                "按钮 2", ActionSpec.VOLUME_UP, "", 0, 0, 1, 1, 1));
        shortcuts.components.add(WidgetComponent.button(
                "按钮 3", ActionSpec.VOLUME_DOWN, "", 0, 0, 1, 1, 2));
        WidgetTypeRegistry.buildShortcutLayout(shortcuts);
        assertEquals(3, shortcuts.components.size());
        assertEquals(shortcuts.components.get(0).x, shortcuts.components.get(1).x, 0.001f);
        assertEquals(WidgetConfig.CANVAS_WIDTH / 2f,
                shortcuts.components.get(0).x + shortcuts.components.get(0).width / 2f, 0.001f);
        assertTrue(shortcuts.components.get(0).y < shortcuts.components.get(1).y);
        assertTrue(shortcuts.components.get(1).y < shortcuts.components.get(2).y);
    }

    @Test
    public void existingShortcutWidgetMigratesToCenteredAdaptiveTouchTarget() throws Exception {
        WidgetConfig old = new WidgetConfig();
        old.typeId = WidgetTypeRegistry.SHORTCUTS;
        old.components.add(WidgetComponent.button(
                "应用", ActionSpec.LAUNCH_APP, "com.example", 28, 200, 384, 96, 0));

        WidgetComponent migrated = WidgetConfig.fromJson(old.toJson()).components.get(0);

        assertEquals(WidgetConfig.CANVAS_WIDTH / 2f,
                migrated.x + migrated.width / 2f, 0.001f);
        assertEquals(WidgetConfig.CANVAS_HEIGHT / 2f,
                migrated.y + migrated.height / 2f, 0.001f);
        assertTrue(migrated.cornerRadius > 20f);
    }

    @Test
    public void shortcutTilesScaleDownAsTheVisibleCountGrows() {
        WidgetConfig one = shortcutConfig(1);
        WidgetConfig two = shortcutConfig(2);
        WidgetConfig three = shortcutConfig(3);

        assertEquals(320f, one.components.get(0).width, 0.001f);
        assertEquals(300f, two.components.get(0).width, 0.001f);
        assertEquals(260f, three.components.get(0).width, 0.001f);
        assertTrue(one.components.get(0).width > two.components.get(0).width);
        assertTrue(two.components.get(0).width > three.components.get(0).width);
    }

    @Test
    public void unboundShortcutIsKeptForEditingButExcludedFromRuntimeLayout() {
        WidgetConfig config = shortcutConfig(3);
        config.components.add(WidgetComponent.button(
                "按钮 4", ActionSpec.LAUNCH_APP, "", 0, 0, 1, 1, 3));

        WidgetTypeRegistry.buildShortcutLayout(config);

        assertEquals(4, config.components.size());
        assertEquals(3, config.components.stream().filter(component -> component.visible).count());
        assertEquals(260f, config.components.get(0).width, 0.001f);
        assertFalse(config.components.get(3).visible);
    }

    @Test
    public void legacyEightButtonLayoutShowsOnlyFirstSixInTwoByThreeTemplate() {
        WidgetConfig config = shortcutConfig(8);

        assertEquals(6, config.components.stream().filter(component -> component.visible).count());
        assertTrue(config.components.get(0).x < config.components.get(1).x);
        assertEquals(config.components.get(0).y, config.components.get(1).y, 0.001f);
        assertTrue(config.components.get(1).y < config.components.get(2).y);
        assertTrue(config.components.get(3).y < config.components.get(4).y);
        assertFalse(config.components.get(6).visible);
        assertFalse(config.components.get(7).visible);
    }

    @Test
    public void widgetTypeRoundTripAndLegacyInferenceRemainCompatible() throws Exception {
        WidgetConfig source = WidgetTypeRegistry.create(WidgetTypeRegistry.MUSIC);
        assertEquals(WidgetTypeRegistry.MUSIC,
                WidgetConfig.fromJson(source.toJson()).typeId);

        JSONObject legacyMedia = new JSONObject()
                .put("mediaType", WidgetComponent.TYPE_IMAGE)
                .put("mimeType", "image/jpeg");
        assertEquals(WidgetTypeRegistry.MEDIA,
                WidgetConfig.fromJson(legacyMedia).typeId);
    }

    @Test
    public void existingTwoLineMusicWidgetGainsPreviousLyricLine() throws Exception {
        WidgetConfig legacyMusic = new WidgetConfig();
        legacyMusic.typeId = WidgetTypeRegistry.MUSIC;
        WidgetComponent title = lyricComponent(WidgetComponent.TYPE_SONG_TITLE);
        title.textSize = 22;
        legacyMusic.components.add(title);
        WidgetComponent artist = lyricComponent(WidgetComponent.TYPE_ARTIST);
        artist.textSize = 16;
        legacyMusic.components.add(artist);
        legacyMusic.components.add(lyricComponent(WidgetComponent.TYPE_LYRIC_CURRENT));
        legacyMusic.components.add(lyricComponent(WidgetComponent.TYPE_LYRIC_NEXT));

        WidgetConfig migrated = WidgetConfig.fromJson(legacyMusic.toJson());

        assertTrue(migrated.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(component.type)));
        assertEquals(3, migrated.components.stream().filter(component ->
                component.type.startsWith("lyric_")).count());
        assertTrue(migrated.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_SONG_TITLE.equals(component.type)
                        && component.textSize == 24));
        assertTrue(migrated.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_ARTIST.equals(component.type)
                        && component.textSize == 18));
        assertTrue(migrated.components.stream().anyMatch(component ->
                WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)
                        && component.textSize == 32 && component.height == 120));
    }

    @Test
    public void musicGestureMappingMatchesProductContract() {
        assertEquals(ActionSpec.MEDIA_PLAY_PAUSE,
                WidgetTypeRegistry.musicGestureAction(false, 100, 720));
        assertEquals(ActionSpec.MEDIA_PREVIOUS,
                WidgetTypeRegistry.musicGestureAction(true, 100, 720));
        assertEquals(ActionSpec.MEDIA_NEXT,
                WidgetTypeRegistry.musicGestureAction(true, 600, 720));
        assertEquals(ActionSpec.VOLUME_UP,
                WidgetTypeRegistry.musicLongPressAction(100, 720));
        assertEquals(ActionSpec.VOLUME_DOWN,
                WidgetTypeRegistry.musicLongPressAction(360, 720));
        assertEquals(ActionSpec.VOLUME_DOWN,
                WidgetTypeRegistry.musicLongPressAction(600, 720));
    }

    @Test
    public void musicTouchYieldsToHostAfterMovementExceedsSlop() {
        assertFalse(MediaWidgetView.shouldYieldToHost(100, 100, 106, 108, 12));
        assertTrue(MediaWidgetView.shouldYieldToHost(100, 100, 140, 102, 12));
        assertTrue(MediaWidgetView.shouldYieldToHost(100, 100, 101, 140, 12));
    }

    private static WidgetComponent lyricComponent(String type) {
        WidgetComponent component = new WidgetComponent();
        component.type = type;
        component.width = 352;
        component.height = 60;
        return component;
    }

    private static WidgetConfig shortcutConfig(int count) {
        WidgetConfig config = new WidgetConfig();
        config.typeId = WidgetTypeRegistry.SHORTCUTS;
        for (int index = 0; index < count; index++) {
            config.components.add(WidgetComponent.button(
                    Integer.toString(index + 1), ActionSpec.VOLUME_UP, "",
                    0, 0, 1, 1, index));
        }
        WidgetTypeRegistry.buildShortcutLayout(config);
        return config;
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
        button.mediaRotation = 270;
        button.mediaScale = Float.POSITIVE_INFINITY;
        button.mediaOffsetX = -8;
        button.mediaOffsetY = Float.NaN;
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
        assertEquals(0, button.mediaRotation);
        assertEquals(1f, button.mediaScale, 0.001f);
        assertEquals(-1f, button.mediaOffsetX, 0.001f);
        assertEquals(0f, button.mediaOffsetY, 0.001f);
        assertEquals(10_000, button.zIndex);
    }
}
