package com.aeinspector.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import org.junit.Test;

public class TimeSeriesTest {
    @Test
    public void exactRollupsAndPartialBuckets() {
        TimeSeries s = new TimeSeries();
        for (int tick = 0; tick < 100000; tick++) s.add(tick, 100000);
        assertEquals(10_000_000_000L, s.total());
        for (int level = 1; level < TimeSeries.WIDTHS.length; level++) {
            long width = TimeSeries.WIDTHS[level];
            SparseSeries view = s.view(level);
            assertTrue(view.size() <= 300);
            long lastStart = 99999 / width * width;
            assertEquals((100000 - lastStart) * 100000, view.value(view.size() - 1));
            if (width >= 2400) assertEquals(s.total(), view.sum(0, Long.MAX_VALUE));
        }
        assertEquals(s.total(), s.view(8).sum(0, Long.MAX_VALUE));
        s.advance(200000);
        assertEquals(s.total(), s.view(7).sum(0, Long.MAX_VALUE));
    }

    @Test
    public void sparseGapsAndRestart() throws Exception {
        TimeSeries s = new TimeSeries();
        s.add(0, 42);
        s.add(72_000_001, 13);
        assertEquals(55, s.view(8).sum(0, Long.MAX_VALUE));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.write(new DataOutputStream(out));
        TimeSeries restored = TimeSeries.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray())));
        restored.add(72_000_002, 7);
        assertEquals(62, restored.total());
        for (int level = 0; level < 9; level++) {
            SparseSeries view = restored.view(level);
            assertTrue(view.size() <= 300);
        }
        assertEquals(62, restored.view(8).sum(0, Long.MAX_VALUE));
    }
}
