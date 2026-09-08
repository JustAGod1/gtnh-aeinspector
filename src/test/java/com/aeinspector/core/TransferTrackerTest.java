package com.aeinspector.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TransferTrackerTest {
    @Test
    public void filteredParentCannotReappearThroughDifferentResourceOrNetwork() {
        long[] total = {0};
        TransferTracker t = new TransferTracker((n, r, d, in, amount) -> total[0] += amount);
        int outer = t.begin(1, 2, 3, 64, true, true, true);
        t.finish(t.begin(2, 99, 7, 64, false, true, false), 64);
        t.finish(outer, 0);
        assertEquals(0, total[0]);
    }
    @Test
    public void partialSimulationFailureAndPlayer() {
        long[] totals = new long[2];
        TransferTracker t = new TransferTracker((n, r, d, in, amount) -> totals[in ? 0 : 1] += amount);
        t.finish(t.begin(1, 2, 3, 64, true, true, false), 12);
        t.finish(t.begin(1, 2, 3, 64, false, true, false), 7);
        t.finish(t.begin(1, 2, 3, 64, true, false, false), 0);
        t.finish(t.begin(1, 2, 3, 64, true, true, true), 0);
        t.abort(t.begin(1, 2, 3, 64, true, true, false));
        assertEquals(52, totals[0]);
        assertEquals(7, totals[1]);
        assertEquals(0, t.depth());
    }

    @Test
    public void nestedCallsAndDistinctNetworks() {
        long[] totals = new long[3];
        TransferTracker t = new TransferTracker((n, r, d, in, amount) -> totals[(int) n] += amount);
        int outer = t.begin(1, 2, 3, 64, true, true, false);
        t.finish(t.begin(1, 2, 3, 64, true, true, false), 0);
        t.finish(t.begin(2, 2, 3, 64, true, true, false), 0);
        t.finish(outer, 0);
        assertEquals(64, totals[1]);
        assertEquals(64, totals[2]);
    }

    @Test
    public void reservationAndReturnAreNotProduction() {
        long[] total = {0};
        TransferTracker t = new TransferTracker((n, r, d, in, amount) -> total[0] += amount);
        t.enterReservation();
        t.finish(t.begin(1, 2, 3, 100, false, true, false), 100);
        t.leaveReservation();
        t.finish(t.begin(1, 2, 3, 4, false, true, false), 4);
        t.enterReservation();
        t.finish(t.begin(1, 2, 3, 96, true, true, false), 0);
        t.leaveReservation();
        assertEquals(4, total[0]);
    }
}
