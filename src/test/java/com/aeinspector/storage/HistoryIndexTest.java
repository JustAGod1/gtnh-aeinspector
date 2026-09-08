package com.aeinspector.storage;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;
import com.aeinspector.core.NetworkRecord;
import com.aeinspector.core.ResourceDictionary;

public class HistoryIndexTest {
    private static void prepare(HistoryIndex index) {
        int steps = 0;
        while (!index.ready()) { index.step(); if (++steps > 1_000_000) fail("Index did not complete"); }
    }
    private static HistoryIndex.Coverage coverage(HistoryIndex index, long now) {
        HistoryIndex.Coverage coverage = index.coverage(now);
        int steps = 0;
        while (!coverage.step()) if (++steps > 100_000) fail("Coverage did not complete");
        return coverage;
    }
    private static HistoryIndex.Read complete(HistoryIndex.Read read) throws Exception {
        int steps = 0;
        while (!read.step()) if (++steps > 100_000) fail("Read did not complete");
        return read;
    }

    @Test public void indexedGraphsAndSummaryOnlyReadsMatchReferenceForEveryScale() throws Exception {
        try (WorldStatistics world = new WorldStatistics()) {
            int coal = world.resources.resolve(ResourceDictionary.ITEM, "test:coal", 0, null);
            int variant = world.resources.resolve(ResourceDictionary.ITEM, "test:coal", 1, null);
            int endpoint = world.devices.resolve(0, 1, 2, 3, 4, "test", "bus");
            NetworkRecord initial = world.createNetwork();
            for (int tick = 0; tick < 803; tick++) {
                if (tick % 19 != 0) initial.observe(tick);
                if (tick % 7 == 0) initial.add(coal, endpoint, true, false, 31);
                if (tick % 11 == 0) initial.add(coal, endpoint, false, true, 17);
                if (tick % 17 == 0) initial.add(variant, endpoint, true, true, 5);
                world.endTick();
            }
            NetworkRecord left = world.createNetwork(initial.id), right = world.createNetwork(initial.id);
            left.observe(world.tick()); right.observe(world.tick());
            left.add(coal, endpoint, true, false, 201); right.add(coal, endpoint, false, false, 77);
            world.endTick();
            NetworkRecord joined = world.createNetwork(left.id, right.id);
            joined.observe(world.tick()); joined.add(variant, endpoint, true, false, 901); world.endTick();
            HistoryIndex index = new HistoryIndex(world, joined.id); prepare(index);
            HistoryIndex.Coverage coverage = coverage(index, world.tick());
            assertEquals(4, index.walkedNetworks());
            for (int resource : new int[] {coal, variant}) for (int device : new int[] {-1, endpoint}) for (int level = 0; level < 9; level++) {
                HistoryQuery.Result expected = new HistoryQuery(world).query(joined.id, resource, device, level, world.tick());
                HistoryIndex.Read actual = complete(index.read(resource, device, level, world.tick(), true, coverage));
                assertEquals(expected.start, actual.start); assertEquals(expected.width, actual.width);
                assertArrayEquals(expected.totals, actual.totals);
                assertArrayEquals(expected.observedTicks, actual.observed);
                for (int c = 0; c < 4; c++) assertArrayEquals("channel " + c + " level " + level, expected.counts[c], actual.points[c]);
                HistoryIndex.Read summary = complete(index.read(resource, device, level, world.tick(), false, coverage));
                assertNull(summary.points); assertNull(summary.observed);
                assertEquals(expected.windowCount(true), summary.count(true));
                assertEquals(expected.windowCount(false), summary.count(false));
                assertEquals(expected.rate(true), summary.rate(true), 0.00001);
                assertEquals(Arrays.stream(expected.observedTicks).sum(), summary.observedTicks());
            }
            assertEquals(4, index.walkedNetworks()); // graph/summary reads never walk ancestors again
        }
    }

    @Test public void yieldingCannotMixNewLiveTransfersIntoAnAlreadyCapturedResource() throws Exception {
        try (WorldStatistics world = new WorldStatistics()) {
            int coal = world.resources.resolve(ResourceDictionary.ITEM, "test:coal", 0, null);
            NetworkRecord parent = world.createNetwork();
            parent.observe(0); parent.add(coal, 0, true, false, 10); world.endTick();
            NetworkRecord root = world.createNetwork(parent.id);
            root.observe(1); root.add(coal, 0, true, false, 20); world.endTick();
            HistoryIndex index = new HistoryIndex(world, root.id); prepare(index);
            HistoryIndex.Coverage coverage = coverage(index, world.tick());
            HistoryIndex.Read captured = index.read(coal, -1, 1, world.tick(), true, coverage);
            root.observe(world.tick()); root.add(coal, 0, true, false, 100_000); world.endTick();
            complete(captured);
            assertEquals(30, captured.count(true)); assertEquals(30, captured.totals[0]);
            assertEquals(2, captured.observedTicks()); assertArrayEquals(new long[] {30}, captured.points[0]);
            HistoryIndex.Read next = complete(index.read(coal, -1, 1, world.tick(), true, coverage));
            assertEquals(100_030, next.count(true)); assertEquals(3, next.observedTicks());
        }
    }

    @Test public void cachedIndexDiscoversNewLivePairsWithoutWalkingAncestryAgain() throws Exception {
        try (WorldStatistics world = new WorldStatistics()) {
            int first = world.resources.resolve(ResourceDictionary.ITEM, "test:coal", 0, null);
            NetworkRecord parent = world.createNetwork(); parent.observe(0); parent.add(first, 0, true, false, 1); world.endTick();
            NetworkRecord root = world.createNetwork(parent.id);
            HistoryIndex index = new HistoryIndex(world, root.id); prepare(index);
            int variant = world.resources.resolve(ResourceDictionary.ITEM, "test:coal", 3, null);
            root.observe(world.tick()); root.add(variant, 0, true, false, 456); world.endTick();
            prepare(index);
            assertEquals(2, index.walkedNetworks()); assertEquals(variant, index.firstResource(first + 1));
            assertEquals(0, index.firstDevice(variant, 0));
            assertEquals(456, complete(index.read(variant, -1, 8, world.tick(), false, coverage(index, world.tick()))).count(true));
        }
    }

    @Test public void deepSharedHistoryPreparationYieldsAtEveryEdgeInsteadOfBlockingAWholeRequest() throws Exception {
        try (WorldStatistics world = new WorldStatistics()) {
            int coal = world.resources.resolve(ResourceDictionary.ITEM, "test:coal", 0, null);
            NetworkRecord initial = world.createNetwork(); initial.observe(0); initial.add(coal, 0, true, false, 999); world.endTick();
            NetworkRecord root = initial;
            for (int n = 0; n < 3000; n++) root = world.createNetwork(root.id, initial.id);
            HistoryIndex index = new HistoryIndex(world, root.id);
            int steps = 0, previous = 0;
            while (!index.ready()) {
                index.step(); assertTrue(index.walkedNetworks() - previous <= 1); previous = index.walkedNetworks(); steps++;
            }
            assertEquals(3001, index.walkedNetworks()); assertTrue(steps > 6000);
            HistoryIndex.Read read = complete(index.read(coal, -1, 8, world.tick(), true, coverage(index, world.tick())));
            assertEquals(999, read.count(true));
        }
    }
}
