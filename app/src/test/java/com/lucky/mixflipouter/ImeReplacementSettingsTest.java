package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.Test;

public final class ImeReplacementSettingsTest {
    @Test
    public void unavailableProviderIsUnknownRatherThanDisabled() {
        ImeReplacementSettings.ReadResult result = ImeReplacementSettings.fromProvider(
                (Bundle) null);

        assertFalse(result.known);
        assertEquals("unavailable", result.source);
    }

    @Test
    public void providerDefaultIsKnownOffAndDoesNotNeedMigrationData() {
        ImeReplacementSettings.ReadResult result = ImeReplacementSettings.fromProvider(
                false, false, "");

        assertTrue(result.known);
        assertEquals("", result.target);
        assertEquals("provider_default", result.source);
    }

    @Test
    public void configuredDoubaoTargetIsPreserved() {
        ImeReplacementSettings.ReadResult result = ImeReplacementSettings.fromProvider(
                true, true, ImeReplacementSettings.DOUBAO_PACKAGE);

        assertTrue(result.known);
        assertEquals(ImeReplacementSettings.DOUBAO_PACKAGE, result.target);
    }

    @Test
    public void invalidConfiguredTargetIsUnknown() {
        ImeReplacementSettings.ReadResult result = ImeReplacementSettings.fromProvider(
                true, true, "com.example.unknown.ime");

        assertFalse(result.known);
    }

    @Test
    public void unavailableDurableStoreIsUnknownRatherThanDisabled() {
        ImeReplacementSettings.ReadResult result = ImeReplacementSettings.fromProvider(
                false, false, false, "", "disk read failed");

        assertFalse(result.known);
        assertTrue(result.error.endsWith("disk read failed"));
    }
}
