package com.aeinspector.storage;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

import com.aeinspector.core.NetworkRecord;
import com.aeinspector.core.SparseSeries;
import com.aeinspector.core.TimeSeries;

/** Server-thread query of one exact resource and endpoint, including deduplicated ancestors. */
public final class HistoryQuery {
    public static final long[] DURATIONS = {100, 1200, 12000, 72000, 720000, 3600000, 18000000, 72000000};
    private final WorldStatistics world;

    public HistoryQuery(WorldStatistics world) { this.world = world; }

    public List<NetworkRecord> lineage(int network) {
        List<NetworkRecord> result = new ArrayList<>();
        BitSet visited = new BitSet();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.push(network);
        while (!pending.isEmpty()) {
            int id = pending.pop();
            if (visited.get(id)) continue;
            visited.set(id);
            NetworkRecord record = world.network(id);
            result.add(record);
            for (long parent : record.parents) pending.push(Math.toIntExact(parent));
        }
        return result;
    }

    public Result query(int network, int resource, int device, int level, long now) throws IOException {
        if (resource < 0 || resource >= world.resources.size() || device < -1 || device >= world.devices.size()
                || level < 0 || level > 8 || now < 0) throw new IllegalArgumentException("History query");
        List<NetworkRecord> records = lineage(network);
        long width;
        long start;
        int count;
        if (level == 8) {
            width = 1;
            while (now > width * 300 && width <= Long.MAX_VALUE / 600) width *= 2;
            count = now == 0 ? 0 : (int) ((now - 1) / width + 1);
            start = 0;
        } else {
            width = TimeSeries.WIDTHS[level];
            int maximum = (int) (DURATIONS[level] / width);
            count = (int) Math.min(maximum, now == 0 ? 0 : (now - 1) / width + 1);
            start = count == 0 ? 0 : ((now - 1) / width - count + 1) * width;
        }
        Result result = new Result(start, width, now, count);
        List<long[]> intervals = new ArrayList<>();
        for (NetworkRecord record : records) {
            intervals.addAll(record.coverage());
            long pair = record.findPair(resource, device);
            if (pair < 0) continue;
            for (int channel = 0; channel < 4; channel++) {
                SeriesDatabase.SeriesView series = world.database.query(record.id, pair * 4 + channel, level, now);
                result.totals[channel] = Math.addExact(result.totals[channel], series.total);
                SparseSeries points = series.points;
                for (int i = 0; i < points.size(); i++) {
                    long time = points.time(i);
                    if (time < start || time >= now) continue;
                    int bucket = (int) ((time - start) / width);
                    result.counts[channel][bucket] = Math.addExact(result.counts[channel][bucket], points.value(i));
                }
            }
        }
        // Parallel ancestor segments may cover the same ticks: count their union, never sum coverage twice.
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        long from = -1, to = -1;
        for (long[] interval : intervals) {
            if (from < 0) { from = interval[0]; to = interval[1]; }
            else if (interval[0] <= to) to = Math.max(to, interval[1]);
            else {
                result.observe(from, to);
                from = interval[0]; to = interval[1];
            }
        }
        if (from >= 0) result.observe(from, to);
        return result;
    }

    public static final class Result {
        public final long start, width, now;
        /** Channels: exact incoming, exact outgoing, estimated incoming, estimated outgoing. */
        public final long[][] counts;
        public final long[] totals = new long[4];
        /** Zero means no observations, not an observed zero flow. */
        public final long[] observedTicks;

        private Result(long start, long width, long now, int count) {
            this.start = start; this.width = width; this.now = now;
            counts = new long[4][count];
            observedTicks = new long[count];
        }

        private void observe(long from, long to) {
            from = Math.max(start, from);
            to = Math.min(now, to);
            if (to <= from) return;
            int first = (int) ((from - start) / width);
            int last = (int) ((to - 1 - start) / width);
            for (int i = first; i <= last; i++) {
                long left = start + i * width;
                observedTicks[i] += Math.min(to, left + width) - Math.max(from, left);
            }
        }

        public long windowCount(boolean incoming) {
            int direction = incoming ? 0 : 1;
            long sum = 0;
            for (int i = 0; i < observedTicks.length; i++) {
                sum = Math.addExact(sum, counts[direction][i]);
                sum = Math.addExact(sum, counts[direction + 2][i]);
            }
            return sum;
        }

        public double rate(boolean incoming) {
            long ticks = 0;
            for (long count : observedTicks) ticks += count;
            return ticks == 0 ? Double.NaN : windowCount(incoming) * 20.0 / ticks;
        }
    }
}
