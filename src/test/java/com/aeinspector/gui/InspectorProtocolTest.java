package com.aeinspector.gui;

import static org.junit.Assert.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

public class InspectorProtocolTest {
    @Test public void requestRoundTrip() {
        InspectorProtocol.Request source = new InspectorProtocol.Request();
        source.window = 4; source.level = 8; source.resourceOffset = 19; source.deviceOffset = 2; source.sort = 2;
        source.search = "Уголь #42"; source.selected = new int[] {42, 12, 700};
        source.sequence = 87; source.rows = 5; source.devices = true; source.filter = ResourceFilter.FLUIDS;
        ByteBuf bytes = Unpooled.buffer();
        source.toBytes(bytes);
        InspectorProtocol.Request target = new InspectorProtocol.Request();
        target.fromBytes(bytes);
        assertEquals(source.window, target.window);
        assertEquals(source.level, target.level);
        assertEquals(source.resourceOffset, target.resourceOffset);
        assertEquals(source.deviceOffset, target.deviceOffset);
        assertEquals(source.sort, target.sort);
        assertEquals(source.sequence, target.sequence);
        assertEquals(source.rows, target.rows);
        assertEquals(source.devices, target.devices);
        assertEquals(source.filter, target.filter);
        assertEquals(source.search, target.search);
        assertArrayEquals(source.selected, target.selected);
        bytes.release();
    }
    @Test public void invalidSelectionAndOversizedRequestsAreRejected() {
        InspectorProtocol.Request request = new InspectorProtocol.Request();
        request.selected = new int[] {1, 1};
        ByteBuf duplicate = Unpooled.buffer(); request.toBytes(duplicate);
        assertThrows(IllegalArgumentException.class, () -> new InspectorProtocol.Request().fromBytes(duplicate));
        duplicate.release();
        request.selected = new int[9];
        ByteBuf tooMany = Unpooled.buffer(); request.toBytes(tooMany);
        assertThrows(IllegalArgumentException.class, () -> new InspectorProtocol.Request().fromBytes(tooMany));
        tooMany.release();
        ByteBuf huge = Unpooled.buffer().writeZero(601);
        assertThrows(IllegalArgumentException.class, () -> new InspectorProtocol.Request().fromBytes(huge));
        huge.release();
    }
    @Test public void snapshotPreservesLongCounts() throws Exception {
        NBTTagCompound data = new NBTTagCompound();
        data.setLong("total", Long.MAX_VALUE - 5);
        data.setString("name", "Жидкость");
        InspectorProtocol.Snapshot source = new InspectorProtocol.Snapshot(data);
        ByteBuf bytes = Unpooled.buffer(); source.toBytes(bytes);
        InspectorProtocol.Snapshot target = new InspectorProtocol.Snapshot(); target.fromBytes(bytes);
        assertEquals(data, target.decode());
        bytes.release();
    }
    @Test public void invalidRevisionAndRowCountsAreRejected() {
        for (int[] options : new int[][] {{-1, 5}, {1, 0}, {1, 11}}) {
            InspectorProtocol.Request request = new InspectorProtocol.Request();
            request.sequence = options[0]; request.rows = options[1];
            ByteBuf bytes = Unpooled.buffer(); request.toBytes(bytes);
            try { assertThrows(IllegalArgumentException.class, () -> new InspectorProtocol.Request().fromBytes(bytes)); }
            finally { bytes.release(); }
        }
    }
    @Test public void aQueuedRequestOwnsItsSelectionAndOptions() {
        InspectorProtocol.Request request = new InspectorProtocol.Request();
        request.level = 4; request.devices = true; request.selected = new int[] {12}; request.filter = ResourceFilter.ITEMS;
        InspectorProtocol.Request queued = request.copy();
        request.selected[0] = 99; request.level = 8; request.devices = false; request.filter = ResourceFilter.FLUIDS;
        assertEquals(12, queued.selected[0]); assertEquals(4, queued.level); assertTrue(queued.devices);
        assertEquals(ResourceFilter.ITEMS, queued.filter);
    }
    @Test public void invalidResourceFilterIsRejected() {
        InspectorProtocol.Request request = new InspectorProtocol.Request(); request.filter = 3;
        ByteBuf bytes = Unpooled.buffer();
        try { request.toBytes(bytes); assertThrows(IllegalArgumentException.class, () -> new InspectorProtocol.Request().fromBytes(bytes)); }
        finally { bytes.release(); }
    }
}
