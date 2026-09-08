package com.aeinspector.item;

import static org.junit.Assert.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

public class InspectorRecipeTest {
    @Test public void upgradeRetainsIndependentCompleteTerminalTag() {
        Item terminalItem = new Item() {}, processorItem = new Item() {}, resultItem = new Item() {};
        ItemStack terminal = new ItemStack(terminalItem);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setDouble("internalCurrentPower", 123456.5);
        tag.setString("encryptionKey", "987654321");
        NBTTagCompound custom = new NBTTagCompound(); custom.setIntArray("data", new int[] {7, 9});
        tag.setTag("custom", custom); terminal.setTagCompound(tag);
        InspectorRecipe recipe = new InspectorRecipe(new ItemStack(resultItem), new ItemStack(terminalItem), new ItemStack(processorItem));
        InventoryCrafting inventory = new InventoryCrafting(new Container() {
            @Override public boolean canInteractWith(EntityPlayer player) { return true; }
        }, 3, 3);
        inventory.setInventorySlotContents(8, terminal);
        inventory.setInventorySlotContents(0, new ItemStack(processorItem));
        assertTrue(recipe.matches(inventory, null));
        ItemStack result = recipe.getCraftingResult(inventory);
        assertSame(resultItem, result.getItem());
        assertEquals(tag, result.getTagCompound());
        assertNotSame(tag, result.getTagCompound());
        custom.setInteger("changed", 1);
        assertFalse(result.getTagCompound().getCompoundTag("custom").hasKey("changed"));
        inventory.setInventorySlotContents(1, new ItemStack(processorItem));
        assertFalse(recipe.matches(inventory, null));
    }
}
