package com.aeinspector.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** One direction/quality of one resource at one endpoint. Mutated only at tick boundaries. */
public final class TimeSeries {
    public static final long[] WIDTHS = {1, 4, 40, 240, 2400, 12000, 60000, 240000};
    private final SparseSeries[] levels = new SparseSeries[WIDTHS.length];
    private final long[] pendingTime = new long[WIDTHS.length];
    private final long[] pendingValue = new long[WIDTHS.length];
    private SparseSeries allTime = new SparseSeries();
    private long allWidth = 1;
    private long total;
    private long lastTick = -1;
    private long allocatedBytes;

    public TimeSeries() {
        for (int i = 0; i < levels.length; i++) levels[i] = new SparseSeries();
        recalculateBytes();
    }

    public void add(long tick, long amount) {
        if (tick < 0 || tick < lastTick || amount <= 0) throw new IllegalArgumentException("Invalid sample");
        total = Math.addExact(total, amount);
        lastTick = tick;
        append(levels[0], tick, amount);
        accumulate(1, tick, amount);
        while (tick / allWidth >= SparseSeries.CAPACITY) compactAllTime();
        append(allTime, tick / allWidth * allWidth, amount);
    }

    private void append(SparseSeries series, long tick, long amount) {
        long before = series.allocatedBytes();
        series.add(tick, amount);
        allocatedBytes += series.allocatedBytes() - before;
    }

    private void accumulate(int level, long tick, long amount) {
        if (level >= levels.length) return;
        long bucket = tick / WIDTHS[level] * WIDTHS[level];
        if (pendingValue[level] != 0 && pendingTime[level] != bucket) flush(level);
        pendingTime[level] = bucket;
        pendingValue[level] = Math.addExact(pendingValue[level], amount);
    }

    private void flush(int level) {
        long time = pendingTime[level];
        long amount = pendingValue[level];
        if (amount == 0) return;
        pendingValue[level] = 0;
        append(levels[level], time, amount);
        accumulate(level + 1, time, amount);
    }

    /** Called on an immutable snapshot before serving a query, or on save. */
    public void advance(long tick) {
        for (int i = 1; i < levels.length; i++) {
            if (pendingValue[i] != 0 && pendingTime[i] + WIDTHS[i] <= tick) flush(i);
        }
    }

    private void compactAllTime() {
        allWidth = Math.multiplyExact(allWidth, 2);
        SparseSeries compacted = new SparseSeries();
        for (int i = 0; i < allTime.size(); i++) {
            compacted.add(allTime.time(i) / allWidth * allWidth, allTime.value(i));
        }
        allocatedBytes += compacted.allocatedBytes() - allTime.allocatedBytes();
        allTime = compacted;
    }

    public SparseSeries view(int level) {
        if (level == WIDTHS.length) return allTime.copy();
        SparseSeries result = levels[level].copy();
        // Higher tiers do not yet contain the pending values of lower tiers.
        // Add from oldest (highest level) to newest (lowest level).
        for (int i = level; i >= 1; i--) {
            if (pendingValue[i] != 0) {
                long bucket = pendingTime[i] / WIDTHS[level] * WIDTHS[level];
                result.add(bucket, pendingValue[i]);
            }
        }
        return result;
    }

    public long total() { return total; }
    public long allTimeWidth() { return allWidth; }

    public long allocatedBytes() { return allocatedBytes; }

    private void recalculateBytes() {
        long bytes = 16L * WIDTHS.length + allTime.allocatedBytes();
        for (SparseSeries level : levels) bytes += level.allocatedBytes();
        allocatedBytes = bytes;
    }

    public void write(DataOutput out) throws IOException {
        out.writeInt(1);
        out.writeLong(total);
        out.writeLong(lastTick);
        out.writeLong(allWidth);
        allTime.write(out);
        for (int i = 0; i < levels.length; i++) {
            levels[i].write(out);
            out.writeLong(pendingTime[i]);
            out.writeLong(pendingValue[i]);
        }
    }

    public static TimeSeries read(DataInput in) throws IOException {
        if (in.readInt() != 1) throw new IOException("Unsupported time-series version");
        TimeSeries result = new TimeSeries();
        result.total = in.readLong();
        result.lastTick = in.readLong();
        result.allWidth = in.readLong();
        if (result.total < 0 || result.lastTick < -1 || result.allWidth <= 0
                || (result.allWidth & (result.allWidth - 1)) != 0) throw new IOException("Invalid series metadata");
        result.allTime = SparseSeries.read(in);
        for (int i = 0; i < result.levels.length; i++) {
            result.levels[i] = SparseSeries.read(in);
            result.pendingTime[i] = in.readLong();
            result.pendingValue[i] = in.readLong();
            if (result.pendingTime[i] < 0 || result.pendingValue[i] < 0) throw new IOException("Invalid pending bucket");
        }
        result.recalculateBytes();
        return result;
    }
}
