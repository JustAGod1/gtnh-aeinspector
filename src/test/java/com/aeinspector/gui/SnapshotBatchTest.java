package com.aeinspector.gui;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.Test;

public class SnapshotBatchTest {
    @Test public void hiddenStatisticsKeepsReceivingWhileIoChangesAndCloses() throws Exception {
        SnapshotViews views = new SnapshotViews();
        views.expect(false, 4, 20); views.expect(true, 4, 21);
        List<InspectorProtocol.Snapshot> main = response(20, 1, 300, false), io = response(21, 1, 5, true);
        views.receive(main.get(0));
        for (InspectorProtocol.Snapshot packet : io) views.receive(packet);
        assertEquals(5, views.take(true).decodeStep().getTagList("devices", 10).tagCount());
        views.expect(true, 4, 22); // changing the I/O period cannot clear an incomplete main response
        for (int i = 1; i < main.size(); i++) views.receive(main.get(i));
        SnapshotBatch batch = views.take(false); assertNotNull(batch);
        NBTTagCompound decoded; do { decoded = batch.decodeStep(); } while (decoded == null);
        assertEquals(300, decoded.getTagList("resources", 10).tagCount());
        for (InspectorProtocol.Snapshot packet : response(20, 2, 40, false)) views.receive(packet);
        views.close(true); // back retains the latest main snapshot, without a new expect/request
        for (InspectorProtocol.Snapshot packet : response(22, 2, 4, true)) views.receive(packet);
        assertNull(views.take(true));
        assertEquals(40, views.take(false).decodeStep().getTagList("resources", 10).tagCount());
        views.close();
        for (InspectorProtocol.Snapshot packet : response(20, 3, 10, false)) views.receive(packet);
        assertNull(views.take(false));
    }
    @Test public void tenThousandRowsArriveInBoundedPacketsAndBecomeVisibleTogether() throws Exception {
        List<InspectorProtocol.Snapshot> packets = response(3, 1, 10000, false);
        assertTrue(packets.size() > 70);
        SnapshotBatch.Inbox inbox = new SnapshotBatch.Inbox();
        for (int i = 0; i < packets.size(); i++) {
            InspectorProtocol.Snapshot packet = wire(packets.get(i));
            assertTrue(packet.byteSize() <= 512 * 1024);
            assertTrue(packet.decode().getTagList("rows", 10).tagCount() <= SnapshotStream.ROWS_PER_PACKET);
            inbox.receive(packet);
            if (i < packets.size() - 1) assertNull(inbox.take());
        }
        SnapshotBatch batch = inbox.take();
        assertEquals(4, batch.window); assertEquals(3, batch.sequence);
        NBTTagCompound decoded = null;
        for (int i = 0; i < packets.size(); i++) {
            decoded = batch.decodeStep();
            if (i < packets.size() - 1) assertNull(decoded);
        }
        NBTTagList rows = decoded.getTagList("resources", 10);
        assertEquals(10000, rows.tagCount());
        for (int i = 0; i < 10000; i++) {
            assertEquals(i, rows.getCompoundTagAt(i).getInteger("id"));
            assertEquals(Long.MAX_VALUE - i, rows.getCompoundTagAt(i).getLong("in"));
            assertEquals("Ресурс " + i, rows.getCompoundTagAt(i).getString("name"));
        }
        assertEquals(456, decoded.getLong("tick"));
        assertNull(inbox.take());
    }
    @Test public void cancelledRevisionAndRestartedTopologyCannotMixRows() throws Exception {
        List<InspectorProtocol.Snapshot> old = response(10, 1, 500, false);
        List<InspectorProtocol.Snapshot> changed = response(11, 2, 300, false);
        List<InspectorProtocol.Snapshot> retry = response(11, 3, 4, true);
        SnapshotBatch.Inbox inbox = new SnapshotBatch.Inbox();
        inbox.receive(old.get(0)); inbox.receive(changed.get(0));
        for (InspectorProtocol.Snapshot packet : old) inbox.receive(packet);
        for (InspectorProtocol.Snapshot packet : retry) inbox.receive(packet);
        for (InspectorProtocol.Snapshot packet : changed) inbox.receive(packet);
        NBTTagCompound result = inbox.take().decodeStep();
        assertEquals(4, result.getTagList("devices", 10).tagCount());
        assertEquals(0, result.getTagList("resources", 10).tagCount());
        assertNull(inbox.take());
    }
    @Test public void missingPacketsAndIncompleteRowCountsAreNotPublishedAsAValidList() throws Exception {
        List<InspectorProtocol.Snapshot> packets = response(2, 1, 500, false);
        SnapshotBatch.Inbox inbox = new SnapshotBatch.Inbox();
        inbox.receive(packets.get(0));
        for (int i = 2; i < packets.size(); i++) inbox.receive(packets.get(i));
        assertNull(inbox.take());
        inbox.clear();
        NBTTagCompound incomplete = new NBTTagCompound(); incomplete.setInteger("resourceCount", 1);
        inbox.receive(new InspectorProtocol.Snapshot(incomplete, 4, 3, 1, 0, true));
        assertThrows(IOException.class, () -> inbox.take().decodeStep());
    }
    @Test public void emptyFilteredListStillCompletes() throws Exception {
        SnapshotBatch.Inbox inbox = new SnapshotBatch.Inbox();
        for (InspectorProtocol.Snapshot packet : response(1, 1, 0, false)) inbox.receive(packet);
        assertEquals(0, inbox.take().decodeStep().getTagList("resources", 10).tagCount());
    }
    @Test public void onlyTheOpenScreensCurrentRequestCanRetainPackets() throws Exception {
        SnapshotBatch.Inbox inbox = new SnapshotBatch.Inbox();
        inbox.expect(4, 12);
        for (InspectorProtocol.Snapshot packet : response(11, 1, 300, false)) inbox.receive(packet);
        assertNull(inbox.take());
        inbox.receive(response(12, 1, 500, false).get(0));
        inbox.close();
        for (InspectorProtocol.Snapshot packet : response(12, 2, 1, false)) inbox.receive(packet);
        assertNull(inbox.take());
        inbox.expect(4, 13);
        for (InspectorProtocol.Snapshot packet : response(13, 1, 2, false)) inbox.receive(packet);
        assertEquals(2, inbox.take().decodeStep().getTagList("resources", 10).tagCount());
    }
    private static List<InspectorProtocol.Snapshot> response(int sequence, int batch, int count, boolean devices) throws Exception {
        List<InspectorProtocol.Snapshot> packets = new ArrayList<>();
        SnapshotStream stream = new SnapshotStream(4, sequence, batch, packets::add);
        for (int id = 0; id < count; id++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("id", id); row.setLong("in", Long.MAX_VALUE - id);
            row.setString("name", "Ресурс " + id); row.setBoolean("fluid", id % 2 == 0);
            stream.add(row); if (stream.needsFlush()) stream.flush();
        }
        NBTTagCompound metadata = new NBTTagCompound(); metadata.setBoolean("devicesView", devices);
        metadata.setInteger(devices ? "deviceCount" : "resourceCount", count); metadata.setLong("tick", 456);
        stream.finish(metadata); return packets;
    }
    private static InspectorProtocol.Snapshot wire(InspectorProtocol.Snapshot source) {
        ByteBuf bytes = Unpooled.buffer();
        try {
            source.toBytes(bytes); InspectorProtocol.Snapshot target = new InspectorProtocol.Snapshot(); target.fromBytes(bytes); return target;
        } finally { bytes.release(); }
    }
}
