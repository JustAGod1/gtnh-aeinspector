package com.aeinspector.gui;

import static org.junit.Assert.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

public class InspectorProtocolTest {
    @Test public void requestRoundTrip() {
        InspectorProtocol.Request source = new InspectorProtocol.Request();
        source.window = 4; source.level = 8; source.resourcePage = 19; source.devicePage = 2; source.sort = 2;
        source.search = "Уголь #42"; source.selected = new int[] {42, 12, 700};
        ByteBuf bytes = Unpooled.buffer();
        source.toBytes(bytes);
        InspectorProtocol.Request target = new InspectorProtocol.Request();
        target.fromBytes(bytes);
        assertEquals(source.window, target.window);
        assertEquals(source.level, target.level);
        assertEquals(source.resourcePage, target.resourcePage);
        assertEquals(source.devicePage, target.devicePage);
        assertEquals(source.sort, target.sort);
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
}
