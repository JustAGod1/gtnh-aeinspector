package com.aeinspector.storage;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.InputStream;
import java.util.Arrays;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import com.aeinspector.core.NetworkRecord;

/** Optional read-only regression check of a copied aeinspector.dat. Never launches Minecraft. */
public final class VerifySavedHistory {
    public static void main(String[] args) throws Exception {
        NBTTagCompound saved;
        try (InputStream input = Files.newInputStream(Paths.get(args[0]))) { saved = CompressedStreamTools.readCompressed(input).getCompoundTag("data"); }
        try (WorldStatistics world = new WorldStatistics(saved)) {
            int root = -1;
            long[] depth = new long[world.networkCount()];
            for (int n = 0; n < world.networkCount(); n++) {
                NetworkRecord record = world.network(n);
                depth[n] = 1;
                for (long parent : record.parents) {
                    if (parent >= n || parent < 0) throw new AssertionError("Invalid ancestor order");
                    depth[n] = Math.max(depth[n], depth[(int) parent] + 1);
                }
                if (root < 0 || depth[n] >= depth[root]) root = n;
            }
            if (root < 0) throw new AssertionError("Empty history");
            HistoryIndex index = new HistoryIndex(world, root);
            while (!index.ready()) index.step();
            HistoryIndex.Coverage coverage = index.coverage(world.tick());
            while (!coverage.step()) {}
            int resources = 0, comparisons = 0;
            for (int id = index.firstResource(0); id >= 0; id = index.firstResource(id + 1)) {
                resources++;
                HistoryIndex.Read summary = index.read(id, -1, 0, world.tick(), false, coverage);
                while (!summary.step()) {}
                if (summary.points != null || summary.observed != null) throw new AssertionError("Summary allocated a graph");
                if (resources > 6) continue;
                for (int level = 0; level < 9; level++) {
                    HistoryQuery.Result expected = new HistoryQuery(world).query(root, id, -1, level, world.tick());
                    HistoryIndex.Read actual = index.read(id, -1, level, world.tick(), true, coverage);
                    while (!actual.step()) {}
                    if (!Arrays.equals(expected.totals, actual.totals) || !Arrays.equals(expected.observedTicks, actual.observed))
                        throw new AssertionError("Coverage/totals differ for " + id + " level " + level);
                    for (int c = 0; c < 4; c++) if (!Arrays.equals(expected.counts[c], actual.points[c]))
                        throw new AssertionError("Counts differ for " + id + " level " + level + " channel " + c);
                    comparisons++;
                }
            }
            System.out.println("PASS networks=" + world.networkCount() + " chosenRoot=" + root + " ancestors=" + index.walkedNetworks()
                    + " resources=" + resources + " graphComparisons=" + comparisons);
        }
    }
}
