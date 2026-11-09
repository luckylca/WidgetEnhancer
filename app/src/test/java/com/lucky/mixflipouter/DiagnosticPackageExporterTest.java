package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipFile;

public final class DiagnosticPackageExporterTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void fullPackageUsesTheSameSnapshotAndContainsRequiredDiagnosticEntries()
            throws Exception {
        File snapshot = temporaryFolder.newFolder("snapshot");
        String log = "{\"eventId\":\"snap\",\"timestamp\":9}\n";
        Files.write(new File(snapshot, "widgetenhancer.log.1").toPath(),
                log.getBytes(StandardCharsets.UTF_8));
        JSONArray files = new JSONArray().put(new JSONObject()
                .put("name", "widgetenhancer.log.1").put("bytes", log.length())
                .put("status", "ready"));
        String report = new JSONObject().put("app", new JSONObject().put("versionName", "0.9.11"))
                .put("device", new JSONObject().put("model", "test"))
                .put("packages", new JSONArray()).put("runtimeLogging", new JSONObject())
                .put("hooks", new JSONObject()).put("hookStatus", new JSONArray())
                .put("moduleProcesses", new JSONArray()).toString();
        File output = new File(temporaryFolder.getRoot(), "diagnostics.zip");

        DiagnosticPackageExporter.write(output, report, snapshot, files);

        try (ZipFile zip = new ZipFile(output)) {
            assertNotNull(zip.getEntry("diagnostics/environment.json"));
            assertNotNull(zip.getEntry("diagnostics/hook_status.json"));
            assertNotNull(zip.getEntry("diagnostics/report.json"));
            assertEquals(log, new String(zip.getInputStream(zip.getEntry(
                    "diagnostics/runtime_logs/widgetenhancer.log.1")).readAllBytes(),
                    StandardCharsets.UTF_8));
            assertEquals(4, zip.size());
        }
    }

    @Test
    public void incompleteSnapshotAbortsAndDeletesFullDiagnosticArchive() throws Exception {
        File snapshot = temporaryFolder.newFolder("missing");
        JSONArray files = new JSONArray().put(new JSONObject()
                .put("name", "widgetenhancer.log").put("bytes", 30).put("status", "ready"));
        File output = new File(temporaryFolder.getRoot(), "incomplete.zip");

        try {
            DiagnosticPackageExporter.write(output, "{}", snapshot, files);
            throw new AssertionError("A full diagnostics package must not be incomplete");
        } catch (java.io.IOException expected) {
            assertFalse(output.exists());
        }
    }
}
