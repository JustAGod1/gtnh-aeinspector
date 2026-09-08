package com.aeinspector.storage;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import com.aeinspector.core.NetworkRecord;
import com.aeinspector.core.TimeSeries;

/** A resumable ancestry walk and pair index. No ancestry walk is repeated for an individual resource. */
public final class HistoryIndex {
    public final NetworkRecord root;
    private final WorldStatistics world;
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final BitSet visited = new BitSet();
    private final Map<Long, List<Reference>> rows = new HashMap<>();
    private final TreeMap<Long, Long> observations = new TreeMap<>();
    private final BitSet resources = new BitSet();
    private final Map<Integer, BitSet> devices = new HashMap<>();
    private final Set<NetworkRecord> changedPairs = new LinkedHashSet<>();
    private final int[] indexedPairs;
    private final Consumer<NetworkRecord> pairChanges = record -> {
        if (visited.get(record.id)) changedPairs.add(record);
    };
    private NetworkRecord reading;
    private int parent, pair, interval;
    private boolean ready;
    private long intervalStart = -1, intervalEnd;
    private int walked;

    public HistoryIndex(WorldStatistics world, int network) {
        this.world = world; root = world.network(network);
        indexedPairs = new int[world.networkCount()];
        visited.set(root.id); reading = root;
        world.listenForPairs(pairChanges);
    }
    public boolean ready() { return ready && changedPairs.isEmpty() && indexedPairs[root.id] == root.pairCount(); }
    public int walkedNetworks() { return walked; }
    public int firstResource(int from) { return resources.nextSetBit(from); }
    public int firstDevice(int resource, int from) {
        BitSet set = devices.get(resource); return set == null ? -1 : set.nextSetBit(from);
    }

    /** Each call handles a parent edge, a pair, or one coverage interval/merge. */
    public boolean step() {
        if (intervalStart >= 0) { mergeIntervalStep(); return false; }
        if (reading == null) {
            if (!pending.isEmpty()) {
                reading = world.network(pending.removeFirst()); parent = pair = interval = 0;
            } else {
                if (indexedPairs[root.id] < root.pairCount()) { indexPair(root, indexedPairs[root.id]); return false; }
                if (!changedPairs.isEmpty()) {
                    NetworkRecord changed = changedPairs.iterator().next();
                    int next = indexedPairs[changed.id];
                    if (next < changed.pairCount()) indexPair(changed, next);
                    else changedPairs.remove(changed);
                    return false;
                }
                ready = true;
                return true;
            }
        }
        if (parent < reading.parents.length) {
            int id = Math.toIntExact(reading.parents[parent++]);
            if (!visited.get(id)) { visited.set(id); pending.addLast(id); }
            return false;
        }
        if (pair < reading.pairCount()) {
            indexPair(reading, pair++);
            return false;
        }
        // The current segment keeps changing. Its coverage is read live; ancestors are completed segments.
        if (reading != root && interval < reading.coverageCount()) {
            intervalStart = reading.coverageStart(interval); intervalEnd = reading.coverageEnd(interval++);
            return false;
        }
        walked++; reading = null;
        return false;
    }
    private void indexPair(NetworkRecord record, int pair) {
        if (pair < indexedPairs[record.id]) return;
        indexedPairs[record.id] = pair + 1;
        long key = record.pairKey(pair);
        int resource = (int) (key >>> 32), device = (int) key;
        resources.set(resource);
        if (device >= 0) devices.computeIfAbsent(resource, ignored -> new BitSet()).set(device);
        if (record != root) rows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Reference(record.id, pair));
    }
    private void mergeIntervalStep() {
        Map.Entry<Long, Long> previous = observations.floorEntry(intervalStart);
        if (previous != null && previous.getValue() >= intervalStart) {
            intervalStart = previous.getKey(); intervalEnd = Math.max(intervalEnd, previous.getValue());
            observations.remove(previous.getKey()); return;
        }
        Map.Entry<Long, Long> next = observations.ceilingEntry(intervalStart);
        if (next != null && next.getKey() <= intervalEnd) {
            intervalEnd = Math.max(intervalEnd, next.getValue()); observations.remove(next.getKey()); return;
        }
        observations.put(intervalStart, intervalEnd); intervalStart = -1;
    }

    public Coverage coverage(long now) {
        if (!ready) throw new IllegalStateException("History index is not ready");
        return new Coverage(now);
    }
    public Read read(int resource, int device, int level, long now, boolean graph, Coverage coverage) throws IOException {
        if (!ready || !coverage.done) throw new IllegalStateException("History context is not ready");
        return new Read(resource, device, level, now, graph, coverage);
    }

    /** Merge observation coverage once per response, then answer window coverage by binary search. */
    public final class Coverage {
        private final long now;
        private final java.util.Iterator<Map.Entry<Long, Long>> parents = observations.entrySet().iterator();
        private final List<Interval> intervals = new ArrayList<>();
        private Map.Entry<Long, Long> nextParent;
        private final int rootLimit;
        private int rootPosition;
        private boolean done;
        private Coverage(long now) {
            this.now = now; rootLimit = root.coverageCount();
            nextParent = parents.hasNext() ? parents.next() : null;
        }
        public boolean step() {
            if (done) return true;
            if (nextParent != null && (rootPosition >= rootLimit || nextParent.getKey() <= root.coverageStart(rootPosition))) {
                add(nextParent.getKey(), Math.min(now, nextParent.getValue()));
                nextParent = parents.hasNext() ? parents.next() : null; return false;
            }
            if (rootPosition < rootLimit) {
                add(root.coverageStart(rootPosition), Math.min(now, root.coverageEnd(rootPosition++))); return false;
            }
            done = true; return true;
        }
        private void add(long from, long to) {
            if (from >= to) return;
            Interval last = intervals.isEmpty() ? null : intervals.get(intervals.size() - 1);
            if (last != null && from <= last.to) { last.to = Math.max(last.to, to); return; }
            intervals.add(new Interval(from, to, last == null ? 0 : last.prefix + last.to - last.from));
        }
        private long before(long tick) {
            int low = 0, high = intervals.size();
            while (low < high) { int mid = (low + high) >>> 1; if (intervals.get(mid).from < tick) low = mid + 1; else high = mid; }
            if (low == 0) return 0;
            Interval range = intervals.get(low - 1);
            return range.prefix + Math.min(tick, range.to) - range.from;
        }
        private long between(long from, long to, long liveFrom, long liveTo) {
            return before(to) - before(from) + Math.max(0, Math.min(to, liveTo) - Math.max(from, Math.max(now, liveFrom)));
        }
    }
    private static final class Interval {
        final long from, prefix; long to;
        Interval(long from, long to, long prefix) { this.from = from; this.to = to; this.prefix = prefix; }
    }

    public final class Read {
        public final long start, width, now;
        public final long[] counts = new long[4], totals = new long[4];
        public final long[][] points;
        public final long[] observed;
        private final int level;
        private final List<Reference> references;
        private int reference;
        private final long observedTotal;
        private boolean done;

        private Read(int resource, int device, int level, long now, boolean graph, Coverage coverage) throws IOException {
            if (level < 0 || level > 8 || now < 0) throw new IllegalArgumentException("History window");
            this.level = level; this.now = now;
            long w = level == 8 ? 1 : TimeSeries.WIDTHS[level];
            if (level == 8) while (now > w * 300 && w <= Long.MAX_VALUE / 600) w *= 2;
            width = w;
            int count = (int) Math.min(level == 8 ? 300 : HistoryQuery.DURATIONS[level] / w, now == 0 ? 0 : (now - 1) / w + 1);
            start = level == 8 || count == 0 ? 0 : ((now - 1) / w - count + 1) * w;
            points = graph ? new long[4][count] : null;
            observed = graph ? new long[count] : null;
            long key = ((long) resource << 32) | (device & 0xffffffffL);
            references = rows.getOrDefault(key, Collections.emptyList());
            // Capture the live segment in the same server turn as 'now'. Later steps read completed ancestors.
            long current = root.findPair(resource, device);
            if (current >= 0) collect(root.id, current);
            int last = root.coverageCount() - 1;
            long liveFrom = last < 0 ? now : root.coverageStart(last), liveTo = last < 0 ? now : Math.min(now, root.coverageEnd(last));
            observedTotal = coverage.between(start, now, liveFrom, liveTo);
            if (observed != null) for (int i = 0; i < count; i++) {
                long from = start + i * width;
                observed[i] = coverage.between(from, Math.min(now, from + width), liveFrom, liveTo);
            }
        }
        public boolean step() throws IOException {
            if (done) return true;
            if (reference < references.size()) {
                Reference row = references.get(reference++); collect(row.network, row.pair); return false;
            }
            done = true; return true;
        }
        private void collect(long network, long pair) throws IOException {
            for (int c = 0; c < 4; c++) world.database.collect(network, pair * 4 + c, level, start, now, width,
                    counts, totals, c, points == null ? null : points[c]);
        }
        public long count(boolean incoming) { return Math.addExact(counts[incoming ? 0 : 1], counts[incoming ? 2 : 3]); }
        public double rate(boolean incoming) { return observedTotal == 0 ? Double.NaN : count(incoming) * 20.0 / observedTotal; }
        public long observedTicks() { return observedTotal; }
    }
    private static final class Reference {
        final int network, pair;
        Reference(int network, int pair) { this.network = network; this.pair = pair; }
    }
}
