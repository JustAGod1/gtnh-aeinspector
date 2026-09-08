package com.aeinspector.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

public final class ClientGuiHandler extends CommonGuiHandler {
    @Override public Object getClientGuiElement(int id, EntityPlayer player, World world, int slot, int y, int z) {
        return id == 0 ? new InspectorScreen(new InspectorContainer(player, slot)) : null;
    }
}
