package com.aeinspector.integration;

import com.aeinspector.core.ExternalStorageObserver;
import com.aeinspector.core.NetworkRecord;
import com.glodblock.github.common.parts.PartFluidStorageBus;

import appeng.api.networking.IGridNode;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.data.IAEStack;
import appeng.parts.misc.PartStorageBus;

/** Shared integration for item and fluid storage monitors. */
public final class StorageObservation {
    public final ExternalStorageObserver observer = new ExternalStorageObserver(this::record);
    private BaseActionSource owner;

    public boolean bind(BaseActionSource source) {
        if (!(source instanceof MachineSource)) return false;
        Object host = ((MachineSource) source).via;
        if (!(host instanceof PartStorageBus) && !(host instanceof PartFluidStorageBus)) return false;
        owner = source;
        return true;
    }

    public void changes(Iterable<? extends IAEStack<?>> changes) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null) return;
        for (IAEStack<?> stack : changes) {
            observer.difference(runtime.resources.resolve(stack), stack.getStackSize());
        }
        observer.endReport();
    }

    private void record(long resource, long signedAmount) {
        if (signedAmount == 0) return;
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null || owner == null) return;
        IGridNode node = ((MachineSource) owner).via.getActionableNode();
        if (node == null || node.getGrid() == null || !node.isActive()) return;
        NetworkRecord network = runtime.network(node.getGrid());
        network.observe(runtime.world.tick());
        network.add((int) resource, runtime.endpoint(owner), signedAmount > 0, true, Math.abs(signedAmount));
    }
}
