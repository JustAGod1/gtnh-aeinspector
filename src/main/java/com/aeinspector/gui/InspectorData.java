package com.aeinspector.gui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import com.aeinspector.core.DeviceDictionary;
import com.aeinspector.core.NetworkRecord;
import com.aeinspector.core.ResourceDictionary;
import com.aeinspector.integration.FlowRuntime;
import com.aeinspector.integration.DeviceCatalog;
import com.aeinspector.integration.InspectorGridCache;
import com.aeinspector.storage.HistoryQuery;
import com.glodblock.github.common.item.ItemFluidDrop;
import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;

/** Builds bounded display pages; exact keys remain in the server dictionary. */
public final class InspectorData {
    private InspectorData() {}

    public static NBTTagCompound build(FlowRuntime runtime, IGrid grid, InspectorProtocol.Request request) throws IOException {
        NetworkRecord current = runtime.network(grid);
        HistoryQuery history = new HistoryQuery(runtime.world);
        List<NetworkRecord> lineage = history.lineage(current.id);
        BitSet visible = new BitSet();
        for (NetworkRecord record : lineage) {
            for (int i = 0; i < record.pairCount(); i++) visible.set((int) (record.pairKey(i) >>> 32));
        }
        BitSet historical = (BitSet) visible.clone();
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        for (IAEItemStack stack : storage.getItemInventory().getStorageList()) visible.set(runtime.resources.resolve(stack));
        for (IAEFluidStack stack : storage.getFluidInventory().getStorageList()) visible.set(runtime.resources.resolve(stack));
        DeviceCatalog catalog = new DeviceCatalog(runtime, grid.getCache(InspectorGridCache.class), visible);
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("window", request.window);
        result.setInteger("network", current.id);
        result.setInteger("level", request.level);
        result.setLong("tick", runtime.world.tick());
        List<Integer> matches = new ArrayList<>();
        String search = request.search.toLowerCase(Locale.ROOT);
        for (int id = visible.nextSetBit(0); id >= 0; id = visible.nextSetBit(id + 1)) {
            ResourceDictionary.Entry entry = runtime.world.resources.get(id);
            if (search.isEmpty() || (name(entry) + " " + entry.name + " #" + id + " " + entry.metadata)
                    .toLowerCase(Locale.ROOT).contains(search)) matches.add(id);
        }
        Map<Integer, HistoryQuery.Result> summaries = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        for (int id : matches) {
            names.put(id, name(runtime.world.resources.get(id)));
            if (historical.get(id)) summaries.put(id, history.query(current.id, id, -1, request.level, runtime.world.tick()));
        }
        matches.sort((a, b) -> {
            if (request.sort < 2) {
                HistoryQuery.Result left = summaries.get(a), right = summaries.get(b);
                long l = left == null ? 0 : left.windowCount(request.sort == 0);
                long r = right == null ? 0 : right.windowCount(request.sort == 0);
                int countOrder = Long.compare(r, l);
                if (countOrder != 0) return countOrder;
            }
            int nameOrder = names.get(a).compareToIgnoreCase(names.get(b));
            return nameOrder != 0 ? nameOrder : Integer.compare(a, b);
        });
        int page = Math.min(request.resourcePage, Math.max(0, (matches.size() - 1) / InspectorProtocol.PAGE_SIZE));
        result.setInteger("resourcePage", page);
        result.setInteger("resourceCount", matches.size());
        NBTTagList resources = new NBTTagList();
        for (int i = page * InspectorProtocol.PAGE_SIZE; i < Math.min(matches.size(), (page + 1) * InspectorProtocol.PAGE_SIZE); i++) {
            int id = matches.get(i);
            NBTTagCompound row = resource(runtime.world.resources.get(id));
            HistoryQuery.Result summary = summaries.get(id);
            row.setLong("in", summary == null ? 0 : summary.windowCount(true));
            row.setLong("out", summary == null ? 0 : summary.windowCount(false));
            row.setDouble("inRate", summary == null ? 0 : summary.rate(true));
            row.setDouble("outRate", summary == null ? 0 : summary.rate(false));
            resources.appendTag(row);
        }
        result.setTag("resources", resources);
        NBTTagList graphs = new NBTTagList();
        if (request.selected.length == 0 && !matches.isEmpty()) request.selected = new int[] {matches.get(0)};
        result.setIntArray("selected", request.selected);
        int first = -1;
        for (int id : request.selected) {
            if (!visible.get(id)) continue;
            if (first < 0) first = id;
            NBTTagCompound graph = resource(runtime.world.resources.get(id));
            HistoryQuery.Result data = summaries.get(id);
            if (data == null) data = history.query(current.id, id, -1, request.level, runtime.world.tick());
            graph.setLong("start", data.start); graph.setLong("width", data.width);
            graph.setByteArray("observed", longs(data.observedTicks));
            graph.setByteArray("totals", longs(data.totals));
            for (int channel = 0; channel < 4; channel++) graph.setByteArray("c" + channel, longs(data.counts[channel]));
            graphs.appendTag(graph);
        }
        result.setTag("graphs", graphs);
        if (first >= 0) result.setTag("deviceResource", resource(runtime.world.resources.get(first)));
        BitSet devices = new BitSet();
        BitSet configuredDevices = first < 0 ? new BitSet() : catalog.matching(first);
        devices.or(configuredDevices);
        if (first >= 0) {
            for (NetworkRecord record : lineage) {
                for (int i = 0; i < record.pairCount(); i++) {
                    long pair = record.pairKey(i);
                    if ((int) (pair >>> 32) == first && (int) pair >= 0) devices.set((int) pair);
                }
            }
        }
        int devicePage = Math.min(request.devicePage, Math.max(0, (devices.cardinality() - 1) / InspectorProtocol.PAGE_SIZE));
        result.setInteger("devicePage", devicePage);
        result.setInteger("deviceCount", devices.cardinality());
        NBTTagList deviceList = new NBTTagList();
        int index = 0;
        for (int id = devices.nextSetBit(0); id >= 0; id = devices.nextSetBit(id + 1), index++) {
            if (index < devicePage * InspectorProtocol.PAGE_SIZE) continue;
            if (index >= (devicePage + 1) * InspectorProtocol.PAGE_SIZE) break;
            DeviceDictionary.Device device = runtime.world.devices.get(id);
            HistoryQuery.Result data = history.query(current.id, first, id, request.level, runtime.world.tick());
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("id", id); row.setInteger("dim", device.dimension);
            row.setInteger("x", device.x); row.setInteger("y", device.y); row.setInteger("z", device.z);
            row.setInteger("side", device.side); row.setString("name", device.name);
            row.setBoolean("configured", configuredDevices.get(id));
            row.setBoolean("active", catalog.active(id));
            row.setLong("in", data.windowCount(true)); row.setLong("out", data.windowCount(false));
            long[] windowCounts = new long[4];
            for (int channel = 0; channel < 4; channel++) {
                for (long count : data.counts[channel]) windowCounts[channel] = Math.addExact(windowCounts[channel], count);
            }
            row.setByteArray("windowCounts", longs(windowCounts));
            row.setDouble("inRate", data.rate(true)); row.setDouble("outRate", data.rate(false));
            row.setByteArray("totals", longs(data.totals));
            deviceList.appendTag(row);
        }
        result.setTag("devices", deviceList);
        return result;
    }

    private static NBTTagCompound resource(ResourceDictionary.Entry entry) {
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("id", entry.id); result.setInteger("meta", entry.metadata);
        result.setString("registry", entry.name); result.setString("name", name(entry));
        result.setBoolean("fluid", entry.kind == ResourceDictionary.FLUID);
        // Display rows carry stable IDs, not copies of potentially megabyte-sized resource keys.
        return result;
    }

    private static String name(ResourceDictionary.Entry entry) {
        if (entry.kind == ResourceDictionary.FLUID) {
            Fluid fluid = FluidRegistry.getFluid(entry.name);
            return fluid == null ? entry.name : fluid.getLocalizedName(new FluidStack(fluid, 1, entry.copyTag()));
        }
        Item item = (Item) Item.itemRegistry.getObject(entry.name);
        if (item == null) return entry.name;
        ItemStack stack = new ItemStack(item, 1, entry.metadata);
        stack.setTagCompound(entry.copyTag());
        return stack.getDisplayName();
    }

    public static ItemStack icon(NBTTagCompound resource) {
        if (resource.getBoolean("fluid")) {
            Fluid fluid = FluidRegistry.getFluid(resource.getString("registry"));
            return fluid == null ? null : ItemFluidDrop.newDisplayStack(new FluidStack(fluid, 1));
        }
        Item item = (Item) Item.itemRegistry.getObject(resource.getString("registry"));
        return item == null ? null : new ItemStack(item, 1, resource.getInteger("meta"));
    }
    public static byte[] longs(long[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 8);
        for (long value : values) bytes.putLong(value);
        return bytes.array();
    }
    public static long[] longs(byte[] bytes) {
        if (bytes.length % 8 != 0 || bytes.length > 300 * 8) throw new IllegalArgumentException("Invalid graph array");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long[] values = new long[bytes.length / 8];
        for (int i = 0; i < values.length; i++) values[i] = buffer.getLong();
        return values;
    }
}
