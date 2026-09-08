package com.aeinspector.gui;

import net.minecraft.nbt.NBTTagList;

/** Full accepted table, with a cheap local viewport. No transport or server query on scrolling. */
final class ClientRowList {
    private NBTTagList all = new NBTTagList(), page = new NBTTagList();
    private int pageOffset = -1, pageRows = -1;
    int count() { return all.tagCount(); }
    int replace(NBTTagList next, int offset, int visibleRows) {
        // Keep the top visible resource/device when live sorting moves rows above a scrolled viewport.
        int anchor = offset > 0 && offset < count() ? all.getCompoundTagAt(offset).getInteger("id") : -1;
        all = next; pageOffset = -1;
        if (anchor >= 0) for (int i = 0; i < count(); i++) {
            if (all.getCompoundTagAt(i).getInteger("id") == anchor) { offset = i; break; }
        }
        return ScrollWindow.clamp(offset, count(), visibleRows);
    }
    NBTTagList page(int offset, int rows) {
        offset = ScrollWindow.clamp(offset, count(), rows);
        if (offset != pageOffset || rows != pageRows) {
            page = new NBTTagList(); pageOffset = offset; pageRows = rows;
            for (int i = offset; i < Math.min(count(), offset + rows); i++) page.appendTag(all.getCompoundTagAt(i));
        }
        return page;
    }
}
