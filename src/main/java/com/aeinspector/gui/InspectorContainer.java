package com.aeinspector.gui;

import java.io.IOException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import org.apache.logging.log4j.LogManager;
import com.aeinspector.integration.FlowRuntime;

/** Read-only wireless session with hidden hotbar slots for vanilla held-item synchronization. */
public final class InspectorContainer extends Container {
    private final EntityPlayer player;
    private final WirelessSession session;
    private InspectorProtocol.Request request = new InspectorProtocol.Request();
    private int age;
    private boolean failureReported;

    public InspectorContainer(EntityPlayer player, int slot) {
        this.player = player;
        // NetHandlerPlayServer requires a slot for the held item after onItemRightClick returns.
        for (int i = 0; i < 9; i++) addSlotToContainer(new Slot(player.inventory, i, -10000, -10000) {
            @Override public boolean isItemValid(ItemStack stack) { return false; }
            @Override public boolean canTakeStack(EntityPlayer player) { return false; }
        });
        session = player.worldObj.isRemote ? null : new WirelessSession(player, slot);
    }
    @Override public boolean canInteractWith(EntityPlayer player) {
        if (player != this.player) return false;
        if (player.worldObj.isRemote || session.valid()) return true;
        reportFailure(session.failureMessage());
        return false;
    }
    private void reportFailure(String key) {
        if (failureReported) return;
        failureReported = true;
        player.addChatMessage(new ChatComponentTranslation(key));
        LogManager.getLogger("AE Inspector").info("Closing inspector window {}: {}", windowId, key);
    }
    @Override public ItemStack transferStackInSlot(EntityPlayer player, int slot) { return null; }
    @Override public ItemStack slotClick(int slot, int button, int mode, EntityPlayer player) { return null; }
    @Override public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (player.worldObj.isRemote) return;
        if (!canInteractWith(player)) { player.closeScreen(); return; }
        age++;
        if (age % 11 == 0 && !session.drain(11)) {
            reportFailure("aeinspector.no_power"); player.closeScreen(); return;
        }
        InspectorProtocol.Request latest = InspectorProtocol.take((EntityPlayerMP) player);
        if (latest != null && latest.window == windowId) request = latest;
        if (age != 1 && age % 20 != 0) return;
        try {
            request.window = windowId;
            InspectorProtocol.CHANNEL.sendTo(new InspectorProtocol.Snapshot(
                    InspectorData.build(FlowRuntime.get(), session.grid(), request)), (EntityPlayerMP) player);
        } catch (IOException e) {
            LogManager.getLogger("AE Inspector").error("Cannot query inspector history", e);
            reportFailure("aeinspector.read_error");
            player.closeScreen();
        }
    }
    @Override public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (player instanceof EntityPlayerMP) InspectorProtocol.forget((EntityPlayerMP) player);
    }
}
