package com.aeinspector.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;

import com.aeinspector.core.LongCounters;

import org.junit.Test;

public class SeriesDatabaseTest {
    @Test
    public void continuouslyUpdatedShardCannotStarveOlderDirtyShards() throws Exception {
        Path directory = Files.createTempDirectory("aeinspector-fair-save");
        try (SeriesDatabase db = new SeriesDatabase(directory)) {
            LongCounters counters = new LongCounters();
            counters.add(0, 1);
            for (int network = 1; network <= 3; network++) db.append(network, 0, counters);
            assertEquals(3, db.dirtyShards());
            assertEquals(0, db.maintain(0, Long.MAX_VALUE));
            assertEquals(0, db.maintain(3, 0));
            assertEquals(1, db.maintain(1, Long.MAX_VALUE));
            assertTrue(Files.exists(directory.resolve("n1-s0.aeis")));
            db.append(1, 1, counters);
            assertEquals(1, db.maintain(1, Long.MAX_VALUE));
            assertTrue(Files.exists(directory.resolve("n2-s0.aeis")));
            db.append(2, 2, counters);
            assertEquals(1, db.maintain(1, Long.MAX_VALUE));
            assertTrue(Files.exists(directory.resolve("n3-s0.aeis")));
            db.flush();
            assertEquals(0, db.dirtyShards());
        }
        deleteDirectory(directory);
    }

    @Test
    public void failedWriteKeepsDirtyDataAndReportsFailure() throws Exception {
        Path directory = Files.createTempDirectory("aeinspector-failed-save");
        SeriesDatabase db = new SeriesDatabase(directory, 16, 1024 * 1024);
        LongCounters counters = new LongCounters();
        counters.add(0, 70000);
        db.append(0, 0, counters);
        assertThrows(IOException.class, () -> db.maintain(1, Long.MAX_VALUE));
        assertEquals(1, db.dirtyShards());
        assertEquals(70000, db.query(0, 0, 8, 1).total);
        assertThrows(IOException.class, db::close);
        assertEquals(1, db.dirtyShards());
        deleteDirectory(directory);
    }

    private static void deleteDirectory(Path directory) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) Files.delete(file);
        }
        Files.delete(directory);
    }
    @Test
    public void aggregationEvictionAndReopen() throws Exception {
        Path directory = Files.createTempDirectory("aeinspector-database-test");
        try (SeriesDatabase db = new SeriesDatabase(directory, 1024 * 1024, 16 * 1024)) {
            for (int tick = 0; tick < 50; tick++) {
                LongCounters counters = new LongCounters();
                counters.add(0, 100000);
                counters.add(512, 27);
                db.append(1, tick, counters);
                counters.clear();
            }
            db.flush();
            assertEquals(5_000_000, db.query(1, 0, 8, 50).total);
            assertEquals(1350, db.query(1, 512, 8, 50).points.sum(0, 100));
            assertEquals(0, db.query(2, 0, 8, 50).total);
        }
        try (SeriesDatabase db = new SeriesDatabase(directory, 1024 * 1024, 16 * 1024)) {
            assertEquals(5_000_000, db.query(1, 0, 8, 50).total);
            LongCounters counters = new LongCounters();
            counters.add(0, 3);
            db.append(1, 50, counters);
            assertEquals(5_000_003, db.query(1, 0, 8, 51).total);
        }
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) Files.delete(file);
        }
        Files.delete(directory);
    }
}
