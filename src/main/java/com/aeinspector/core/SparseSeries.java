package com.aeinspector.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/** Sparse, chronological ring. Stores only nonzero buckets, bounded at 300 entries. */
public final class SparseSeries {
    public static final int CAPACITY = 300;
    private long[] times = new long[4];
    private long[] values = new long[4];
    private int start;
    private int size;

    public void add(long time, long value) {
        if (value == 0) return;
        if (size != 0) {
            int last = (start + size - 1) % times.length;
            if (time < times[last]) throw new IllegalArgumentException("Non-monotonic bucket");
            if (time == times[last]) {
                values[last] = Math.addExact(values[last], value);
                return;
            }
        }
        if (size == times.length && times.length < CAPACITY) resize(Math.min(CAPACITY, times.length * 2));
        if (size == CAPACITY) {
            start = (start + 1) % times.length;
            size--;
        }
        int slot = (start + size++) % times.length;
        times[slot] = time;
        values[slot] = value;
    }

    private void resize(int capacity) {
        long[] newTimes = new long[capacity];
        long[] newValues = new long[capacity];
        for (int i = 0; i < size; i++) {
            newTimes[i] = time(i);
            newValues[i] = value(i);
        }
        times = newTimes;
        values = newValues;
        start = 0;
    }

    public int size() { return size; }
    public long time(int index) { return times[(start + index) % times.length]; }
    public long value(int index) { return values[(start + index) % values.length]; }
    public long allocatedBytes() { return times.length * 16L; }

    public long sum(long fromInclusive, long toExclusive) {
        long sum = 0;
        for (int i = 0; i < size; i++) {
            if (time(i) >= fromInclusive && time(i) < toExclusive) sum = Math.addExact(sum, value(i));
        }
        return sum;
    }

    public SparseSeries copy() {
        SparseSeries result = new SparseSeries();
        result.times = Arrays.copyOf(times, times.length);
        result.values = Arrays.copyOf(values, values.length);
        result.start = start;
        result.size = size;
        return result;
    }

    public void write(DataOutput out) throws IOException {
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeLong(time(i));
            out.writeLong(value(i));
        }
    }

    public static SparseSeries read(DataInput in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > CAPACITY) throw new IOException("Invalid bucket count");
        SparseSeries result = new SparseSeries();
        long previous = -1;
        for (int i = 0; i < count; i++) {
            long time = in.readLong();
            long value = in.readLong();
            if (time <= previous || value <= 0) throw new IOException("Invalid bucket");
            result.add(time, value);
            previous = time;
        }
        return result;
    }
}
