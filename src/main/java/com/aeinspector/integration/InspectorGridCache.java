package com.aeinspector.integration;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import com.aeinspector.core.NetworkRecord;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridCache;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridStorage;
import appeng.api.networking.security.IActionHost;

/** One statistics segment per live AE grid; no retrospective redistribution on topology changes. */
public final class InspectorGridCache implements IGridCache {
    private static final String KEY = "aeinspector.segment";
    private static final String SPLIT = "aeinspector.splitParent";
    public final IGrid grid;
    private NetworkRecord record;
    private final Set<IGridNode> nodes = Collections.newSetFromMap(new IdentityHashMap<IGridNode, Boolean>());

    public InspectorGridCache(IGrid grid) { this.grid = grid; }

    public NetworkRecord record() {
        if (record == null) record = FlowRuntime.get().world.createNetwork();
        return record;
    }

    @Override
    public void onUpdateTick() {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime != null) record().observe(runtime.world.tick());
    }

    @Override
    public void addNode(IGridNode node, IGridHost machine) { nodes.add(node); }

    public Set<IGridNode> nodes() { return Collections.unmodifiableSet(nodes); }

    @Override
    public void removeNode(IGridNode node, IGridHost machine) {
        nodes.remove(node);
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime != null && machine instanceof IActionHost) runtime.forget((IActionHost) machine);
    }

    @Override
    public void onSplit(IGridStorage destination) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null) return;
        int parent = record().id;
        destination.dataObject().setInteger(SPLIT, parent);
        record = runtime.world.createNetwork(parent);
    }

    @Override
    public void onJoin(IGridStorage source) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null) return;
        NBTTagCompound data = source.dataObject();
        if (data.hasKey(SPLIT)) {
            int parent = data.getInteger(SPLIT);
            record = record == null ? runtime.world.createNetwork(parent)
                    : runtime.world.createNetwork(record.id, parent);
        } else if (data.hasKey(KEY)) {
            int previous = data.getInteger(KEY);
            if (previous < 0 || previous >= runtime.world.networkCount()) {
                throw new IllegalStateException("AE network references missing inspector metadata " + previous);
            }
            record = record == null ? runtime.world.network(previous)
                    : record.id == previous ? record : runtime.world.createNetwork(record.id, previous);
        }
    }

    @Override
    public void populateGridStorage(IGridStorage destination) {
        if (record != null) destination.dataObject().setInteger(KEY, record.id);
    }
}
