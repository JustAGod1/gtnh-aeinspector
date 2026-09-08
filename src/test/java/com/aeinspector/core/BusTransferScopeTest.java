package com.aeinspector.core;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class BusTransferScopeTest {
    @Test public void partialAcceptanceAndFullReturnAreNotProduction() {
        List<String> events = new ArrayList<>();
        BusTransferScope scope = new BusTransferScope((n, r, d, in, amount) -> events.add(n + ":" + r + ":" + d + ":" + in + ":" + amount));
        int frame = scope.enter(9);
        scope.record(1, 2, 9, false, 100000);
        scope.record(1, 2, 9, true, 60000);
        scope.record(1, 3, 9, false, 100);
        scope.record(1, 3, 9, true, 100);
        scope.leave(frame);
        assertEquals(1, events.size());
        assertEquals("1:2:9:false:40000", events.get(0));
    }
    @Test public void sourcesResourcesAndNetworksStaySeparateAcrossNestedScopes() {
        List<String> events = new ArrayList<>();
        BusTransferScope scope = new BusTransferScope((n, r, d, in, amount) -> events.add(n + ":" + r + ":" + d + ":" + in + ":" + amount));
        int outer = scope.enter(9);
        scope.record(1, 2, 9, false, 100);
        int inner = scope.enter(9);
        scope.record(1, 2, 9, true, 40);
        scope.record(2, 2, 9, true, 30);
        scope.record(1, 2, 10, true, 20); // Another source is not a return from bus 9.
        scope.leave(inner);
        assertEquals(1, events.size());
        scope.leave(outer);
        assertEquals(3, events.size());
        assertTrue(events.contains("1:2:9:false:60"));
        assertTrue(events.contains("2:2:9:true:30"));
        assertTrue(events.contains("1:2:10:true:20"));
    }
    @Test public void outsideBusOperationsBothDirectionsAreReal() {
        long[] counts = new long[2];
        BusTransferScope scope = new BusTransferScope((n, r, d, in, amount) -> counts[in ? 0 : 1] += amount);
        scope.record(1, 2, 9, false, 100);
        scope.record(1, 2, 9, true, 100);
        assertArrayEquals(new long[] {100, 100}, counts);
    }
}
