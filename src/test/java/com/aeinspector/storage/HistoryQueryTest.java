package com.aeinspector.storage;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.aeinspector.core.NetworkRecord;
import com.aeinspector.core.ResourceDictionary;
import org.junit.Test;

public class HistoryQueryTest {
    @Test
    public void sharedAncestorIsCountedOnceAndUnknownTicksStayUnknown() throws Exception {
        Path directory = Files.createTempDirectory("aeinspector-query");
        try (WorldStatistics world = new WorldStatistics(directory)) {
            int resource = world.resources.resolve(ResourceDictionary.ITEM, "test:coal", 0, null);
            NetworkRecord root = world.createNetwork();
            root.observe(0);
            root.add(resource, 0, true, false, 100);
            world.endTick();
            NetworkRecord left = world.createNetwork(root.id), right = world.createNetwork(root.id);
            left.observe(1); right.observe(1);
            left.add(resource, 0, true, true, 30);
            right.add(resource, 0, false, false, 20);
            world.endTick();
            NetworkRecord joined = world.createNetwork(left.id, right.id);
            world.endTick(); // Deliberately unobserved tick 2.
            joined.observe(3);
            joined.add(resource, 0, true, false, 7);
            world.endTick();
            HistoryQuery query = new HistoryQuery(world);
            HistoryQuery.Result result = query.query(joined.id, resource, -1, 0, 4);
            assertEquals(4, query.lineage(joined.id).size());
            assertArrayEquals(new long[] {107, 20, 30, 0}, result.totals);
            assertArrayEquals(new long[] {1, 1, 0, 1}, result.observedTicks);
            assertEquals(137, result.windowCount(true));
            assertEquals(137 * 20.0 / 3, result.rate(true), 0.00001);
            HistoryQuery.Result old = query.query(joined.id, resource, -1, 0, 200);
            assertEquals(100, old.observedTicks.length);
            assertEquals(0, old.windowCount(true));
            assertTrue(Double.isNaN(old.rate(true)));
            assertArrayEquals(result.totals, old.totals);
        }
        delete(directory);
    }

    @Test
    public void allTimeRebinsDifferentSeriesWidthsWithoutLosingTotals() throws Exception {
        Path directory = Files.createTempDirectory("aeinspector-query-rebin");
        try (WorldStatistics world = new WorldStatistics(directory)) {
            int resource = world.resources.resolve(ResourceDictionary.FLUID, "water", 0, null);
            NetworkRecord network = world.createNetwork();
            network.observe(0);
            network.add(resource, 0, true, false, 123456);
            world.endTick();
            for (int i = 1; i <= 800; i++) {
                network.observe(i);
                if (i == 800) network.add(resource, 0, false, false, 23456);
                world.endTick();
            }
            HistoryQuery.Result result = new HistoryQuery(world).query(network.id, resource, -1, 8, world.tick());
            assertTrue(result.observedTicks.length <= 300);
            assertEquals(4, result.width);
            assertEquals(123456, result.windowCount(true));
            assertEquals(23456, result.windowCount(false));
            long observations = 0;
            for (long ticks : result.observedTicks) observations += ticks;
            assertEquals(801, observations);
        }
        delete(directory);
    }

    private static void delete(Path directory) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(file);
        }
    }
}
