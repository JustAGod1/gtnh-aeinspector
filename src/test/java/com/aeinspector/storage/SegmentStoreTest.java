package com.aeinspector.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.Test;

public class SegmentStoreTest {
    @Test
    public void boundedCacheAndRestart() throws Exception {
        Path directory = Files.createTempDirectory("aeinspector-store-test");
        byte[] expected = new byte[4096];
        Arrays.fill(expected, (byte) 42);
        try (SegmentStore store = new SegmentStore(directory, 8192, 4096)) {
            for (int i = 0; i < 30; i++) {
                byte[] mutable = expected.clone();
                store.write("network1-series" + i, mutable);
                mutable[0] = 0;
            }
            assertTrue(store.cachedBytes() <= 4096);
            assertArrayEquals(expected, store.read("network1-series0"));
        }
        try (SegmentStore store = new SegmentStore(directory, 8192, 4096)) {
            assertArrayEquals(expected, store.read("network1-series29"));
        }
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) Files.delete(file);
        }
        Files.delete(directory);
    }
}
