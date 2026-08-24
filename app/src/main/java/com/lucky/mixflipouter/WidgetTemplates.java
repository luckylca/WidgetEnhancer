package com.lucky.mixflipouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Built-in examples expressed only as editable WidgetConfig data. */
final class WidgetTemplates {
    static List<Template> all() {
        ArrayList<Template> templates = new ArrayList<>();
        templates.add(new Template("photo", "照片", "全屏照片，可继续添加文字和按钮", photo()));
        templates.add(new Template("video", "视频", "循环静音视频背景", video()));
        templates.add(new Template("music", "音乐歌词", "封面、歌曲、歌手、歌词、进度和控制", music()));
        templates.add(new Template("controls", "快捷控制", "音量、手电筒和锁屏", controls()));
        templates.add(new Template("clock", "时钟", "大号时间与日期", clock()));
        return Collections.unmodifiableList(templates);
    }

    private static WidgetConfig photo() {
        WidgetConfig config = base("照片");
        config.enabled = false;
        config.mediaType = WidgetComponent.TYPE_IMAGE;
        config.mimeType = "image/*";
        config.components.add(WidgetComponent.media(WidgetComponent.TYPE_IMAGE));
        return config;
    }

    private static WidgetConfig video() {
        WidgetConfig config = base("视频");
        config.enabled = false;
        config.mediaType = WidgetComponent.TYPE_VIDEO;
        config.mimeType = "video/*";
        config.loop = true;
        config.mute = true;
        config.components.add(WidgetComponent.media(WidgetComponent.TYPE_VIDEO));
        return config;
    }

    private static WidgetConfig music() {
        WidgetConfig config = base("音乐歌词");
        WidgetComponent artwork = component(WidgetComponent.TYPE_ALBUM_ART,
                "", 24, 70, 150, 150, 0, 20);
        artwork.cornerRadius = 20;
        config.components.add(artwork);
        WidgetComponent title = component(WidgetComponent.TYPE_SONG_TITLE,
                "歌曲名称", 190, 78, 226, 58, 1, 30);
        title.textAlign = "left";
        config.components.add(title);
        WidgetComponent artist = component(WidgetComponent.TYPE_ARTIST,
                "歌手", 190, 138, 226, 46, 2, 22);
        artist.textAlign = "left";
        artist.color = "#CCFFFFFF";
        config.components.add(artist);
        config.components.add(component(WidgetComponent.TYPE_LYRIC_CURRENT,
                "当前歌词", 24, 270, 392, 82, 3, 30));
        WidgetComponent next = component(WidgetComponent.TYPE_LYRIC_NEXT,
                "下一句歌词", 24, 356, 392, 62, 4, 22);
        next.color = "#99FFFFFF";
        config.components.add(next);
        config.components.add(component(WidgetComponent.TYPE_PLAYBACK_PROGRESS,
                "", 34, 452, 372, 28, 5, 1));
        config.components.add(WidgetComponent.button("上一曲", ActionSpec.MEDIA_PREVIOUS,
                "", 24, 520, 120, 68, 6));
        config.components.add(WidgetComponent.button("播放", ActionSpec.MEDIA_PLAY_PAUSE,
                "", 160, 520, 120, 68, 7));
        config.components.add(WidgetComponent.button("下一曲", ActionSpec.MEDIA_NEXT,
                "", 296, 520, 120, 68, 8));
        config.syncLegacyActionsFromComponents();
        return config;
    }

    private static WidgetConfig controls() {
        WidgetConfig config = base("快捷控制");
        config.components.add(WidgetComponent.button("音量＋", ActionSpec.VOLUME_UP,
                "", 24, 230, 190, 92, 0));
        config.components.add(WidgetComponent.button("音量－", ActionSpec.VOLUME_DOWN,
                "", 226, 230, 190, 92, 1));
        config.components.add(WidgetComponent.button("手电筒", ActionSpec.FLASHLIGHT_TOGGLE,
                "", 24, 338, 190, 92, 2));
        config.components.add(WidgetComponent.button("锁屏", ActionSpec.LOCK_SCREEN,
                "", 226, 338, 190, 92, 3));
        config.syncLegacyActionsFromComponents();
        return config;
    }

    private static WidgetConfig clock() {
        WidgetConfig config = base("时钟");
        config.components.add(component(WidgetComponent.TYPE_TIME,
                "HH:mm", 20, 230, 400, 130, 0, 76));
        WidgetComponent date = component(WidgetComponent.TYPE_TIME,
                "yyyy年M月d日 EEEE", 36, 370, 368, 70, 1, 25);
        date.color = "#CCFFFFFF";
        config.components.add(date);
        return config;
    }

    private static WidgetConfig base(String name) {
        WidgetConfig config = new WidgetConfig();
        config.name = name;
        config.components.clear();
        return config;
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

    static final class Template {
        final String id;
        final String name;
        final String description;
        final WidgetConfig config;

        Template(String id, String name, String description, WidgetConfig config) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.config = config;
        }
    }

    private WidgetTemplates() {}
}
