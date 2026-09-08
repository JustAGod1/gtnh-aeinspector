package com.aeinspector.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import com.aeinspector.item.ItemInspector;
import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.features.ILocatable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.core.AEConfig;
import appeng.helpers.WirelessTerminalGuiObject;
import net.minecraftforge.common.util.ForgeDirection;

/** Same view-only access as AE's wireless terminal: a live encoded station, range and battery. */
public final class WirelessSession {
    private final EntityPlayer player;
    private final ItemStack stack;
    private final int slot;
    private final String key;
    private final ItemInspector item;
    private final WirelessTerminalGuiObject wireless;
    private String failure = "aeinspector.unavailable";

    public WirelessSession(EntityPlayer player, int slot) {
        this.player = player;
        this.slot = slot;
        stack = player.inventory.getStackInSlot(slot);
        item = (ItemInspector) stack.getItem();
        key = item.getEncryptionKey(stack);
        wireless = new WirelessTerminalGuiObject(item, stack, player, player.worldObj, slot, 0, 0);
    }

    public IGrid grid() { return wireless.getGrid(); }
    public String failureMessage() { return failure; }
    private boolean fail(String reason) { failure = "aeinspector." + reason; return false; }

    public boolean valid() {
        if (player.isDead) return fail("session_ended");
        if (!HeldStackBinding.restore(player.inventory, slot, stack)) return fail("held_item_changed");
        if (!key.equals(item.getEncryptionKey(stack))) return fail("link_changed");
        if (!item.hasPower(player, 0.5, stack)) return fail("no_power");
        if (grid() == null) return fail("unavailable");
        // A removed/rebound security station must not leave a stale session with access to its former grid.
        try {
            ILocatable station = AEApi.instance().registries().locatable().getLocatableBy(Long.parseLong(key));
            if (!(station instanceof IGridHost)) return fail("unavailable");
            IGridNode node = ((IGridHost) station).getGridNode(ForgeDirection.UNKNOWN);
            if (node == null || node.getGrid() != grid()) return fail("unavailable");
        } catch (NumberFormatException e) { return fail("unlinked"); }
        return wireless.rangeCheck() || fail("out_of_range");
    }

    public boolean drain(int ticks) {
        double amount = AEConfig.instance.wireless_getDrainRate(wireless.getRange()) * ticks;
        return wireless.extractAEPower(amount, Actionable.MODULATE, PowerMultiplier.CONFIG) >= amount;
    }
}
