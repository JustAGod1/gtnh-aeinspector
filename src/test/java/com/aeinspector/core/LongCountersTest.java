package com.aeinspector.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LongCountersTest {
    @Test
    public void rehashAndFullWidthAmounts() {
        LongCounters c = new LongCounters();
        for (int i = 0; i < 10000; i++) c.add(i, 100_000L);
        for (int i = 0; i < 10000; i++) c.add(i, 3_000_000_000L);
        for (int i = 0; i < 10000; i++) assertEquals(3_000_100_000L, c.get(i));
        c.add(Long.MIN_VALUE, 13);
        assertEquals(13, c.get(Long.MIN_VALUE));
        c.clear();
        assertEquals(0, c.size());
        assertEquals(0, c.get(Long.MIN_VALUE));
    }
}
