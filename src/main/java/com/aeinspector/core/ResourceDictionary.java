package com.aeinspector.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;

/** Server-thread owned. Lookup never retains the caller's mutable NBT. */
public final class ResourceDictionary {

    public static final byte ITEM = 0;
    public static final byte FLUID = 1;
    private Entry[] buckets = new Entry[256];
    private final ArrayList<Entry> entries = new ArrayList<>();

    public int resolve(byte kind, String name, int metadata, NBTTagCompound tag) {
        Objects.requireNonNull(name, "registry name");
        if (kind != ITEM && kind != FLUID) throw new IllegalArgumentException("resource kind");
        int hash = hash(kind, name, metadata, tag);
        int slot = hash & (buckets.length - 1);
        for (Entry e = buckets[slot]; e != null; e = e.next) {
            if (e.hash == hash && e.kind == kind && e.metadata == metadata && e.name.equals(name)
                    && NbtIdentity.equal(e.tag, tag)) return e.id;
        }
        Entry e = new Entry(entries.size(), kind, name, metadata,
                tag == null ? null : (NBTTagCompound) tag.copy(), hash, buckets[slot]);
        buckets[slot] = e;
        entries.add(e);
        if (entries.size() * 4 > buckets.length * 3) grow();
        return e.id;
    }

    private static int hash(byte kind, String name, int metadata, NBTTagCompound tag) {
        int h = 31 * (31 * (31 * kind + name.hashCode()) + metadata) + NbtIdentity.hash(tag);
        return h ^ (h >>> 16);
    }

    private void grow() {
        buckets = new Entry[buckets.length * 2];
        for (Entry e : entries) {
            int slot = e.hash & (buckets.length - 1);
            e.next = buckets[slot];
            buckets[slot] = e;
        }
    }

    public Entry get(int id) { return entries.get(id); }

    public int size() { return entries.size(); }

    public void write(DataOutput out) throws IOException {
        out.writeInt(1);
        out.writeInt(entries.size());
        for (Entry e : entries) {
            out.writeByte(e.kind);
            out.writeUTF(e.name);
            out.writeInt(e.metadata);
            out.writeBoolean(e.tag != null);
            if (e.tag != null) NbtIdentity.writeCompound(e.tag, out);
        }
    }

    public static ResourceDictionary read(DataInput in) throws IOException {
        if (in.readInt() != 1) throw new IOException("Unsupported resource dictionary version");
        int count = in.readInt();
        if (count < 0 || count > 10_000_000) throw new IOException("Invalid resource count");
        ResourceDictionary result = new ResourceDictionary();
        for (int i = 0; i < count; i++) {
            byte kind = in.readByte();
            String name = in.readUTF();
            int metadata = in.readInt();
            NBTTagCompound tag = in.readBoolean()
                    ? CompressedStreamTools.func_152456_a(in, new NBTSizeTracker(64L * 1024 * 1024)) : null;
            if (kind != ITEM && kind != FLUID) throw new IOException("Invalid resource kind");
            if (result.resolve(kind, name, metadata, tag) != i) throw new IOException("Duplicate dictionary entry");
        }
        return result;
    }

    public static final class Entry {
        public final int id;
        public final byte kind;
        public final String name;
        public final int metadata;
        private final NBTTagCompound tag;
        private final int hash;
        private Entry next;

        private Entry(int id, byte kind, String name, int metadata, NBTTagCompound tag, int hash, Entry next) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.metadata = metadata;
            this.tag = tag;
            this.hash = hash;
            this.next = next;
        }

        public NBTTagCompound copyTag() { return tag == null ? null : (NBTTagCompound) tag.copy(); }
    }
}
