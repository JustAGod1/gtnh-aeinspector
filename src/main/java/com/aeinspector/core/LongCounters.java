package com.aeinspector.core;

import java.util.Arrays;

/** Primitive accumulator: no allocations on existing-key updates. All long keys are valid. */
public final class LongCounters {

    private long[] keys = new long[16];
    private long[] values = new long[16];
    private boolean[] occupied = new boolean[16];
    private int size;

    public void add(long key, long amount) {
        if (amount == 0) return;
        int slot = slot(key);
        if (occupied[slot]) {
            values[slot] = Math.addExact(values[slot], amount);
            return;
        }
        occupied[slot] = true;
        keys[slot] = key;
        values[slot] = amount;
        if (++size * 2 >= keys.length) grow();
    }

    public long get(long key) {
        int slot = slot(key);
        return occupied[slot] ? values[slot] : 0;
    }

    private int slot(long key) {
        long h = key;
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        int slot = (int) (h ^ (h >>> 33)) & (keys.length - 1);
        while (occupied[slot] && keys[slot] != key) slot = (slot + 1) & (keys.length - 1);
        return slot;
    }

    private void grow() {
        long[] oldKeys = keys;
        long[] oldValues = values;
        boolean[] oldOccupied = occupied;
        keys = new long[keys.length * 2];
        values = new long[keys.length];
        occupied = new boolean[keys.length];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) if (oldOccupied[i]) add(oldKeys[i], oldValues[i]);
    }

    public int size() { return size; }

    public long allocatedBytes() { return 17L * keys.length; }

    public void forEach(Consumer consumer) {
        for (int i = 0; i < keys.length; i++) if (occupied[i]) consumer.accept(keys[i], values[i]);
    }

    public void clear() {
        Arrays.fill(occupied, false);
        Arrays.fill(values, 0);
        size = 0;
    }

    @FunctionalInterface
    public interface Consumer { void accept(long key, long value); }
}
