package com.aeinspector.integration;

import static org.junit.Assert.*;
import com.aeinspector.core.ResourceDictionary;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

/** Tests the exact drop format without starting Forge registries or a Minecraft world. */
public class ResourceResolverTest {
    @Test public void dropRepresentationCopiesFullNestedFluidTag() {
        NBTTagCompound fluidTag = new NBTTagCompound();
        NBTTagCompound nested = new NBTTagCompound(); nested.setIntArray("variant", new int[] {1,2,3});
        fluidTag.setTag("nested", nested);
        ItemStack drop = ResourceResolver.fluidRepresentation(new Item(), "water", fluidTag);
        assertEquals(1, drop.stackSize);
        assertTrue(ResourceResolver.canonicalDrop(drop.getTagCompound()));
        assertEquals(fluidTag, drop.getTagCompound().getCompoundTag("FluidTag"));
        nested.setInteger("changed", 7);
        assertFalse(drop.getTagCompound().getCompoundTag("FluidTag").getCompoundTag("nested").hasKey("changed"));
        ResourceDictionary dictionary = new ResourceDictionary();
        int original = dictionary.resolve(ResourceDictionary.FLUID,"water",0,drop.getTagCompound().getCompoundTag("FluidTag"));
        assertNotEquals(original,dictionary.resolve(ResourceDictionary.FLUID,"water",0,fluidTag));
    }
    @Test public void absentEmptyAndExtraTagsRemainDistinct() {
        Item item = new Item();
        NBTTagCompound plain = ResourceResolver.fluidRepresentation(item,"water",null).getTagCompound();
        NBTTagCompound empty = ResourceResolver.fluidRepresentation(item,"water",new NBTTagCompound()).getTagCompound();
        assertTrue(ResourceResolver.canonicalDrop(plain)); assertTrue(ResourceResolver.canonicalDrop(empty));
        assertFalse(plain.hasKey("FluidTag")); assertTrue(empty.hasKey("FluidTag",10));
        assertNotEquals(plain,empty);
        NBTTagCompound extra=(NBTTagCompound)plain.copy(); extra.setBoolean("DisplayOnly",true);
        assertFalse(ResourceResolver.canonicalDrop(extra));
        NBTTagCompound wrongType=(NBTTagCompound)plain.copy(); wrongType.setString("FluidTag","data");
        assertFalse(ResourceResolver.canonicalDrop(wrongType));
        NBTTagCompound wrongName=(NBTTagCompound)plain.copy(); wrongName.setInteger("Fluid",1);
        assertFalse(ResourceResolver.canonicalDrop(wrongName));
    }
}
