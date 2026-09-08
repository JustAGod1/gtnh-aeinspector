package com.aeinspector.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Numeric pair IDs keep both tick counters and disk shards compact. */
public final class NetworkRecord {
    public final int id;
    public final long started;
    public final long[] parents;
    public final LongCounters pending = new LongCounters();
    private final LongCounters pairs = new LongCounters();
    private final ArrayList<Long> pairKeys = new ArrayList<>();
    private final ArrayList<long[]> coverage = new ArrayList<>();
    private boolean resumed;
    private Consumer<NetworkRecord> pairListener;

    public NetworkRecord(int id, long started, long[] parents) {
        this.id = id;
        this.started = started;
        this.parents = parents.clone();
    }

    public long pair(int resource, int device) {
        long key = ((long) resource << 32) | (device & 0xffffffffL);
        long found = pairs.get(key);
        if (found != 0) return found - 1;
        long next = pairKeys.size();
        pairKeys.add(key);
        pairs.add(key, next + 1);
        if (pairListener != null) pairListener.accept(this);
        return next;
    }
    public void setPairListener(Consumer<NetworkRecord> listener) { pairListener = listener; }

    public long findPair(int resource, int device) {
        return pairs.get(((long) resource << 32) | (device & 0xffffffffL)) - 1;
    }

    public int pairCount() { return pairKeys.size(); }
    public long pairKey(int id) { return pairKeys.get(id); }
    public int coverageCount() { return coverage.size(); }
    public long coverageStart(int index) { return coverage.get(index)[0]; }
    public long coverageEnd(int index) { return coverage.get(index)[1]; }

    public void add(int resource, int device, boolean incoming, boolean estimated, long amount) {
        int channel = (incoming ? 0 : 1) | (estimated ? 2 : 0);
        pending.add(pair(resource, device) * 4 + channel, amount);
        // Device -1 is the network total, separate from unknown source device 0.
        pending.add(pair(resource, -1) * 4 + channel, amount);
    }

    public void observe(long tick) {
        if (!coverage.isEmpty() && !resumed) {
            long[] last = coverage.get(coverage.size() - 1);
            if (tick < last[1] - 1) throw new IllegalArgumentException("Non-monotonic observation");
            if (tick <= last[1]) {
                last[1] = Math.max(last[1], tick + 1);
                return;
            }
        }
        resumed = false;
        coverage.add(new long[] {tick, tick + 1});
    }

    public List<long[]> coverage() {
        List<long[]> result = new ArrayList<>();
        for (long[] interval : coverage) result.add(interval.clone());
        return result;
    }

    public void write(DataOutput out) throws IOException {
        out.writeInt(id);
        out.writeLong(started);
        out.writeInt(parents.length);
        for (long parent : parents) out.writeLong(parent);
        out.writeInt(pairKeys.size());
        for (long key : pairKeys) out.writeLong(key);
        out.writeInt(coverage.size());
        for (long[] range : coverage) { out.writeLong(range[0]); out.writeLong(range[1]); }
    }

    public static NetworkRecord read(DataInput in) throws IOException {
        int id = in.readInt();
        long started = in.readLong();
        int parentCount = bounded(in.readInt());
        long[] parents = new long[parentCount];
        for (int i = 0; i < parentCount; i++) parents[i] = in.readLong();
        NetworkRecord result = new NetworkRecord(id, started, parents);
        int pairs = bounded(in.readInt());
        for (int i = 0; i < pairs; i++) {
            long key = in.readLong();
            if (result.pair((int) (key >>> 32), (int) key) != i) throw new IOException("Duplicate resource/endpoint pair");
        }
        int ranges = bounded(in.readInt());
        long previousEnd = -1;
        for (int i = 0; i < ranges; i++) {
            long start = in.readLong();
            long end = in.readLong();
            if (start < previousEnd || end <= start) throw new IOException("Invalid observation interval");
            result.coverage.add(new long[] {start, end});
            previousEnd = end;
        }
        result.resumed = true;
        return result;
    }

    private static int bounded(int count) throws IOException {
        if (count < 0 || count > 10_000_000) throw new IOException("Invalid metadata count");
        return count;
    }
}
