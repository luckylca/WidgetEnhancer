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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class LogArchiveExporterTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void exportsAllCurrentAndRotatedFilesAsOriginalJsonlAndAddsManifest() throws Exception {
        File snapshot = temporaryFolder.newFolder("snapshot");
        JSONArray metadata = new JSONArray();
        String[] names = {"widgetenhancer.log.4", "widgetenhancer.log.3",
                "widgetenhancer.log.2", "widgetenhancer.log.1", "widgetenhancer.log"};
        for (int i = 0; i < names.length; i++) {
            String raw = new JSONObject().put("timestamp", 1000 + i)
                    .put("level", i == 4 ? "ERROR" : "INFO")
                    .put("stackTrace", i == 4 ? repeat("exception-frame", 50) : "")
                    .toString() + "\n";
            Files.write(new File(snapshot, names[i]).toPath(), raw.getBytes(StandardCharsets.UTF_8));
            metadata.put(new JSONObject().put("name", names[i])
                    .put("bytes", raw.getBytes(StandardCharsets.UTF_8).length)
                    .put("status", "ready"));
        }
        File output = new File(temporaryFolder.getRoot(), "all-logs.zip");

        LogArchiveExporter.write(output, snapshot, metadata, "0.9.11 (35)", "");

        try (ZipFile zip = new ZipFile(output)) {
            for (int i = 0; i < names.length; i++) {
                ZipEntry entry = zip.getEntry(names[i]);
                assertNotNull("missing archive entry " + names[i], entry);
                String expected = new String(Files.readAllBytes(new File(snapshot, names[i]).toPath()),
                        StandardCharsets.UTF_8);
                String actual = new String(zip.getInputStream(entry).readAllBytes(),
                        StandardCharsets.UTF_8);
                assertEquals(expected, actual);
            }
            JSONObject manifest = new JSONObject(new String(zip.getInputStream(
                    zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("0.9.11 (35)", manifest.getString("moduleVersion"));
            assertEquals(5, manifest.getJSONArray("files").length());
            JSONObject current = manifest.getJSONArray("files").getJSONObject(4);
            assertEquals(1004L, current.getLong("firstTimestamp"));
            assertEquals(1004L, current.getLong("lastTimestamp"));
            assertEquals("exported", current.getString("status"));
        }
    }

    @Test
    public void emptyLogSetProducesValidManifestOnlyArchive() throws Exception {
        File snapshot = temporaryFolder.newFolder("empty");
        File output = new File(temporaryFolder.getRoot(), "empty.zip");

        LogArchiveExporter.write(output, snapshot, new JSONArray(), "test", "");

        try (ZipFile zip = new ZipFile(output)) {
            assertNotNull(zip.getEntry("manifest.json"));
            assertEquals(1, zip.size());
            JSONObject manifest = new JSONObject(new String(zip.getInputStream(
                    zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(0, manifest.getJSONArray("files").length());
        }
    }

    @Test
    public void unavailableFileIsRecordedAndDoesNotStopOtherFiles() throws Exception {
        File snapshot = temporaryFolder.newFolder("partial");
        String contents = "{\"timestamp\":2000,\"level\":\"INFO\"}\n";
        Files.write(new File(snapshot, "widgetenhancer.log").toPath(),
                contents.getBytes(StandardCharsets.UTF_8));
        JSONArray metadata = new JSONArray()
                .put(new JSONObject().put("name", "widgetenhancer.log.1")
                        .put("bytes", 200).put("status", "snapshot_failed")
                        .put("error", "permission denied"))
                .put(new JSONObject().put("name", "widgetenhancer.log")
                        .put("bytes", contents.length()).put("status", "ready"));
        File output = new File(temporaryFolder.getRoot(), "partial.zip");

        LogArchiveExporter.write(output, snapshot, metadata, "test", "flush warning");

        try (ZipFile zip = new ZipFile(output)) {
            assertNotNull(zip.getEntry("widgetenhancer.log"));
            assertEquals(null, zip.getEntry("widgetenhancer.log.1"));
            JSONObject manifest = new JSONObject(new String(zip.getInputStream(
                    zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("flush warning", manifest.getString("flushWarning"));
            assertEquals("snapshot_failed", manifest.getJSONArray("files")
                    .getJSONObject(0).getString("status"));
            assertEquals("permission denied", manifest.getJSONArray("files")
                    .getJSONObject(0).getString("error"));
            assertEquals("exported", manifest.getJSONArray("files")
                    .getJSONObject(1).getString("status"));
        }
    }

    @Test
    public void missingSnapshotRecordsReadFailureAndLargeStackExportsCompletely() throws Exception {
        File snapshot = temporaryFolder.newFolder("large");
        String stack = repeat("frame-0123456789-abcdef\n", 80_000);
        String line = new JSONObject().put("timestamp", 98765).put("level", "ERROR")
                .put("stackTrace", stack).toString() + "\n";
        Files.write(new File(snapshot, "widgetenhancer.log").toPath(),
                line.getBytes(StandardCharsets.UTF_8));
        JSONArray metadata = new JSONArray()
                .put(new JSONObject().put("name", "widgetenhancer.log.2")
                        .put("bytes", 15).put("status", "ready"))
                .put(new JSONObject().put("name", "widgetenhancer.log")
                        .put("bytes", line.getBytes(StandardCharsets.UTF_8).length)
                        .put("status", "ready"));
        File output = new File(temporaryFolder.getRoot(), "large.zip");

        LogArchiveExporter.write(output, snapshot, metadata, "test", "");

        try (ZipFile zip = new ZipFile(output)) {
            assertNotNull(zip.getEntry("widgetenhancer.log"));
            String actual = new String(zip.getInputStream(zip.getEntry("widgetenhancer.log"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(line, actual);
            JSONObject manifest = new JSONObject(new String(zip.getInputStream(
                    zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("read_failed", manifest.getJSONArray("files")
                    .getJSONObject(0).getString("status"));
            assertTrue(manifest.getJSONArray("files").getJSONObject(0).has("error"));
            assertFalse(output.length() == 0);
        }
    }

    @Test
    public void snapshotRemainsConsistentWhenTheSourceRotatesDuringExport() throws Exception {
        File root = temporaryFolder.newFolder("writer-root");
        LogFileWriter writer = new LogFileWriter(root, 80);
        String original = new JSONObject().put("eventId", "before")
                .put("timestamp", 10).put("level", "INFO").toString();
        writer.append(java.util.Collections.singletonList(original), false);
        File snapshot = new File(temporaryFolder.getRoot(), "rotation-snapshot");
        JSONArray metadata = writer.snapshotFiles(snapshot);
        String later = new JSONObject().put("eventId", "after")
                .put("timestamp", 20).put("level", "INFO")
                .put("message", repeat("rotation", 30)).toString();
        writer.append(java.util.Collections.singletonList(later), false);
        File output = new File(temporaryFolder.getRoot(), "rotation-snapshot.zip");

        LogArchiveExporter.write(output, snapshot, metadata, "test", "");

        try (ZipFile zip = new ZipFile(output)) {
            String zipped = new String(zip.getInputStream(zip.getEntry("widgetenhancer.log"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(original + "\n", zipped);
            JSONObject manifest = new JSONObject(new String(zip.getInputStream(
                    zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8));
            JSONObject file = manifest.getJSONArray("files").getJSONObject(0);
            assertEquals(original.getBytes(StandardCharsets.UTF_8).length + 1,
                    file.getLong("exportedBytes"));
            assertTrue(file.getBoolean("entryIncluded"));
        }
    }

    @Test
    public void parallelExportTasksUseIndependentZipBuffersAndProduceValidArchives()
            throws Exception {
        File snapshot = temporaryFolder.newFolder("parallel-snapshot");
        String raw = new JSONObject().put("timestamp", 500).put("level", "ERROR")
                .put("stackTrace", repeat("frame", 90_000)).toString() + "\n";
        Files.write(new File(snapshot, "widgetenhancer.log").toPath(),
                raw.getBytes(StandardCharsets.UTF_8));
        JSONArray metadata = new JSONArray().put(new JSONObject()
                .put("name", "widgetenhancer.log").put("bytes", raw.getBytes(
                        StandardCharsets.UTF_8).length).put("status", "ready"));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newFixedThreadPool(4);
        java.util.ArrayList<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                File output = new File(temporaryFolder.getRoot(), "parallel-" + i + ".zip");
                futures.add(pool.submit(() -> LogArchiveExporter.write(output, snapshot,
                        metadata, "test", "")));
            }
            for (java.util.concurrent.Future<?> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        for (int i = 0; i < 4; i++) {
            try (ZipFile zip = new ZipFile(new File(temporaryFolder.getRoot(),
                    "parallel-" + i + ".zip"))) {
                assertEquals(raw, new String(zip.getInputStream(
                        zip.getEntry("widgetenhancer.log")).readAllBytes(), StandardCharsets.UTF_8));
                JSONObject manifest = new JSONObject(new String(zip.getInputStream(
                        zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(1, manifest.getInt("exportedFileCount"));
            }
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder output = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) output.append(value);
        return output.toString();
    }
}
