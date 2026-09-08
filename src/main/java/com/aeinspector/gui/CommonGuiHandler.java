package com.aeinspector.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import com.aeinspector.item.ItemInspector;
import cpw.mods.fml.common.network.IGuiHandler;

public class CommonGuiHandler implements IGuiHandler {
    @Override public Object getServerGuiElement(int id, EntityPlayer player, World world, int slot, int y, int z) {
        if (id != 0 || slot < 0 || slot >= 9 || player.inventory.currentItem != slot) return null;
        ItemStack stack = player.inventory.getStackInSlot(slot);
        if (stack == null || !(stack.getItem() instanceof ItemInspector)) return null;
        InspectorContainer container = new InspectorContainer(player, slot);
        if (container.canInteractWith(player)) return container;
        return null;
    }
    @Override public Object getClientGuiElement(int id, EntityPlayer player, World world, int slot, int y, int z) { return null; }
}
