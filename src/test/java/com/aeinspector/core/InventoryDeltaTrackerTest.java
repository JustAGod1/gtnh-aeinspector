package com.aeinspector.core;

import static org.junit.Assert.*;
import org.junit.Test;

public class InventoryDeltaTrackerTest {
    @Test public void initialContentsAreBaselineAndExternalChangesHaveTheExpectedDirection() {
        LongCounters changes = new LongCounters(); InventoryDeltaTracker tracker = new InventoryDeltaTracker(changes::add);
        assertFalse(tracker.contains(1));
        sample(tracker, 1, 1000); assertEquals(0, changes.get(1)); assertTrue(tracker.contains(1));
        sample(tracker, 1, 1064); assertEquals(64, changes.get(1));
        changes.clear(); sample(tracker, 1, 1040); assertEquals(-24, changes.get(1));
        changes.clear(); sample(tracker, 1, 1040); assertEquals(0, changes.get(1));
        tracker.beginSample(); tracker.endSample(); assertEquals(-1040, changes.get(1)); assertFalse(tracker.contains(1));
    }
    @Test public void delayedMonitorNotificationDoesNotDoubleCountAeExtraction() {
        LongCounters changes = new LongCounters(); InventoryDeltaTracker tracker = new InventoryDeltaTracker(changes::add);
        sample(tracker, 1, 1000);
        tracker.aeChange(1, -100); tracker.aeChange(1, -50);
        // The chest notifies later; it also received 36 items from its physical input.
        sample(tracker, 1, 886); assertEquals(36, changes.get(1));
        changes.clear(); sample(tracker, 1, 886); assertEquals(0, changes.get(1));
    }
    @Test public void netZeroNotificationStillRevealsExternalRefilling() {
        LongCounters changes = new LongCounters(); InventoryDeltaTracker tracker = new InventoryDeltaTracker(changes::add);
        sample(tracker, 2, 64);
        tracker.aeChange(2, -64);
        sample(tracker, 2, 64); // 64 removed by AE and refilled externally; monitor may send no notification.
        assertEquals(64, changes.get(2));
    }
    @Test public void partialAeTransfersAndReservationReturnsAreSubtracted() {
        LongCounters changes = new LongCounters(); InventoryDeltaTracker tracker = new InventoryDeltaTracker(changes::add);
        sample(tracker, 1, 100);
        tracker.aeChange(1, 4); // requested 64, but only 4 were accepted
        sample(tracker, 1, 104); assertEquals(0, changes.get(1));
        tracker.aeChange(1, -50); tracker.aeChange(1, 50); sample(tracker, 1, 104);
        assertEquals(0, changes.get(1));
        tracker.aeChange(1, 10); sample(tracker, 1, 109); assertEquals(-5, changes.get(1));
    }
    @Test public void exactResourceIdsSlotMovementAndReconnectKeepTheirMeaning() {
        LongCounters changes = new LongCounters(); InventoryDeltaTracker tracker = new InventoryDeltaTracker(changes::add);
        tracker.beginSample(); tracker.sample(1, 32); tracker.sample(1, 32); tracker.sample(2, 9); tracker.endSample();
        tracker.beginSample(); tracker.sample(1, 64); tracker.sample(2, 9); tracker.endSample(); assertEquals(0, changes.get(1));
        tracker.beginSample(); tracker.sample(1, 20); tracker.sample(2, 53); tracker.endSample();
        assertEquals(-44, changes.get(1)); assertEquals(44, changes.get(2));
        changes.clear(); tracker.aeChange(1, -10); tracker.invalidate(); assertFalse(tracker.contains(1)); sample(tracker, 1, 5000);
        assertEquals(0, changes.get(1)); assertEquals(0, changes.get(2));
    }
    @Test public void quantitiesBeyondIntegerRangeRemainExact() {
        LongCounters changes = new LongCounters(); InventoryDeltaTracker tracker = new InventoryDeltaTracker(changes::add);
        sample(tracker, 1, 10_000_000_000L); tracker.aeChange(1, -100_000);
        sample(tracker, 1, 10_000_200_000L); assertEquals(300_000, changes.get(1));
        assertThrows(IllegalArgumentException.class, () -> tracker.sample(1, -1));
    }
    private static void sample(InventoryDeltaTracker tracker, int resource, long count) {
        tracker.beginSample(); tracker.sample(resource, count); tracker.endSample();
    }
}
