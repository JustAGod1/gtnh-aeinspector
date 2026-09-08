package com.aeinspector.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class ResourceDictionaryTest {
    @Test
    public void nanTagsHaveStableIdentityAndPreserveFloatingPointBits() throws Exception {
        ResourceDictionary d = new ResourceDictionary();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setFloat("value", Float.intBitsToFloat(0x7fc00001));
        int id = d.resolve((byte) 0, "test:item", 0, tag);
        assertEquals(id, d.resolve((byte) 0, "test:item", 0, (NBTTagCompound) tag.copy()));
        tag.setFloat("value", Float.intBitsToFloat(0x7fc00002));
        assertNotEquals(id, d.resolve((byte) 0, "test:item", 0, tag));
        tag.setDouble("zero", 0.0);
        int zero = d.resolve((byte) 0, "test:item", 0, tag);
        tag.setDouble("zero", -0.0);
        assertNotEquals(zero, d.resolve((byte) 0, "test:item", 0, tag));
        NBTTagList emptyTypedList = new NBTTagList();
        emptyTypedList.appendTag(new NBTTagInt(7));
        emptyTypedList.removeTag(0);
        tag.setTag("empty", emptyTypedList);
        int last = d.resolve((byte) 0, "test:item", 0, tag);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        d.write(new DataOutputStream(bytes));
        ResourceDictionary loaded = ResourceDictionary.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(last, loaded.resolve((byte) 0, "test:item", 0, tag));
        for (int i = 0; i < d.size(); i++) {
            assertEquals(i, loaded.resolve((byte) 0, "test:item", 0, d.get(i).copyTag()));
        }
    }
    @Test
    public void exactIdentityAndMutation() {
        ResourceDictionary d = new ResourceDictionary();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("value", 1);
        int id = d.resolve((byte) 0, "test:item", 7, tag);
        assertEquals(id, d.resolve((byte) 0, "test:item", 7, (NBTTagCompound) tag.copy()));
        assertNotEquals(id, d.resolve((byte) 0, "test:item", 8, tag));
        tag.setInteger("value", 2);
        assertNotEquals(id, d.resolve((byte) 0, "test:item", 7, tag));
        assertEquals(1, d.get(id).copyTag().getInteger("value"));
        tag.setByte("value", (byte) 1);
        assertNotEquals(id, d.resolve((byte) 0, "test:item", 7, tag));
        assertNotEquals(d.resolve((byte) 0, "test:item", 0, null),
                d.resolve((byte) 0, "test:item", 0, new NBTTagCompound()));
    }

    @Test
    public void orderingAndHashCollisions() {
        ResourceDictionary d = new ResourceDictionary();
        NBTTagCompound a = new NBTTagCompound();
        a.setInteger("one", 1);
        a.setByteArray("two", new byte[] {1, 2});
        NBTTagCompound b = new NBTTagCompound();
        b.setByteArray("two", new byte[] {1, 2});
        b.setInteger("one", 1);
        int first = d.resolve((byte) 0, "test:item", 0, a);
        assertEquals(first, d.resolve((byte) 0, "test:item", 0, b));
        b.setByteArray("two", new byte[] {2, 1});
        assertNotEquals(first, d.resolve((byte) 0, "test:item", 0, b));
        NBTTagList list = new NBTTagList();
        list.appendTag(new NBTTagInt(1));
        list.appendTag(new NBTTagInt(2));
        a.setTag("list", list);
        int ordered = d.resolve((byte) 0, "test:item", 0, a);
        NBTTagList reversed = new NBTTagList();
        reversed.appendTag(new NBTTagInt(2));
        reversed.appendTag(new NBTTagInt(1));
        a.setTag("list", reversed);
        assertNotEquals(ordered, d.resolve((byte) 0, "test:item", 0, a));
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertNotEquals(d.resolve((byte) 0, "Aa", 0, null), d.resolve((byte) 0, "BB", 0, null));
    }

    @Test
    public void dictionaryRoundTrip() throws Exception {
        ResourceDictionary d = new ResourceDictionary();
        NBTTagCompound inner = new NBTTagCompound();
        inner.setIntArray("array", new int[] {Integer.MIN_VALUE, 42});
        NBTTagCompound outer = new NBTTagCompound();
        outer.setTag("nested", inner);
        int id = d.resolve((byte) 1, "water", 0, outer);
        d.resolve((byte) 0, "test:item", 2, null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        d.write(new DataOutputStream(bytes));
        ResourceDictionary loaded = ResourceDictionary.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(2, loaded.size());
        assertEquals(id, loaded.resolve((byte) 1, "water", 0, outer));
        assertEquals(outer, loaded.get(id).copyTag());
    }
}
