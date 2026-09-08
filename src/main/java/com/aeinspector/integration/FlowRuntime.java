package com.aeinspector.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.IdentityHashMap;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.DimensionManager;

import com.aeinspector.core.NetworkRecord;
import com.aeinspector.core.TransferTracker;
import com.aeinspector.core.BusTransferScope;
import com.aeinspector.storage.WorldStatistics;
import com.aeinspector.storage.InspectorSavedData;
import net.minecraft.world.WorldServer;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.data.IAEStack;
import appeng.api.util.DimensionalCoord;
import appeng.parts.AEBasePart;
import cpw.mods.fml.common.FMLCommonHandler;

/** Minecraft-facing adapter. Every caller runs on the logical server thread. */
public final class FlowRuntime {
    private static FlowRuntime active;
    public final WorldStatistics world;
    private final InspectorSavedData savedData;
    public final ResourceResolver resources;
    public final TransferTracker transfers;
    public final BusTransferScope busTransfers;
    public final com.aeinspector.gui.InspectorQueries queries;
    public final StorageBusObservers storageBuses;
    private final IdentityHashMap<IActionHost, Integer> endpoints = new IdentityHashMap<>();

    private FlowRuntime(WorldServer overworld) {
        InspectorSavedData saved = (InspectorSavedData) overworld.mapStorage.loadData(InspectorSavedData.class, InspectorSavedData.NAME);
        if (saved == null) {
            saved = new InspectorSavedData(InspectorSavedData.NAME);
            try { saved.importLegacy(overworld.getSaveHandler().getWorldDirectory().toPath().resolve("aeinspector")); }
            catch (IOException e) { throw new UncheckedIOException("Cannot import existing Inspector history into NBT", e); }
            overworld.mapStorage.setData(InspectorSavedData.NAME, saved);
        }
        savedData = saved; world = saved.statistics(); saved.markDirty();
        resources = new ResourceResolver(world.resources);
        busTransfers = new BusTransferScope((network, resource, device, incoming, amount) ->
                world.network((int) network).add(resource, device, incoming, false, amount));
        transfers = new TransferTracker(busTransfers);
        queries = new com.aeinspector.gui.InspectorQueries(this);
        storageBuses = new StorageBusObservers(this);
    }

    public static FlowRuntime get() {
        if (!FMLCommonHandler.instance().getEffectiveSide().isServer()) return null;
        if (active == null) {
            WorldServer overworld = DimensionManager.getWorld(0);
            if (overworld == null) return null;
            active = new FlowRuntime(overworld);
        }
        return active;
    }

    public static void reset() {
        if (active != null) close();
    }

    public static void checkpoint() {
        if (active == null) return;
        active.savedData.markDirty();
    }

    public static void close() {
        if (active == null) return;
        // The world's MapStorage owns saving and lifetime; don't write after its save handler has closed.
        active.savedData.markDirty(); active.queries.close(); active.storageBuses.close(); active = null;
    }

    public void endTick() {
        try { storageBuses.tick(); world.endTick(); savedData.markDirty(); queries.tick(); }
        catch (IOException e) { throw new UncheckedIOException("Cannot record AE Inspector tick", e); }
    }

    public int endpoint(BaseActionSource source) {
        return source instanceof MachineSource ? endpoint(((MachineSource) source).via) : 0;
    }

    public int endpoint(IActionHost host) {
        if (host == null) return 0;
        Integer known = endpoints.get(host);
        if (known != null) return known;
        IGridNode node = host.getActionableNode();
        if (node == null) return 0;
        // worldAccessible describes external cable adjacency, NOT whether a part has world coordinates.
        // AEBasePart sets it false for every part except cables, including import/export buses.
        DimensionalCoord c = host instanceof AEBasePart ? ((AEBasePart) host).getLocation()
                : node.getGridBlock().getLocation();
        if (c == null) return 0;
        int side = host instanceof AEBasePart ? ((AEBasePart) host).getSide().ordinal() : -1;
        ItemStack representation = node.getGridBlock().getMachineRepresentation();
        String type = host.getClass().getName();
        String name = representation == null ? type : representation.getDisplayName();
        int id = world.devices.resolve(c.getDimension(), c.x, c.y, c.z, side, type, name);
        endpoints.put(host, id);
        return id;
    }

    public void forget(IActionHost host) { endpoints.remove(host); storageBuses.forget(host); }

    public NetworkRecord network(IGrid grid) {
        InspectorGridCache cache = grid.getCache(InspectorGridCache.class);
        return cache.record();
    }

    public int begin(IGrid grid, IAEStack<?> stack, boolean incoming, boolean modulate, BaseActionSource source) {
        NetworkRecord record = network(grid);
        record.observe(world.tick());
        return transfers.begin(record.id, resources.resolve(stack), endpoint(source), stack.getStackSize(), incoming,
                modulate, source != null && source.isPlayer());
    }
}
