package com.aeinspector.gui;

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
    private final GuiSubscription statistics = new GuiSubscription(), devices = new GuiSubscription();
    private int age;
    private boolean failureReported, closed;

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
        update(statistics, false); update(devices, true);
    }
    private void update(GuiSubscription view, boolean io) {
        InspectorProtocol.Request latest = InspectorProtocol.take((EntityPlayerMP) player, io);
        if (latest != null && latest.window == windowId) view.accept(latest, age);
        if (closed || !view.ready(age)) return;
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null) return;
        InspectorProtocol.Request request = view.request;
        request.window = windowId;
        int sequence = request.sequence;
        SnapshotStream stream = new SnapshotStream(windowId, sequence, ++view.responseBatch,
                packet -> InspectorProtocol.CHANNEL.sendTo(packet, (EntityPlayerMP) player));
        view.work = runtime.queries.work.submit(new InspectorQueryJob(runtime, session.grid(), request, stream,
                () -> !closed && player.openContainer == this && view.active(sequence),
                data -> {
                    view.work = null; view.nextRefresh = age + 20;
                    if (request.selected.length == 0) request.selected = data.getIntArray("selected");
                }, failure -> queryFailed(view, failure)));
    }
    private void queryFailed(GuiSubscription view, Exception failure) {
        view.work = null;
        if (failure instanceof InspectorQueryJob.Changed) { view.nextRefresh = age; return; }
        LogManager.getLogger("AE Inspector").error("Cannot query inspector history", failure);
        reportFailure("aeinspector.read_error"); player.closeScreen();
    }
    @Override public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        closed = true; statistics.close(); devices.close();
        if (player instanceof EntityPlayerMP) InspectorProtocol.forget((EntityPlayerMP) player);
    }
}
