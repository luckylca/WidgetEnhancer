package com.lucky.mixflipouter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates the full diagnostics archive from the same immutable snapshot as the log ZIP. */
final class DiagnosticPackageExporter {
    static void write(File outputZip, String reportJson, File snapshotDirectory,
                      JSONArray fileMetadata) throws Exception {
        File parent = outputZip.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create diagnostics export directory");
        }
        try {
            JSONObject report = new JSONObject(reportJson);
            JSONObject environment = new JSONObject()
                    .put("app", report.optJSONObject("app"))
                    .put("device", report.optJSONObject("device"))
                    .put("packages", report.optJSONObject("packages"))
                    .put("runtimeLogging", report.optJSONObject("runtimeLogging"));
            JSONObject hookStatus = new JSONObject()
                    .put("hooks", report.optJSONObject("hooks"))
                    .put("hookStatus", report.optJSONArray("hookStatus"))
                    .put("moduleProcesses", report.optJSONArray("moduleProcesses"));
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                    new FileOutputStream(outputZip)))) {
                put(zip, "diagnostics/environment.json", environment.toString(2));
                put(zip, "diagnostics/hook_status.json", hookStatus.toString(2));
                put(zip, "diagnostics/report.json", report.toString(2));
                LogArchiveExporter.addSnapshotFiles(zip, snapshotDirectory, fileMetadata,
                        "diagnostics/runtime_logs/", true);
            }
        } catch (Throwable error) {
            if (outputZip.exists()) outputZip.delete();
            throw error;
        }
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private DiagnosticPackageExporter() { }
}
