package com.aeinspector.integration;

import java.util.function.ToIntFunction;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.util.IterationCounter;
import com.aeinspector.core.InventoryDeltaTracker;
import com.aeinspector.core.LongCounters;

/** Observes the AE API, not a particular tile or its callback timing/source conventions. */
@SuppressWarnings({"rawtypes", "unchecked"})
final class InventoryMonitorObservation implements IMEMonitorHandlerReceiver {
    private final IMEInventory inventory;
    private final ToIntFunction<IAEStack<?>> resources;
    private final Runnable dirty;
    private final InventoryDeltaTracker delta;
    private boolean connected;
    private long generation;

    InventoryMonitorObservation(IMEInventory<?> inventory, ToIntFunction<IAEStack<?>> resources,
            LongCounters.Consumer sink, Runnable dirty) {
        this.inventory = inventory; this.resources = resources; this.dirty = dirty;
        delta = new InventoryDeltaTracker(sink);
    }
    void connect() {
        if (connected) return;
        connected = true;
        if (inventory instanceof IMEMonitor) ((IMEMonitor) inventory).addListener(this, this);
        dirty.run();
    }
    void close() {
        if (!connected) return;
        connected = false;
        if (inventory instanceof IMEMonitor) ((IMEMonitor) inventory).removeListener(this);
        invalidate();
    }
    void operation(int resource, long signedAmount) {
        if (!connected || signedAmount == 0) return;
        delta.aeChange(resource, signedAmount);
        dirty.run(); // AE withdrawal + external refill can cancel, producing no notification at all.
    }
    void invalidate() { generation++; delta.invalidate(); }
    boolean contains(int resource) { return delta.contains(resource); }
    void sample() {
        if (!connected) return;
        long readingGeneration = generation;
        // Full report, read in one server turn; never retain mutable AE stacks as keys.
        IItemList<?> contents = inventory instanceof IMEMonitor ? ((IMEMonitor) inventory).getStorageList()
                : inventory.getAvailableItems(inventory.getChannel().createList(), IterationCounter.fetchNewId());
        delta.beginSample();
        for (Object value : contents) {
            IAEStack<?> stack = (IAEStack<?>) value;
            if (stack != null && stack.getStackSize() > 0) delta.sample(resources.applyAsInt(stack), stack.getStackSize());
        }
        if (connected && readingGeneration == generation) delta.endSample();
    }
    @Override public boolean isValid(Object token) { return connected && token == this; }
    @Override public void postChange(IBaseMonitor monitor, Iterable change, BaseActionSource source) {
        // Deltas may be delayed, coalesced or mutable. Reconcile absolute quantities with the AE ledger instead.
        if (connected) dirty.run();
    }
    @Override public void onListUpdate() {
        // The AE contract uses this for availability/power events, not item transfers.
        if (connected) { invalidate(); dirty.run(); }
    }
}
