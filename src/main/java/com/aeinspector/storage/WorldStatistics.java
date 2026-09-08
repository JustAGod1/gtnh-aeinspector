package com.aeinspector.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.nbt.NBTTagCompound;

import com.aeinspector.core.DeviceDictionary;
import com.aeinspector.core.NetworkRecord;
import com.aeinspector.core.ResourceDictionary;

/** Server-owned world state. Persist identity before any series data can reference a new ID. */
public final class WorldStatistics implements AutoCloseable {
    public final ResourceDictionary resources;
    public final DeviceDictionary devices;
    public final SeriesDatabase database;
    private final SegmentStore metadata;
    private final ArrayList<NetworkRecord> networks = new ArrayList<>();
    private long tick;
    private long reservedUntil;
    private int savedResources, savedDevices, savedNetworks, savedPairs;

    public WorldStatistics() {
        metadata = null; database = new SeriesDatabase();
        resources = new ResourceDictionary(); devices = new DeviceDictionary();
    }

    public WorldStatistics(NBTTagCompound tag) throws IOException {
        if (tag.getInteger("version") != 1) throw new IOException("Unsupported Inspector NBT version");
        metadata = null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(tag.getByteArray("dictionary")))) {
            if (in.readInt() != 1) throw new IOException("Unsupported Inspector dictionary version");
            tick = in.readLong();
            if (tick < 0) throw new IOException("Invalid saved tick");
            resources = ResourceDictionary.read(in); devices = DeviceDictionary.read(in);
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) throw new IOException("Invalid network count");
            for (int i = 0; i < count; i++) {
                NetworkRecord record = NetworkRecord.read(in);
                if (record.id != i) throw new IOException("Invalid network ID");
                networks.add(record);
            }
            if (in.read() != -1) throw new IOException("Trailing dictionary data");
        }
        database = SeriesDatabase.readNBT(tag.getTagList("series", 10));
    }

    public void writeNBT(NBTTagCompound tag) throws IOException {
        if (metadata == null) drain(); // Legacy mode is used only for importing already saved files.
        tag.setInteger("version", 1);
        tag.setByteArray("dictionary", encodeMetadata(tick));
        tag.setTag("series", database.writeNBT());
    }

    public WorldStatistics(Path directory) throws IOException {
        Files.createDirectories(directory);
        metadata = new SegmentStore(directory.resolve("metadata"), 32 * 1024 * 1024, 0);
        database = new SeriesDatabase(directory.resolve("series"));
        if (Files.exists(directory.resolve("metadata/world.aeis"))) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(metadata.read("world")))) {
                if (in.readInt() != 1) throw new IOException("Unsupported inspector world version");
                tick = in.readLong();
                if (tick < 0) throw new IOException("Invalid saved tick");
                resources = ResourceDictionary.read(in);
                devices = DeviceDictionary.read(in);
                int count = in.readInt();
                if (count < 0 || count > 1_000_000) throw new IOException("Invalid network count");
                for (int i = 0; i < count; i++) {
                    NetworkRecord record = NetworkRecord.read(in);
                    if (record.id != i) throw new IOException("Invalid network ID");
                    networks.add(record);
                }
                if (in.read() != -1) throw new IOException("Trailing world metadata");
            }
        } else {
            try (java.util.stream.Stream<Path> files = Files.list(directory.resolve("series"))) {
                if (files.anyMatch(p -> p.toString().endsWith(".aeis"))) {
                    throw new IOException("Series exist without their resource dictionary; refusing to reuse IDs");
                }
            }
            resources = new ResourceDictionary();
            devices = new DeviceDictionary();
        }
    }

    public long tick() { return tick; }
    public int networkCount() { return networks.size(); }
    public NetworkRecord network(int id) { return networks.get(id); }

    public NetworkRecord createNetwork(long... parents) {
        for (long parent : parents) if (parent < 0 || parent >= networks.size()) throw new IllegalArgumentException("Parent");
        NetworkRecord result = new NetworkRecord(networks.size(), tick, parents);
        networks.add(result);
        return result;
    }

    public void endTick() throws IOException {
        drain();
        tick++;
        database.maintain(1, 500_000);
        if (tick % 100 == 0) checkpoint();
    }

    public void drain() throws IOException {
        int pairCount = 0;
        for (NetworkRecord network : networks) pairCount += network.pairCount();
        if (metadata != null && (tick >= reservedUntil || savedResources != resources.size() || savedDevices != devices.size()
                || savedNetworks != networks.size() || savedPairs != pairCount)) persistMetadata(tick + 100);
        for (NetworkRecord network : networks) {
            if (network.pending.size() == 0) continue;
            database.append(network.id, tick, network.pending);
            network.pending.clear();
        }
    }

    public void checkpoint() throws IOException {
        drain();
        database.flush();
        // Clean checkpoints recover the exact clock; crash checkpoints use the reserved upper bound.
        if (metadata != null) persistMetadata(tick);
    }

    private byte[] encodeMetadata(long recoveryTick) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeLong(recoveryTick);
            resources.write(out);
            devices.write(out);
            out.writeInt(networks.size());
            for (NetworkRecord network : networks) network.write(out);
        }
        return bytes.toByteArray();
    }

    private void persistMetadata(long recoveryTick) throws IOException {
        metadata.write("world", encodeMetadata(recoveryTick));
        reservedUntil = recoveryTick;
        savedResources = resources.size();
        savedDevices = devices.size();
        savedNetworks = networks.size();
        savedPairs = 0;
        for (NetworkRecord network : networks) savedPairs += network.pairCount();
    }

    @Override
    public void close() throws IOException {
        checkpoint();
        database.close();
        if (metadata != null) metadata.close();
    }
}
