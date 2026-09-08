package com.aeinspector.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ExternalStorageObserverTest {
    @Test
    public void subtractsAeTransferWithoutLosingEarlierExternalChanges() {
        LongCounters totals = new LongCounters();
        ExternalStorageObserver observer = new ExternalStorageObserver(totals::add);
        observer.difference(1, 1000); // initial visibility is not production
        observer.endReport();
        observer.initialized();
        observer.beginOperation();
        observer.difference(1, 72); // 8 externally added plus 64 from AE
        observer.endReport();
        observer.endOperation(1, 64);
        assertEquals(8, totals.get(1));
        observer.beginOperation();
        observer.difference(1, -12); // pure AE extraction
        observer.endReport();
        observer.endOperation(1, -12);
        assertEquals(8, totals.get(1));
        observer.invalidate();
        observer.difference(1, -900); // reconfiguration is not consumption
        observer.endReport();
        assertEquals(8, totals.get(1));
    }

    @Test
    public void sameResourceSlotMovesAreNotProduction() {
        LongCounters totals = new LongCounters();
        ExternalStorageObserver observer = new ExternalStorageObserver(totals::add);
        observer.initialized();
        observer.difference(1, -64);
        observer.difference(1, 64);
        observer.endReport();
        assertEquals(0, totals.get(1));
    }
}
