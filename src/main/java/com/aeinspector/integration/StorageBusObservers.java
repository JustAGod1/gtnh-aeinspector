package com.aeinspector.integration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.StorageChannel;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.MEMonitorIInventory;
import appeng.me.storage.MEMonitorPassThrough;
import com.aeinspector.core.NetworkRecord;
import com.glodblock.github.inventory.MEMonitorIFluidHandler;

/** External adapters observed through the AE inventory/monitor contract, independent of the block type. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class StorageBusObservers {
    private static final long BUDGET_NANOS = 1_000_000L;
    private final FlowRuntime runtime;
    private final Map<IActionHost, Binding> owners = new IdentityHashMap<>();
    private final Map<MEInventoryHandler<?>, Group> handlers = new IdentityHashMap<>();
    private final Map<IMEInventory<?>, Group> inventories = new IdentityHashMap<>();
    private final ArrayDeque<Group> dirty = new ArrayDeque<>(), periodic = new ArrayDeque<>();

    StorageBusObservers(FlowRuntime runtime) { this.runtime = runtime; }
    public void attach(IActionHost owner, MEInventoryHandler<?> handler, IMEInventory<?> inventory) {
        Binding existing = owners.get(owner);
        if (existing != null && existing.handler == handler) return;
        forget(owner);
        // These adapters already have precise scan/operation hooks. Network pass-throughs are separate networks.
        if (inventory instanceof MEMonitorIInventory || inventory instanceof MEMonitorIFluidHandler
                || inventory instanceof MEMonitorPassThrough || inventory instanceof appeng.me.cache.NetworkMonitor
                || handler.getExternalNetworkInventory() != null) return;
        if (inventory.getChannel() != StorageChannel.ITEMS && inventory.getChannel() != StorageChannel.FLUIDS) return;
        Group group = inventories.get(inventory);
        boolean created = group == null;
        if (created) { group = new Group(inventory); inventories.put(inventory, group); }
        Binding binding = new Binding(owner, handler, group);
        owners.put(owner, binding); handlers.put(handler, group); group.owners.add(binding);
        if (created) {
            group.nextPoll = runtime.world.tick(); periodic.addLast(group);
            group.observation.connect();
        } else { group.observation.invalidate(); group.markDirty(); }
    }
    public void forget(IActionHost owner) {
        Binding binding = owners.remove(owner);
        if (binding == null) return;
        handlers.remove(binding.handler); Group group = binding.group; group.owners.remove(binding);
        if (group.owners.isEmpty()) {
            group.closed = true; inventories.remove(group.inventory);
            group.observation.close();
        } else { group.observation.invalidate(); group.markDirty(); }
    }
    public void retain(IActionHost owner, MEInventoryHandler<?> current) {
        Binding binding = owners.get(owner);
        if (binding != null && binding.handler != current) forget(owner);
    }
    public void statusChanged(IActionHost owner) {
        Binding binding = owners.get(owner);
        if (binding != null && binding.refreshStatus()) {
            binding.group.observation.invalidate(); binding.group.markDirty();
        }
    }
    public void operation(MEInventoryHandler<?> handler, int resource, long signedAmount) {
        Group group = handlers.get(handler);
        if (group != null) group.observation.operation(resource, signedAmount);
    }
    public boolean observes(MEInventoryHandler<?> handler) { return handlers.containsKey(handler); }
    public boolean contains(MEInventoryHandler<?> handler, int resource) {
        Group group = handlers.get(handler);
        return group != null && group.observation.contains(resource);
    }
    public void invalidate(MEInventoryHandler<?> handler) {
        Group group = handlers.get(handler);
        if (group != null) { group.observation.invalidate(); group.markDirty(); }
    }
    public void tick() {
        long tick = runtime.world.tick(), start = System.nanoTime();
        // Notifications accelerate updates; periodic checks also catch adapters with absent/coalesced callbacks.
        while (!periodic.isEmpty() && periodic.peekFirst().nextPoll <= tick && System.nanoTime() - start < BUDGET_NANOS / 2) {
            Group group = periodic.removeFirst();
            if (group.closed) continue;
            group.markDirty(); group.nextPoll = tick + 20; periodic.addLast(group);
        }
        int remaining = dirty.size(); // Callbacks during reads can requeue a monitor, but never rescan it in this tick.
        while (remaining-- > 0 && !dirty.isEmpty() && System.nanoTime() - start < BUDGET_NANOS) {
            Group group = dirty.removeFirst(); group.queued = false;
            if (!group.closed && tick >= group.retryAt) try { group.sample(); }
            catch (RuntimeException failure) {
                group.observation.invalidate(); group.retryAt = tick + 100;
                if (tick >= group.logAfter) {
                    group.logAfter = tick + 1200;
                    LogManager.getLogger("AE Inspector").warn("Cannot read Storage Bus adapter {}; rebasing after retry",
                            group.inventory.getClass().getName(), failure);
                }
            }
        }
    }
    public void close() {
        for (Group group : inventories.values()) {
            group.closed = true;
            group.observation.close();
        }
        owners.clear(); handlers.clear(); inventories.clear(); dirty.clear(); periodic.clear();
    }
    private static final class Binding {
        final IActionHost owner;
        final MEInventoryHandler<?> handler;
        final Group group;
        IGrid grid;
        Binding(IActionHost owner, MEInventoryHandler<?> handler, Group group) {
            this.owner = owner; this.handler = handler; this.group = group; refreshStatus();
        }
        boolean refreshStatus() {
            IGridNode node = owner.getActionableNode();
            IGrid next = node != null && node.isActive() ? node.getGrid() : null;
            boolean changed = next != grid; grid = next; return changed;
        }
    }
    private final class Group {
        final IMEInventory inventory;
        final ArrayList<Binding> owners = new ArrayList<>();
        final IdentityHashMap<IGrid, Binding> active = new IdentityHashMap<>();
        final InventoryMonitorObservation observation;
        long nextPoll, retryAt, logAfter;
        boolean queued, closed;
        Group(IMEInventory inventory) {
            this.inventory = inventory;
            observation = new InventoryMonitorObservation(inventory, runtime.resources::resolve, this::record, this::markDirty);
        }
        void markDirty() { if (!queued && !closed) { queued = true; dirty.addLast(this); } }
        void sample() {
            active.clear();
            for (Binding binding : owners) {
                if (binding.refreshStatus()) observation.invalidate();
                if (binding.grid != null) active.putIfAbsent(binding.grid, binding);
            }
            if (active.isEmpty()) { observation.invalidate(); return; }
            observation.sample();
        }
        void record(long resource, long amount) {
            for (Map.Entry<IGrid, Binding> entry : active.entrySet()) {
                NetworkRecord network = runtime.network(entry.getKey()); network.observe(runtime.world.tick());
                network.add((int) resource, runtime.endpoint(entry.getValue().owner), amount > 0, true, Math.abs(amount));
            }
        }
    }
}
