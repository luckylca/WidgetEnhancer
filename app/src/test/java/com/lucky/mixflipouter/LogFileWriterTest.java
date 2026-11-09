package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class LogFileWriterTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rotationKeepsOnlyFiveNewestJsonlFiles() throws Exception {
        LogFileWriter writer = new LogFileWriter(temporaryFolder.getRoot(), 8);
        for (int i = 0; i < 8; i++) {
            writer.append(Collections.singletonList("{\"i\":" + i + "}"), false);
        }

        List<File> files = writer.listFiles();
        assertEquals(5, files.size());
        assertEquals("widgetenhancer.log.4", files.get(0).getName());
        assertEquals("widgetenhancer.log", files.get(4).getName());
        assertEquals("{\"i\":3}\n", new String(Files.readAllBytes(files.get(0).toPath()),
                StandardCharsets.UTF_8));
        assertEquals("{\"i\":7}\n", new String(Files.readAllBytes(files.get(4).toPath()),
                StandardCharsets.UTF_8));
        assertTrue(writer.readRecentLines(3).contains("{\"i\":7}"));
        assertEquals(3, writer.readRecentLines(3).size());
    }

    @Test
    public void concurrentProducersKeepJsonlRecordsIntact() throws Exception {
        LogFileWriter writer = new LogFileWriter(temporaryFolder.getRoot(), 1024 * 1024);
        int producerCount = 8;
        int recordsPerProducer = 50;
        CountDownLatch ready = new CountDownLatch(producerCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService producers = Executors.newFixedThreadPool(producerCount);
        List<Future<?>> writes = new ArrayList<>();
        try {
            for (int producer = 0; producer < producerCount; producer++) {
                final int producerId = producer;
                writes.add(producers.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int record = 0; record < recordsPerProducer; record++) {
                        writer.append(Collections.singletonList(
                                "{\"producer\":" + producerId + ",\"record\":"
                                        + record + "}"), false);
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> write : writes) write.get();
        } finally {
            producers.shutdownNow();
        }
        List<String> lines = writer.readRecentLines(producerCount * recordsPerProducer);
        assertEquals(producerCount * recordsPerProducer, lines.size());
        for (String line : lines) {
            assertTrue(line.startsWith("{\"producer\":"));
            assertTrue(line.endsWith("}"));
        }
    }

    @Test
    public void reopeningWriterPreservesHistoryAcrossProcessRestart() throws Exception {
        LogFileWriter firstProcess = new LogFileWriter(temporaryFolder.getRoot(), 1024);
        firstProcess.append(Collections.singletonList("{\"event\":\"before restart\"}"), true);

        LogFileWriter restartedProcess = new LogFileWriter(temporaryFolder.getRoot(), 1024);
        assertEquals(Collections.singletonList("{\"event\":\"before restart\"}"),
                restartedProcess.readRecentLines(10));
    }

    @Test
    public void recoveryReplayAndRepeatedAppendDoNotDuplicateEventIds() throws Exception {
        LogFileWriter firstProcess = new LogFileWriter(temporaryFolder.getRoot(), 1024);
        String event = "{\"eventId\":\"stable-event\",\"level\":\"ERROR\"}";
        firstProcess.append(Collections.singletonList(event), true);

        LogFileWriter restartedProcess = new LogFileWriter(temporaryFolder.getRoot(), 1024);
        restartedProcess.append(java.util.Arrays.asList(event, event), true);

        assertEquals(Collections.singletonList(event), restartedProcess.readRecentLines(10));
    }

    @Test
    public void appendReportsWhenLogDirectoryBecomesUnavailable() throws Exception {
        LogFileWriter writer = new LogFileWriter(temporaryFolder.getRoot(), 1024);
        File logs = new File(temporaryFolder.getRoot(), "logs");
        assertTrue(logs.delete());
        Files.write(logs.toPath(), new byte[]{1});

        try {
            writer.append(Collections.singletonList("{\"event\":\"write failure\"}"), true);
            throw new AssertionError("append should fail when its directory is not a directory");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }
}
