package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Opt-in on-device write/readback probe; pass persistTarget to change the stored user choice. */
@RunWith(AndroidJUnit4.class)
public final class ImeReplacementPersistenceTest {
    @Test
    public void selectedTargetSurvivesFreshProviderReadAndRawFileRead() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Pass persistTarget explicitly to run this state-changing probe",
                arguments.containsKey("persistTarget"));
        String target = arguments.getString("persistTarget", "");
        assertTrue(target.isEmpty()
                || ImeReplacementSettings.WECHAT_PACKAGE.equals(target)
                || ImeReplacementSettings.DOUBAO_PACKAGE.equals(target));

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle extras = new Bundle();
        extras.putString("target", target);
        extras.putBoolean("enabled", !target.isEmpty());
        Bundle saved = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                "set_replacement_status", null, extras);
        assertNotNull(saved);
        assertTrue(saved.getString("error", ""), saved.getBoolean("saved", false));

        Bundle readBack = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                "get_replacement_status", null, null);
        assertNotNull(readBack);
        assertTrue(readBack.getBoolean("available"));
        assertTrue(readBack.getBoolean("configured"));
        assertEquals(target, readBack.getString("target", ""));

        Context deviceContext = context.createDeviceProtectedStorageContext();
        File file = new File(deviceContext.getFilesDir(), "ime_replacement.json");
        assertTrue("Device-protected preference file must exist", file.isFile());
        JSONObject raw = new JSONObject(new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8));
        assertTrue(raw.getBoolean("configured"));
        assertEquals(target, raw.getString("target"));
        assertEquals(!target.isEmpty(), raw.getBoolean("enabled"));
    }
}
