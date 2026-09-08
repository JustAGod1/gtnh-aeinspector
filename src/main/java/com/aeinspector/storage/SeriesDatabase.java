package com.aeinspector.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;

import com.aeinspector.core.LongCounters;
import com.aeinspector.core.SparseSeries;
import com.aeinspector.core.TimeSeries;

/** Server-thread-only database. The tick driver schedules bounded synchronous maintenance. */
public final class SeriesDatabase implements AutoCloseable {
    private final Path root;
    private final SegmentStore disk;
    private final long memoryLimit;
    private final LinkedHashMap<ShardKey, Shard> shards = new LinkedHashMap<>(16, 0.75f, true);
    private final ShardKey lookup = new ShardKey(0, 0);
    private Shard dirtyFirst, dirtyLast;
    private long residentBytes;
    private int dirtyCount;
    private boolean closed;

    public SeriesDatabase(Path root, int maxSegmentBytes, long cacheBytes) throws IOException {
        if (maxSegmentBytes < 16 || cacheBytes < 0) throw new IllegalArgumentException("Storage budgets");
        this.root = root.toAbsolutePath().normalize();
        this.memoryLimit = cacheBytes * 3 / 4;
        this.disk = new SegmentStore(root, maxSegmentBytes, cacheBytes / 4);
    }

    public SeriesDatabase(Path root) throws IOException { this(root, 32 * 1024 * 1024, 128L * 1024 * 1024); }

    public void append(long network, long tick, LongCounters counters) throws IOException {
        check();
        if (network < 0 || tick < 0) throw new IllegalArgumentException("Network/tick");
        try {
            counters.forEach((row, amount) -> {
                if (row < 0 || amount <= 0) throw new IllegalArgumentException("Series ID/count");
                try {
                    Shard shard = shard(network, row);
                    int slot = (int) (row & 255);
                    TimeSeries series = shard.rows[slot];
                    if (series == null) {
                        series = new TimeSeries();
                        shard.rows[slot] = series;
                        shard.count++;
                        long added = series.allocatedBytes() + 512;
                        shard.bytes += added;
                        residentBytes += added;
                    }
                    long before = series.allocatedBytes();
                    series.add(tick, amount);
                    long growth = series.allocatedBytes() - before;
                    shard.bytes += growth;
                    residentBytes += growth;
                    markDirty(shard);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        evict();
    }

    public SeriesView query(long network, long row, int level, long now) throws IOException {
        check();
        if (network < 0 || row < 0 || level < 0 || level > 8 || now < 0) throw new IllegalArgumentException("Query");
        Shard shard = shard(network, row);
        TimeSeries series = shard.rows[(int) (row & 255)];
        SeriesView result = series == null ? new SeriesView(new SparseSeries(), 0, 1)
                : new SeriesView(series.view(level), series.total(),
                        level == 8 ? series.allTimeWidth() : TimeSeries.WIDTHS[level]);
        evict();
        return result;
    }

    private Shard shard(long network, long row) throws IOException {
        lookup.network = network;
        lookup.index = row >>> 8;
        Shard result = shards.get(lookup);
        if (result != null) return result;
        ShardKey key = new ShardKey(network, row >>> 8);
        result = new Shard(key);
        if (Files.exists(root.resolve(result.filename + ".aeis"))) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(disk.read(result.filename)))) {
                if (in.readInt() != 1) throw new IOException("Unsupported series shard");
                int count = in.readInt();
                if (count < 0 || count > 256) throw new IOException("Invalid shard size");
                for (int i = 0; i < count; i++) {
                    long id = in.readLong();
                    int slot = (int) (id & 255);
                    if (id < 0 || (id >>> 8) != (row >>> 8) || result.rows[slot] != null) {
                        throw new IOException("Invalid shard row");
                    }
                    TimeSeries series = TimeSeries.read(in);
                    result.rows[slot] = series;
                    result.count++;
                    result.bytes += series.allocatedBytes() + 512;
                }
                if (in.read() != -1) throw new IOException("Trailing shard data");
            }
        }
        shards.put(key, result);
        residentBytes += result.bytes;
        return result;
    }

    private void save(Shard shard) throws IOException {
        if (!shard.dirty) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeInt(shard.count);
            for (int i = 0; i < shard.rows.length; i++) {
                if (shard.rows[i] == null) continue;
                out.writeLong((shard.key.index << 8) | i);
                shard.rows[i].write(out);
            }
        }
        disk.write(shard.filename, bytes.toByteArray());
        unmarkDirty(shard);
    }

    private void evict() throws IOException {
        Iterator<Shard> i = shards.values().iterator();
        while (residentBytes > memoryLimit && i.hasNext()) {
            Shard shard = i.next();
            save(shard);
            residentBytes -= shard.bytes;
            i.remove();
        }
    }

    /** Never starts another write after its budget expires; one filesystem operation is indivisible. */
    public int maintain(int maxShards, long budgetNanos) throws IOException {
        check();
        if (maxShards < 0 || budgetNanos < 0) throw new IllegalArgumentException("Maintenance budget");
        long start = System.nanoTime();
        int written = 0;
        while (dirtyFirst != null && written < maxShards && System.nanoTime() - start < budgetNanos) {
            save(dirtyFirst);
            written++;
        }
        return written;
    }

    public void flush() throws IOException {
        check();
        while (dirtyFirst != null) save(dirtyFirst);
    }

    private void markDirty(Shard shard) {
        if (shard.dirty) return;
        shard.dirty = true;
        shard.dirtyPrevious = dirtyLast;
        if (dirtyLast == null) dirtyFirst = shard;
        else dirtyLast.dirtyNext = shard;
        dirtyLast = shard;
        dirtyCount++;
    }

    private void unmarkDirty(Shard shard) {
        if (shard.dirtyPrevious == null) dirtyFirst = shard.dirtyNext;
        else shard.dirtyPrevious.dirtyNext = shard.dirtyNext;
        if (shard.dirtyNext == null) dirtyLast = shard.dirtyPrevious;
        else shard.dirtyNext.dirtyPrevious = shard.dirtyPrevious;
        shard.dirtyPrevious = shard.dirtyNext = null;
        shard.dirty = false;
        dirtyCount--;
    }

    public long residentBytes() { return residentBytes; }
    public long cachedSegmentBytes() { return disk.cachedBytes(); }
    public int dirtyShards() { return dirtyCount; }

    private void check() throws IOException {
        if (closed) throw new IOException("Series database closed");
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        flush();
        closed = true;
        disk.close();
        shards.clear();
        residentBytes = 0;
    }

    private static final class Shard {
        final ShardKey key;
        final String filename;
        final TimeSeries[] rows = new TimeSeries[256];
        int count;
        long bytes = 2304;
        boolean dirty;
        Shard dirtyPrevious, dirtyNext;
        Shard(ShardKey key) {
            this.key = key;
            filename = "n" + key.network + "-s" + key.index;
        }
    }

    /** The lookup instance is reused but never inserted; stored keys are never mutated. */
    private static final class ShardKey {
        long network, index;
        ShardKey(long network, long index) { this.network = network; this.index = index; }
        @Override public int hashCode() {
            long mixed = network * 0x9E3779B97F4A7C15L + index;
            return (int) (mixed ^ (mixed >>> 32));
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ShardKey)) return false;
            ShardKey key = (ShardKey) other;
            return network == key.network && index == key.index;
        }
    }

    public static final class SeriesView {
        public final SparseSeries points;
        public final long total;
        public final long widthTicks;
        SeriesView(SparseSeries points, long total, long widthTicks) {
            this.points = points;
            this.total = total;
            this.widthTicks = widthTicks;
        }
    }
}
