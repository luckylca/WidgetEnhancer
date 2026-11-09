package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Regression: diagnostics must query ImeScopeStatusProvider, not ConfigProvider. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticImeProviderTest {
    @Test
    public void diagnosticSnapshotReflectsTheActualImePreferenceProvider() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle expectedSetting = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                "get_replacement_status", null, null);
        Bundle expectedScopes = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                "get_scope_status", null, null);
        assertNotNull(expectedSetting);
        assertNotNull(expectedScopes);
        Bundle repeatedSetting = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                "get_replacement_status", null, null);
        assertNotNull(repeatedSetting);
        assertEquals(expectedSetting.getBoolean("configured"),
                repeatedSetting.getBoolean("configured"));
        assertEquals(expectedSetting.getBoolean("enabled"), repeatedSetting.getBoolean("enabled"));
        assertEquals(expectedSetting.getString("target", ""),
                repeatedSetting.getString("target", ""));

        Context deviceContext = context.createDeviceProtectedStorageContext();
        File rawPreferencesFile = new File(deviceContext.getFilesDir(),
                "ime_replacement.json");
        String rawPreferences = rawPreferencesFile.isFile()
                ? new String(Files.readAllBytes(rawPreferencesFile.toPath()),
                StandardCharsets.UTF_8) : "<missing>";
        android.util.Log.i("DiagnosticImeProbe", "configured="
                + expectedSetting.getBoolean("configured") + ", enabled="
                + expectedSetting.getBoolean("enabled") + ", target="
                + expectedSetting.getString("target", "") + ", source="
                + expectedSetting.getString("source", "") + ", rawPath="
                + rawPreferencesFile.getAbsolutePath() + ", raw=" + rawPreferences);
        if (expectedSetting.getBoolean("configured")) {
            assertTrue("configured preference must have a durable file",
                    rawPreferencesFile.isFile());
            ImeReplacementStore.State disk = ImeReplacementStore.read(
                    deviceContext.getFilesDir(), "");
            assertEquals(expectedSetting.getBoolean("configured"), disk.configured);
            assertEquals(expectedSetting.getString("target", ""), disk.target);
        } else {
            assertTrue("unconfigured preference must remain the default",
                    !rawPreferencesFile.isFile());
        }

        JSONObject report = DiagnosticReport.collect(context, new WidgetRepository(context));
        JSONObject logging = report.getJSONObject("runtimeLogging");
        assertTrue(logging.getBoolean("imeReplacementProviderAvailable"));
        assertTrue(logging.getBoolean("imeScopeProviderAvailable"));
        assertEquals(expectedSetting.getBoolean("configured"), logging.getBoolean("imeReplacementConfigured"));
        assertEquals(expectedSetting.getString("target", ""),
                logging.getString("imeReplacementPackage"));
        assertEquals(expectedScopes.getBoolean("bootCountKnown"),
                logging.getBoolean("imeScopeBootCountKnown"));
    }
}
