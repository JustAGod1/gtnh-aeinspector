package com.aeinspector.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** A complete transport batch. Decoding is incremental on the existing client game loop. */
final class SnapshotBatch {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    final int window, sequence;
    private final List<InspectorProtocol.Snapshot> packets;
    private final NBTTagList rows = new NBTTagList();
    private int position;

    private SnapshotBatch(List<InspectorProtocol.Snapshot> packets) {
        this.packets = packets;
        window = packets.get(0).window; sequence = packets.get(0).sequence;
    }
    NBTTagCompound decodeStep() throws IOException {
        NBTTagCompound data = packets.get(position++).decode();
        NBTTagList chunk = data.getTagList("rows", 10);
        if (chunk.tagCount() > SnapshotStream.ROWS_PER_PACKET) throw new IOException("Oversized row chunk");
        for (int i = 0; i < chunk.tagCount(); i++) rows.appendTag(chunk.getCompoundTagAt(i));
        if (position < packets.size()) return null;
        boolean devices = data.getBoolean("devicesView");
        if (data.getInteger(devices ? "deviceCount" : "resourceCount") != rows.tagCount()) throw new IOException("Incomplete inspector list");
        data.removeTag("rows"); data.setTag(devices ? "devices" : "resources", rows);
        return data;
    }

    /** Transport only: retain compressed packets and publish only a complete, ordered batch. */
    static final class Inbox {
        private List<InspectorProtocol.Snapshot> pending;
        private int window, sequence = -1, batch = -1, bytes;
        private int expectedWindow = -1, expectedSequence = -1;
        private boolean closed;
        private SnapshotBatch complete;

        synchronized void receive(InspectorProtocol.Snapshot packet) {
            if (closed || expectedSequence >= 0 && (packet.sequence != expectedSequence || packet.window != expectedWindow)) return;
            if (packet.sequence < sequence || packet.sequence == sequence && packet.window == window && packet.batch < batch) return;
            if (packet.part == 0) {
                pending = new ArrayList<>(); bytes = 0;
                window = packet.window; sequence = packet.sequence; batch = packet.batch;
            }
            if (pending == null || packet.window != window || packet.sequence != sequence || packet.batch != batch
                    || packet.part != pending.size()) return;
            bytes = Math.addExact(bytes, packet.byteSize());
            if (bytes > MAX_BYTES || pending.size() >= 65536) {
                pending = null; throw new IllegalArgumentException("Oversized inspector batch");
            }
            pending.add(packet);
            if (packet.last) { complete = new SnapshotBatch(pending); pending = null; }
        }
        synchronized SnapshotBatch take() { SnapshotBatch result = complete; complete = null; return result; }
        synchronized void clear() {
            pending = null; complete = null; sequence = batch = -1; bytes = 0;
            expectedWindow = expectedSequence = -1; closed = false;
        }
        synchronized void expect(int window, int sequence) { clear(); expectedWindow = window; expectedSequence = sequence; }
        synchronized void close() { clear(); closed = true; }
    }
}
