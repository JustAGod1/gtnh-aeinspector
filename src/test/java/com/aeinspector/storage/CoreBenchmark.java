package com.aeinspector.storage;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import com.aeinspector.core.NetworkRecord;
import com.aeinspector.core.ResourceDictionary;
import com.aeinspector.core.TransferTracker;

/** Actual dictionary/counter/history/disk path, without Minecraft, viewers or Mixin dispatch overhead. */
public final class CoreBenchmark {
    private static final int CALLS = 5000, KEYS = 10000, RESOURCES = 100;
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "run-");
        List<String> report = new ArrayList<>();
        report.add("JVM=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        report.add("OS=" + System.getProperty("os.name") + "; processors=" + Runtime.getRuntime().availableProcessors());
        report.add("100000 calls/s at 20 TPS; 100000 items/call; 10000 device-resource pairs; exact small NBT");
        report.add("50 warmup ticks; 200 measured ticks; includes synchronous persistence; excludes AE/world/viewer overhead");
        NBTTagCompound[] tags = new NBTTagCompound[RESOURCES];
        long[] transferNanos = new long[200], historyNanos = new long[200], totalNanos = new long[200];
        long[] rollupNanos = new long[200], maintenanceNanos = new long[200];
        long beforeGc = gcMillis();
        long expected = 0;
        try (WorldStatistics world = new WorldStatistics(directory)) {
            NetworkRecord network = world.createNetwork();
            for (int i = 0; i < RESOURCES; i++) {
                tags[i] = new NBTTagCompound(); tags[i].setInteger("variant", i); tags[i].setString("label", "known");
                world.resources.resolve(ResourceDictionary.ITEM, "test:item", 0, tags[i]);
            }
            for (int i = 0; i < KEYS / RESOURCES; i++) world.devices.resolve(0, i, 64, 0, 0, "test", "Test bus");
            TransferTracker tracker = new TransferTracker((n, r, d, incoming, amount) -> network.add(r, d, incoming, false, amount));
            int key = 0;
            for (int tick = 0; tick < 250; tick++) {
                long started = System.nanoTime();
                network.observe(world.tick());
                for (int call = 0; call < CALLS; call++) {
                    int resource = world.resources.resolve(ResourceDictionary.ITEM, "test:item", 0, tags[key % RESOURCES]);
                    int frame = tracker.begin(network.id, resource, key / RESOURCES + 1, 100000, true, true, false);
                    tracker.finish(frame, 0);
                    if (++key == KEYS) key = 0;
                    expected += 100000;
                }
                long afterTransfers = System.nanoTime();
                world.drain();
                long afterRollup = System.nanoTime();
                world.endTick();
                long completed = System.nanoTime();
                if (tick >= 50) {
                    transferNanos[tick - 50] = afterTransfers - started;
                    historyNanos[tick - 50] = completed - afterTransfers;
                    totalNanos[tick - 50] = completed - started;
                    rollupNanos[tick - 50] = afterRollup - afterTransfers;
                    maintenanceNanos[tick - 50] = completed - afterRollup;
                }
                if (tick % 25 == 24) System.out.println("Benchmark tick=" + (tick + 1) + "/250");
            }
            report.add(summary("identity + transfer counters", transferNanos));
            report.add(summary("history + synchronous IO", historyNanos));
            report.add(summary("rollup + dictionary checkpoint", rollupNanos));
            report.add(summary("maintenance + periodic flush", maintenanceNanos));
            report.add(summary("combined", totalNanos));
            report.add("Estimated resident series bytes=" + world.database.residentBytes());
            report.add("Cached segment payload bytes=" + world.database.cachedSegmentBytes());
            report.add("Network pending counter array bytes=" + network.pending.allocatedBytes());
        }
        // Independently sum device rows after reopening, rather than trusting the network aggregate.
        long recovered = 0;
        try (WorldStatistics world = new WorldStatistics(directory)) {
            NetworkRecord network = world.network(0);
            for (int pair = 0; pair < network.pairCount(); pair++) {
                if ((int) network.pairKey(pair) < 0) continue;
                recovered = Math.addExact(recovered, world.database.query(0, pair * 4L, 8, world.tick()).total);
            }
        }
        if (recovered != expected) throw new AssertionError("Expected " + expected + ", restored " + recovered);
        report.add("Restored exact device total=" + recovered + " (PASS)");
        report.add("GC collection milliseconds including verification=" + (gcMillis() - beforeGc));
        report.add("Data directory=" + directory.toAbsolutePath());
        Files.write(directory.resolve("report.txt"), report, StandardCharsets.UTF_8);
        for (String line : report) System.out.println(line);
    }

    private static String summary(String name, long[] nanos) {
        Arrays.sort(nanos);
        return String.format(java.util.Locale.ROOT, "%s: p50=%.3f ms p95=%.3f ms p99=%.3f ms max=%.3f ms", name,
                nanos[99] / 1e6, nanos[189] / 1e6, nanos[197] / 1e6, nanos[199] / 1e6);
    }
    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) total += Math.max(0, gc.getCollectionTime());
        return total;
    }
}
