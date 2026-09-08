package com.aeinspector.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import net.minecraft.nbt.NBTTagCompound;

import com.aeinspector.core.NetworkRecord;

import org.junit.Test;

public class WorldStatisticsTest {
    @Test
    public void restoresIdentitiesClockAndActualHistory() throws Exception {
        Path directory = Files.createTempDirectory("aeinspector-world-test");
        int resource, device, network;
        long pair;
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("quality", 4);
        try (WorldStatistics world = new WorldStatistics(directory)) {
            resource = world.resources.resolve((byte) 0, "example:plate", 7, tag);
            device = world.devices.resolve(-1, 100, 64, -200, 2, "export", "Export Bus");
            NetworkRecord record = world.createNetwork();
            network = record.id;
            record.observe(world.tick());
            record.add(resource, device, false, false, 100000);
            pair = record.findPair(resource, device);
            world.endTick();
            assertEquals(1, world.tick());
        }
        try (WorldStatistics world = new WorldStatistics(directory)) {
            assertEquals(1, world.tick());
            assertEquals(resource, world.resources.resolve((byte) 0, "example:plate", 7, tag));
            assertEquals(device, world.devices.resolve(-1, 100, 64, -200, 2, "export", "Export Bus"));
            NetworkRecord record = world.network(network);
            assertEquals(pair, record.findPair(resource, device));
            assertEquals(100000, world.database.query(network, pair * 4 + 1, 8, world.tick()).total);
            record.observe(world.tick());
            record.add(resource, device, false, false, 20);
            world.endTick();
            assertEquals(100020, world.database.query(network, pair * 4 + 1, 8, world.tick()).total);
            NetworkRecord child = world.createNetwork(network);
            assertNotEquals(record.id, child.id);
            assertEquals(network, child.parents[0]);
            assertEquals(-1, child.findPair(resource, device));
            assertEquals(2, record.coverage().size()); // restart is an explicit observation boundary
        }
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(file);
        }
    }
}
