package com.aeinspector.gui;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

/** Vanilla copies the held stack after right-click, after the wireless GUI has captured it. */
final class HeldStackBinding {
    private HeldStackBinding() {}

    static boolean restore(InventoryPlayer inventory, int slot, ItemStack bound) {
        if (inventory.currentItem != slot) return false;
        ItemStack current = inventory.getStackInSlot(slot);
        if (current == null || bound == null || current.stackSize <= 0) return false;
        if (current != bound) {
            // Preserve all tags, including charge and security binding. Never overwrite a changed item.
            if (!ItemStack.areItemStacksEqual(current, bound)) return false;
            inventory.setInventorySlotContents(slot, bound);
        }
        return true;
    }
}
