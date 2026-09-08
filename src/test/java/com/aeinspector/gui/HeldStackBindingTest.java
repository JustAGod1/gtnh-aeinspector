package com.aeinspector.gui;

import static org.junit.Assert.*;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

public class HeldStackBindingTest {
    private ItemStack terminal() {
        ItemStack stack = new ItemStack(new Item() {});
        stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound().setString("encryptionKey", "123");
        stack.getTagCompound().setDouble("internalCurrentPower", 1000);
        return stack;
    }

    @Test public void vanillaRightClickCopyKeepsSessionAndChargesInventoryStack() {
        InventoryPlayer inventory = new InventoryPlayer(null);
        ItemStack bound = terminal();
        inventory.setInventorySlotContents(0, bound.copy());
        assertTrue(HeldStackBinding.restore(inventory, 0, bound));
        assertSame(bound, inventory.getCurrentItem());
        bound.getTagCompound().setDouble("internalCurrentPower", 900);
        assertEquals(900, inventory.getCurrentItem().getTagCompound().getDouble("internalCurrentPower"), 0);
    }

    @Test public void changedLinkChargeItemOrSelectedSlotCannotRestoreStaleStack() {
        InventoryPlayer inventory = new InventoryPlayer(null);
        ItemStack bound = terminal();
        for (String field : new String[] {"encryptionKey", "internalCurrentPower"}) {
            ItemStack changed = bound.copy();
            changed.getTagCompound().setString(field, "changed");
            inventory.setInventorySlotContents(0, changed);
            assertFalse(HeldStackBinding.restore(inventory, 0, bound));
            assertSame(changed, inventory.getCurrentItem());
        }
        inventory.setInventorySlotContents(0, terminal());
        assertFalse(HeldStackBinding.restore(inventory, 0, bound));
        inventory.setInventorySlotContents(0, null);
        assertFalse(HeldStackBinding.restore(inventory, 0, bound));
        inventory.setInventorySlotContents(0, bound);
        inventory.currentItem = 1;
        assertFalse(HeldStackBinding.restore(inventory, 0, bound));
    }
}
