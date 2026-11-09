package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ImeReplacementStoreTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writeIsDurableAcrossFreshReads() throws Exception {
        File directory = temporaryFolder.newFolder("device-protected");

        assertTrue(ImeReplacementStore.write(directory,
                ImeReplacementSettings.WECHAT_PACKAGE));
        ImeReplacementStore.State readBack = ImeReplacementStore.read(directory, "");

        assertTrue(readBack.configured);
        assertEquals(ImeReplacementSettings.WECHAT_PACKAGE, readBack.target);
        assertEquals("file", readBack.source);
    }

    @Test
    public void explicitDisabledLegacyValueMigratesAsConfiguredOff() throws Exception {
        File directory = temporaryFolder.newFolder("legacy-off");

        ImeReplacementStore.State legacy = ImeReplacementStore.read(directory,
                "<map><boolean name=\"replace_with_wechat\" value=\"false\" /></map>");

        assertTrue(legacy.configured);
        assertEquals("", legacy.target);
        assertEquals("legacy", legacy.source);
    }

    @Test
    public void absentLegacyValueRemainsUnconfiguredDefault() throws Exception {
        File directory = temporaryFolder.newFolder("default");

        ImeReplacementStore.State state = ImeReplacementStore.read(directory,
                "<map><int name=\"doubao_boot\" value=\"51\" /></map>");

        assertFalse(state.configured);
        assertEquals("", state.target);
        assertEquals("default", state.source);
    }

    @Test
    public void explicitWeChatLegacyValueCanBeMigrated() throws Exception {
        File directory = temporaryFolder.newFolder("legacy-wechat");

        ImeReplacementStore.State legacy = ImeReplacementStore.read(directory,
                "<map><boolean name=\"replace_with_wechat\" value=\"true\" />"
                        + "<string name=\"replacement_package\">com.tencent.wetype</string></map>");

        assertTrue(legacy.configured);
        assertEquals(ImeReplacementSettings.WECHAT_PACKAGE, legacy.target);
    }
}
