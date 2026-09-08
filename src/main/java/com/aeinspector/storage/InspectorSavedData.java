package com.aeinspector.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSavedData;

/** Vanilla MapStorage writes this NBT together with world data. No file paths or custom writer. */
public final class InspectorSavedData extends WorldSavedData {
    public static final String NAME = "aeinspector";
    private WorldStatistics statistics = new WorldStatistics();
    private IOException loadFailure;

    public InspectorSavedData(String name) { super(name); }
    public void importLegacy(java.nio.file.Path root) throws IOException {
        if (!java.nio.file.Files.isRegularFile(root.resolve("metadata/world.aeis"))) return;
        WorldStatistics legacy = new WorldStatistics(root);
        NBTTagCompound tag = new NBTTagCompound();
        legacy.writeNBT(tag);
        statistics = new WorldStatistics(tag);
        // Don't call legacy.close(): that API checkpoints the old file format.
        legacy.database.close();
        markDirty();
    }
    public WorldStatistics statistics() {
        if (loadFailure != null) throw new UncheckedIOException("Cannot load Inspector NBT", loadFailure);
        return statistics;
    }
    @Override public void readFromNBT(NBTTagCompound tag) {
        try { statistics = new WorldStatistics(tag); }
        catch (IOException e) { loadFailure = e; }
    }
    @Override public void writeToNBT(NBTTagCompound tag) {
        try { statistics().writeNBT(tag); }
        catch (IOException e) { throw new UncheckedIOException("Cannot serialize Inspector NBT", e); }
    }
}
