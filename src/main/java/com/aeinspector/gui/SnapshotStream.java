package com.aeinspector.gui;

import java.io.IOException;
import java.util.function.Consumer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Bounded row packets, emitted by the existing cooperative server queue. */
final class SnapshotStream {
    static final int ROWS_PER_PACKET = 128;
    private final int window, sequence, batch;
    private final Consumer<InspectorProtocol.Snapshot> send;
    private NBTTagList rows = new NBTTagList();
    private int part, estimatedBytes, sentBytes;

    SnapshotStream(int window, int sequence, int batch, Consumer<InspectorProtocol.Snapshot> send) {
        this.window = window; this.sequence = sequence; this.batch = batch; this.send = send;
    }
    void add(NBTTagCompound row) {
        rows.appendTag(row);
        // Descriptors contain no resource-key NBT. Account conservatively for UTF-8 names plus numeric fields.
        estimatedBytes += 1024 + 6 * (row.getString("name").length() + row.getString("registry").length());
    }
    boolean needsFlush() { return rows.tagCount() >= ROWS_PER_PACKET || estimatedBytes >= 64 * 1024; }
    void flush() throws IOException { emit(new NBTTagCompound(), false); }
    void finish(NBTTagCompound metadata) throws IOException { emit(metadata, true); }
    private void emit(NBTTagCompound data, boolean last) throws IOException {
        data.setTag("rows", rows);
        InspectorProtocol.Snapshot packet = new InspectorProtocol.Snapshot(data, window, sequence, batch, part, last);
        sentBytes = Math.addExact(sentBytes, packet.byteSize());
        if (sentBytes > SnapshotBatch.MAX_BYTES) throw new IOException("Inspector list exceeds transfer limit");
        send.accept(packet); part++;
        rows = new NBTTagList(); estimatedBytes = 0;
    }
}
