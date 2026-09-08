package com.aeinspector.gui;

import static org.junit.Assert.*;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

public class GraphSeriesTest {
    private NBTTagCompound snapshot() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("id", 3); tag.setString("name", "Уголь");
        tag.setLong("start", 100); tag.setLong("width", 4);
        tag.setByteArray("observed", InspectorData.longs(new long[] {4, 0, 4}));
        tag.setByteArray("c0", InspectorData.longs(new long[] {10, 0, 0}));
        tag.setByteArray("c1", InspectorData.longs(new long[] {5, 0, 0}));
        tag.setByteArray("c2", InspectorData.longs(new long[] {2, 0, 0}));
        tag.setByteArray("c3", InspectorData.longs(new long[] {1, 0, 0}));
        return tag;
    }
    @Test public void hoverBucketsKeepUnknownSeparateFromObservedZeroAndUsePerSecondRates() {
        GraphSeries graph = new GraphSeries(snapshot());
        assertEquals(102, graph.time(0), 0);
        assertEquals(-1, graph.bucket(99.9));
        assertEquals(0, graph.bucket(103.9));
        assertEquals(1, graph.bucket(104));
        assertEquals(60, graph.rate(0, 0), 0);
        assertEquals(30, graph.rate(1, 0), 0);
        assertTrue(Double.isNaN(graph.rate(0, 1)));
        assertEquals(0, graph.rate(0, 2), 0);
    }
    @Test public void malformedSnapshotCannotEnterTheRenderLoop() {
        NBTTagCompound tag = snapshot(); tag.setLong("width", 0);
        assertThrows(IllegalArgumentException.class, () -> new GraphSeries(tag));
        tag.setLong("width", 4); tag.setByteArray("c0", new byte[8]);
        assertThrows(IllegalArgumentException.class, () -> new GraphSeries(tag));
    }
    @Test public void quickRepeatedRepliesRetainTheLeftEdgeWithoutDuplicatingCounts() {
        NBTTagCompound first=snapshot();
        GraphSeries old=new GraphSeries(first);
        NBTTagCompound second=snapshot(); second.setLong("start",104);
        GraphSeries middle=new GraphSeries(second);
        GraphSeries retained=GraphSeries.retain(old,middle,100);
        assertEquals(100,retained.start);
        assertEquals(4,retained.observed.length);
        assertEquals(10,retained.counts[0][0]); // old left edge
        assertEquals(10,retained.counts[0][1]); // latest sample wins at the overlap, not added twice
        NBTTagCompound third=snapshot(); third.setLong("start",108);
        retained=GraphSeries.retain(retained,new GraphSeries(third),100);
        assertEquals(100,retained.start);
        assertEquals(5,retained.observed.length);
        assertEquals(10,retained.counts[0][0]);
        assertEquals(10,retained.counts[0][1]);
        assertEquals(10,retained.counts[0][2]);
        retained=GraphSeries.retain(retained,new GraphSeries(third),104);
        assertEquals(104,retained.start); // old data can be discarded after leaving the screen
    }
    @Test public void retainedHistoryLeavesObservationGapsAndBoundsLongStalls() {
        GraphSeries old=new GraphSeries(snapshot());
        NBTTagCompound tag=snapshot(); tag.setLong("start",120);
        GraphSeries current=new GraphSeries(tag);
        GraphSeries retained=GraphSeries.retain(old,current,100);
        assertEquals(8,retained.observed.length);
        assertTrue(Double.isNaN(retained.rate(0,retained.bucket(116))));
        tag.setLong("start",1_000_000);
        current=new GraphSeries(tag);
        assertSame(current,GraphSeries.retain(old,current,100));
    }
}
