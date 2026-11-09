package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public final class LogExportIntentTest {
    @Test
    public void safSaveIntentRequestsZipAndSuggestedFilename() {
        Intent intent = DiagnosticsActivity.createLogSaveIntent("MixFlip-Logs-20261107-120000.zip");

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals("application/zip", intent.getType());
        assertEquals("MixFlip-Logs-20261107-120000.zip",
                intent.getStringExtra(Intent.EXTRA_TITLE));
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0);
    }

    @Test
    public void shareIntentUsesReadableFileProviderUriAndPreservesArchiveBytes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "log_exports");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File archive = new File(directory, "MixFlip-Logs-test.zip");
        byte[] contents = "zip test payload".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(archive)) {
            output.write(contents);
        }
        try {
            Intent chooser = DiagnosticsActivity.createLogShareIntent(context, archive);
            assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
            Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
            assertNotNull(send);
            assertEquals(Intent.ACTION_SEND, send.getAction());
            assertEquals("application/zip", send.getType());
            assertTrue((chooser.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            Uri uri = send.getParcelableExtra(Intent.EXTRA_STREAM);
            assertNotNull(uri);
            assertEquals("content", uri.getScheme());
            assertEquals(context.getPackageName() + ".fileprovider", uri.getAuthority());
            assertNotNull(send.getClipData());
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                assertNotNull(input);
                byte[] actual = new byte[contents.length];
                assertEquals(contents.length, input.read(actual));
                assertEquals(new String(contents, StandardCharsets.UTF_8),
                        new String(actual, StandardCharsets.UTF_8));
            }
            assertTrue(archive.isFile());
            assertTrue(LogArchiveLifecycle.completeDelivery(archive,
                    LogArchiveLifecycle.Outcome.SHARE_CLOSED));
            assertTrue("share completion must retain the source ZIP", archive.isFile());
            assertTrue(LogArchiveLifecycle.completeDelivery(archive,
                    LogArchiveLifecycle.Outcome.SAF_SAVE_SUCCEEDED));
            assertTrue("a completed SAF save may delete the temporary ZIP", !archive.exists());
        } finally {
            archive.delete();
        }
    }

}
