package com.aeinspector.gui;

import static org.junit.Assert.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.Test;

public class LocalTableTest {
    @Test public void anEntireListCanBeScrolledWithoutAnotherServerResponse() {
        ClientRowList table = new ClientRowList();
        table.replace(rows(0, 5420), 0, 5);
        assertEquals(5420, table.count());
        for (int offset : new int[] {0, 100, 5415, 12, 2800}) {
            NBTTagList page = table.page(offset, 5);
            assertEquals(5, page.tagCount());
            for (int i = 0; i < 5; i++) assertEquals(offset + i, page.getCompoundTagAt(i).getInteger("id"));
            assertSame(page, table.page(offset, 5));
        }
        assertEquals(5415, table.page(9999, 5).getCompoundTagAt(0).getInteger("id"));
        assertEquals(5420, table.count());
    }
    @Test public void liveReorderingKeepsTheScrolledResourceAndFilteringClampsTheTail() {
        ClientRowList table = new ClientRowList();
        table.replace(rows(0, 100), 0, 5);
        int offset = table.replace(rows(10, 110), 40, 5);
        assertEquals(30, offset);
        assertEquals(40, table.page(offset, 5).getCompoundTagAt(0).getInteger("id"));
        assertEquals(0, table.replace(rows(10, 12), offset, 5));
        assertEquals(2, table.page(0, 5).tagCount());
        assertEquals(0, table.replace(new NBTTagList(), 10, 5));
        assertEquals(0, table.page(0, 5).tagCount());
    }
    @Test public void firstPageStaysAtTopWhenRanksChangeAndResizeUsesTheCachedRows() {
        ClientRowList table = new ClientRowList();
        table.replace(rows(0, 100), 0, 5);
        assertEquals(0, table.replace(rows(10, 110), 0, 5));
        assertEquals(10, table.page(0, 5).getCompoundTagAt(0).getInteger("id"));
        assertEquals(10, table.page(30, 10).tagCount());
        assertEquals(40, table.page(30, 10).getCompoundTagAt(0).getInteger("id"));
    }
    @Test public void toolbarAndWiderScrollbarFitSupportedWidths() {
        for (int width = 300; width <= 900; width++) {
            InspectorLayout.Controls c = new InspectorLayout.Controls(width);
            assertTrue(c.searchWidth >= 50);
            assertTrue(c.sortLeft > 12 + c.searchWidth);
            assertTrue(c.filterLeft > c.sortLeft + c.sortWidth);
            assertTrue(c.scaleLeft > c.filterLeft + c.filterWidth);
            assertEquals(width - 12, c.scaleLeft + c.scaleWidth);
            for (boolean devices : new boolean[] {false, true}) {
                int in = InspectorLayout.incomingColumn(width, devices), out = InspectorLayout.outgoingColumn(width, devices);
                assertTrue(in > 100); assertTrue(out - in >= 60);
                assertTrue(width - InspectorLayout.SCROLL_MARGIN - InspectorLayout.SCROLL_WIDTH - 5 - (devices ? 7 : 34) - out >= 54);
            }
        }
    }
    private static NBTTagList rows(int from, int to) {
        NBTTagList rows = new NBTTagList();
        for (int id = from; id < to; id++) {
            NBTTagCompound row = new NBTTagCompound(); row.setInteger("id", id); rows.appendTag(row);
        }
        return rows;
    }
}
