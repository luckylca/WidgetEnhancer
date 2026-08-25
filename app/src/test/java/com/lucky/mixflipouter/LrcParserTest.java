package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class LrcParserTest {
    @Test
    public void parsesFractionsMultipleTagsAndTrackText() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "[00:01.20][00:02.345] hello\n[00:04] world",
                "[00:01.200] 你好\n[00:04.000] 世界", "");

        assertEquals(3, lines.size());
        assertEquals(1_200, lines.get(0).start);
        assertEquals("hello", lines.get(0).content);
        assertEquals("你好", lines.get(0).translation);
        assertEquals(2_345, lines.get(1).start);
        assertEquals(4_000, lines.get(1).end);
        assertEquals("world", lines.get(2).content);
    }

    @Test
    public void appliesOffsetsAndIgnoresMetadata() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "[ar:Artist]\n[offset:-250]\n[01:02.5] line", "", "");

        assertEquals(1, lines.size());
        assertEquals(62_250, lines.get(0).start);
        assertEquals("line", lines.get(0).content);
    }
}
