package com.lucky.mixflipouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Central registry for user-facing Widget types and their fixed runtime layouts. */
final class WidgetTypeRegistry {
    static final String MEDIA = "media";
    static final String MUSIC = "music";
    static final String SHORTCUTS = "shortcuts";

    private static final List<Type> TYPES;

    static {
        ArrayList<Type> types = new ArrayList<>();
        types.add(new Type(MEDIA, "媒体展示", "显示一张图片或循环视频"));
        types.add(new Type(MUSIC, "音乐", "歌词与媒体控制"));
        types.add(new Type(SHORTCUTS, "快捷按钮", "纵向排列系统操作或应用入口"));
        TYPES = Collections.unmodifiableList(types);
    }

    static List<Type> all() {
        return TYPES;
    }

    static Type get(String id) {
        for (Type type : TYPES) if (type.id.equals(id)) return type;
        return null;
    }

    static String resolve(WidgetConfig config) {
        if (config != null && get(config.typeId) != null) return config.typeId;
        if (config != null) {
            for (WidgetComponent component : config.components) {
                if (isPlaybackComponent(component)) return MUSIC;
            }
            if (WidgetComponent.TYPE_IMAGE.equals(config.mediaType)
                    || WidgetComponent.TYPE_VIDEO.equals(config.mediaType)) return MEDIA;
            for (WidgetComponent component : config.components) {
                if (WidgetComponent.TYPE_BUTTON.equals(component.type)) return SHORTCUTS;
            }
        }
        return SHORTCUTS;
    }

    static WidgetConfig create(String id) {
        Type type = get(id);
        if (type == null) throw new IllegalArgumentException("Unknown Widget type: " + id);
        WidgetConfig config = new WidgetConfig();
        config.typeId = id;
        config.name = type.name;
        config.components.clear();
        if (MUSIC.equals(id)) {
            buildMusicLayout(config);
        } else if (SHORTCUTS.equals(id)) {
            config.components.add(WidgetComponent.button(
                    "按钮 1", ActionSpec.LAUNCH_APP, "", 0, 0, 0, 0, 0));
            buildShortcutLayout(config);
        }
        config.syncLegacyActionsFromComponents();
        return config;
    }

    static void normalize(WidgetConfig config) {
        config.typeId = resolve(config);
        if (MEDIA.equals(config.typeId)) {
            buildMediaLayout(config);
        } else if (MUSIC.equals(config.typeId)) {
            buildMusicLayout(config);
        } else if (SHORTCUTS.equals(config.typeId)) {
            buildShortcutLayout(config);
        }
        config.syncLegacyActionsFromComponents();
    }

    static void buildMediaLayout(WidgetConfig config) {
        config.components.clear();
        if (WidgetComponent.TYPE_IMAGE.equals(config.mediaType)
                || WidgetComponent.TYPE_VIDEO.equals(config.mediaType)) {
            config.components.add(WidgetComponent.media(config.mediaType));
        }
    }

    static void buildMusicLayout(WidgetConfig config) {
        config.mediaType = "none";
        config.mimeType = "application/octet-stream";
        config.components.clear();

        WidgetComponent background = component(WidgetComponent.TYPE_ALBUM_ART,
                "", 0, 0, 440, 720, 0, 1);
        background.fillMode = "cover";
        config.components.add(background);

        WidgetComponent title = component(WidgetComponent.TYPE_SONG_TITLE,
                "暂无播放", 34, 42, 372, 44, 2, 24);
        title.color = "#CCFFFFFF";
        config.components.add(title);

        WidgetComponent artist = component(WidgetComponent.TYPE_ARTIST,
                "", 34, 88, 372, 34, 3, 18);
        artist.color = "#99FFFFFF";
        config.components.add(artist);

        WidgetComponent previous = component(WidgetComponent.TYPE_LYRIC_PREVIOUS,
                "上一句歌词", 44, 200, 352, 72, 4, 22);
        previous.color = "#88FFFFFF";
        config.components.add(previous);
        config.components.add(component(WidgetComponent.TYPE_LYRIC_CURRENT,
                "当前歌词", 34, 278, 372, 120, 5, 32));
        WidgetComponent next = component(WidgetComponent.TYPE_LYRIC_NEXT,
                "下一句歌词", 44, 406, 352, 72, 6, 22);
        next.color = "#99FFFFFF";
        config.components.add(next);

        config.components.add(component(WidgetComponent.TYPE_PLAYBACK_PROGRESS,
                "", 40, 635, 360, 24, 7, 1));
    }

    static void ensureThreeLineMusicLayout(WidgetConfig config) {
        if (!MUSIC.equals(resolve(config))) return;
        WidgetComponent previous = null;
        WidgetComponent current = null;
        WidgetComponent next = null;
        WidgetComponent title = null;
        WidgetComponent artist = null;
        WidgetComponent progress = null;
        for (WidgetComponent component : config.components) {
            if (WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(component.type)) previous = component;
            else if (WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)) current = component;
            else if (WidgetComponent.TYPE_LYRIC_NEXT.equals(component.type)) next = component;
            else if (WidgetComponent.TYPE_SONG_TITLE.equals(component.type)) title = component;
            else if (WidgetComponent.TYPE_ARTIST.equals(component.type)) artist = component;
            else if (WidgetComponent.TYPE_PLAYBACK_PROGRESS.equals(component.type)) progress = component;
        }
        if (previous == null) {
            previous = component(WidgetComponent.TYPE_LYRIC_PREVIOUS,
                    "上一句歌词", 44, 200, 352, 72, 4, 22);
            config.components.add(previous);
        }
        applyLyricFrame(previous, 44, 200, 352, 72, 4, 22, "#88FFFFFF");
        if (current != null) applyLyricFrame(
                current, 34, 278, 372, 120, 5, 32, "#FFFFFFFF");
        if (next != null) applyLyricFrame(
                next, 44, 406, 352, 72, 6, 22, "#99FFFFFF");
        if (title != null) title.textSize = 24;
        if (artist != null) artist.textSize = 18;
        if (progress != null) progress.zIndex = 7;
        config.components.sort(Comparator.comparingInt(component -> component.zIndex));
    }

    private static void applyLyricFrame(WidgetComponent component,
                                        float x, float y, float width, float height,
                                        int zIndex, float textSize, String color) {
        component.x = x;
        component.y = y;
        component.width = width;
        component.height = height;
        component.zIndex = zIndex;
        component.textSize = textSize;
        component.color = color;
        component.visible = true;
    }

    static void buildShortcutLayout(WidgetConfig config) {
        ArrayList<WidgetComponent> buttons = new ArrayList<>();
        ArrayList<WidgetComponent> configured = new ArrayList<>();
        for (WidgetComponent component : config.components) {
            if (!WidgetComponent.TYPE_BUTTON.equals(component.type)) continue;
            buttons.add(component);
            if (isConfiguredShortcut(component)) configured.add(component);
        }
        config.mediaType = "none";
        config.mimeType = "application/octet-stream";
        config.components.clear();

        int count = Math.min(configured.size(), ButtonLayoutEngine.MAX_BUTTONS);
        if (count == 0) {
            for (WidgetComponent button : buttons) {
                button.visible = false;
                config.components.add(button);
            }
            return;
        }
        ButtonLayoutEngine.Layout layout = ButtonLayoutEngine.layout(
                count, WidgetConfig.CANVAS_WIDTH, WidgetConfig.CANVAS_HEIGHT);
        int visibleIndex = 0;
        for (int index = 0; index < buttons.size(); index++) {
            WidgetComponent button = buttons.get(index);
            if (!isConfiguredShortcut(button) || visibleIndex >= count) {
                button.visible = false;
                button.zIndex = index;
                config.components.add(button);
                continue;
            }
            ButtonLayoutEngine.Item item = layout.items.get(visibleIndex);
            button.x = item.x;
            button.y = item.y;
            button.width = item.width;
            button.height = item.height;
            button.zIndex = index;
            button.cornerRadius = Math.max(16f, item.iconSize * 0.24f);
            button.visible = true;
            button.locked = true;
            config.components.add(button);
            visibleIndex++;
        }
    }

    static boolean isConfiguredShortcut(WidgetComponent component) {
        String actionType = component.actionType == null ? "" : component.actionType.trim();
        if (actionType.isEmpty()) return false;
        if (!ActionSpec.requiresValue(actionType)) return true;
        return component.actionValue != null && !component.actionValue.trim().isEmpty();
    }

    static String musicGestureAction(boolean doubleTap, float y, float height) {
        if (!doubleTap) return ActionSpec.MEDIA_PLAY_PAUSE;
        return y < height / 2f ? ActionSpec.MEDIA_PREVIOUS : ActionSpec.MEDIA_NEXT;
    }

    static String musicLongPressAction(float y, float height) {
        return y < height / 2f ? ActionSpec.VOLUME_UP : ActionSpec.VOLUME_DOWN;
    }

    private static boolean isPlaybackComponent(WidgetComponent component) {
        return WidgetComponent.TYPE_SONG_TITLE.equals(component.type)
                || WidgetComponent.TYPE_ARTIST.equals(component.type)
                || WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(component.type)
                || WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)
                || WidgetComponent.TYPE_LYRIC_NEXT.equals(component.type)
                || WidgetComponent.TYPE_PLAYBACK_PROGRESS.equals(component.type)
                || WidgetComponent.TYPE_ALBUM_ART.equals(component.type);
    }

    private static WidgetComponent component(String type, String content,
                                             float x, float y, float width, float height,
                                             int zIndex, float textSize) {
        WidgetComponent component = new WidgetComponent();
        component.type = type;
        component.content = content;
        component.x = x;
        component.y = y;
        component.width = width;
        component.height = height;
        component.zIndex = zIndex;
        component.textSize = textSize;
        return component;
    }

    static final class Type {
        final String id;
        final String name;
        final String description;

        Type(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }

    private WidgetTypeRegistry() {
    }
}
