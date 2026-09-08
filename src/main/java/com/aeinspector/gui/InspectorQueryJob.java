package com.aeinspector.gui;

import java.util.BitSet;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEFluidStack;
import com.aeinspector.core.DeviceDictionary;
import com.aeinspector.integration.DeviceCatalog;
import com.aeinspector.integration.FlowRuntime;
import com.aeinspector.integration.InspectorGridCache;
import com.aeinspector.storage.HistoryIndex;

/** One response, broken into bounded pieces. World access happens only on the server thread. */
final class InspectorQueryJob implements GuiWorkQueue.Task {
    static final class Changed extends Exception {}
    private enum Stage { INDEX, COVERAGE, HISTORY, ITEMS_OPEN, ITEMS, FLUIDS_OPEN, FLUIDS,
        NODES_OPEN, NODES, MATCH_DEVICES, HISTORY_DEVICES, SUMMARIES, RANK, GRAPHS, DEVICE_ROWS, FINISH }
    private final FlowRuntime runtime;
    private final IGrid grid;
    private final InspectorGridCache gridCache;
    private final long nodeVersion;
    private final InspectorProtocol.Request request;
    private final BooleanSupplier active;
    private final Consumer<NBTTagCompound> completed;
    private final Consumer<Exception> failure;
    private final SnapshotStream stream;
    private final HistoryIndex index;
    private final DeviceCatalog catalog;
    private final BitSet visible = new BitSet(), devices = new BitSet();
    private final PriorityQueue<Row> ranked;
    private final Map<Integer, HistoryIndex.Read> graphs = new HashMap<>();
    private final NBTTagCompound result = new NBTTagCompound();
    private final NBTTagList graphRows = new NBTTagList();
    private final String search;
    private HistoryIndex.Coverage coverage;
    private HistoryIndex.Read reading;
    private Iterator<IAEItemStack> items;
    private Iterator<IAEFluidStack> fluids;
    private Iterator<IGridNode> nodes;
    private DeviceCatalog.Matcher matcher;
    private Stage stage = Stage.INDEX;
    private int cursor, resource = -1, selected;
    private long graphTick;

    InspectorQueryJob(FlowRuntime runtime, IGrid grid, InspectorProtocol.Request request, SnapshotStream stream, BooleanSupplier active,
            Consumer<NBTTagCompound> completed, Consumer<Exception> failure) {
        this.runtime = runtime; this.grid = grid; this.request = request.copy();
        this.active = active; this.completed = completed; this.failure = failure; this.stream = stream;
        gridCache = grid.getCache(InspectorGridCache.class); nodeVersion = gridCache.nodeVersion();
        index = runtime.queries.index(gridCache.record().id);
        catalog = new DeviceCatalog(runtime);
        search = request.search.toLowerCase(java.util.Locale.ROOT);
        ranked = new PriorityQueue<>(Comparator.<Row>comparingLong(row -> this.request.sort == 0 ? row.in : this.request.sort == 1 ? row.out : 0)
                .reversed().thenComparing(row -> row.name, String.CASE_INSENSITIVE_ORDER).thenComparingInt(row -> row.id));
        result.setInteger("window", request.window); result.setInteger("sequence", request.sequence);
        result.setInteger("network", index.root.id); result.setInteger("level", request.level);
        result.setBoolean("devicesView", request.devices); result.setInteger("filter", request.filter);
        if (request.devices && request.selected.length > 0 && request.selected[0] < runtime.world.resources.size()) resource = request.selected[0];
    }
    @Override public boolean step() throws Exception {
        if (!active.getAsBoolean()) return true;
        if (gridCache.nodeVersion() != nodeVersion || gridCache.record().id != index.root.id) throw new Changed();
        if (stage != Stage.INDEX && !index.ready()) { index.step(); return false; }
        if (stream.needsFlush()) { stream.flush(); return false; }
        switch (stage) {
            case INDEX:
                if (!index.ready()) { index.step(); break; }
                coverage = index.coverage(runtime.world.tick()); stage = Stage.COVERAGE; break;
            case COVERAGE:
                if (coverage.step()) { cursor = 0; stage = request.devices ? Stage.NODES_OPEN : Stage.HISTORY; }
                break;
            case HISTORY:
                int historical = index.firstResource(cursor);
                if (historical < 0) stage = Stage.ITEMS_OPEN;
                else { visible.set(historical); cursor = historical + 1; }
                break;
            case ITEMS_OPEN:
                items = ((IStorageGrid) grid.getCache(IStorageGrid.class)).getItemInventory().getStorageList().iterator();
                stage = Stage.ITEMS; break;
            case ITEMS:
                try {
                    if (items.hasNext()) visible.set(runtime.resources.resolve(items.next()));
                    else { items = null; stage = Stage.FLUIDS_OPEN; }
                } catch (ConcurrentModificationException changed) { stage = Stage.ITEMS_OPEN; }
                break;
            case FLUIDS_OPEN:
                fluids = ((IStorageGrid) grid.getCache(IStorageGrid.class)).getFluidInventory().getStorageList().iterator();
                stage = Stage.FLUIDS; break;
            case FLUIDS:
                try {
                    if (fluids.hasNext()) visible.set(runtime.resources.resolve(fluids.next()));
                    else { fluids = null; stage = Stage.NODES_OPEN; }
                } catch (ConcurrentModificationException changed) { stage = Stage.FLUIDS_OPEN; }
                break;
            case NODES_OPEN:
                nodes = gridCache.nodes().iterator(); stage = Stage.NODES; break;
            case NODES:
                if (nodes.hasNext()) { catalog.addNode(nodes.next(), visible); break; }
                nodes = null; cursor = 0;
                if (!request.devices) stage = Stage.SUMMARIES;
                else if (resource < 0) stage = Stage.FINISH;
                else { matcher = catalog.matcher(resource); stage = Stage.MATCH_DEVICES; }
                break;
            case MATCH_DEVICES:
                if (matcher.step()) { devices.or(matcher.matches); stage = Stage.HISTORY_DEVICES; }
                break;
            case HISTORY_DEVICES:
                int device = index.firstDevice(resource, cursor);
                if (device >= 0) { devices.set(device); cursor = device + 1; break; }
                result.setInteger("deviceCount", devices.cardinality());
                result.setTag("deviceResource", describe(resource)); cursor = 0; stage = Stage.DEVICE_ROWS; break;
            case SUMMARIES:
                if (reading != null) {
                    if (!reading.step()) break;
                    ranked.add(new Row(resource, runtime.queries.name(resource), reading));
                    if (reading.points != null) graphs.put(resource, reading);
                    reading = null; break;
                }
                resource = visible.nextSetBit(cursor);
                if (resource < 0) {
                    result.setInteger("resourceCount", ranked.size());
                    stage = Stage.RANK; break;
                }
                cursor = resource + 1;
                if (!matches(resource)) break;
                reading = index.read(resource, -1, request.level, runtime.world.tick(), selected(resource), coverage); break;
            case RANK:
                Row row = ranked.poll();
                if (row == null) { selected = 0; stage = Stage.GRAPHS; break; }
                if (request.selected.length == 0) request.selected = new int[] {row.id};
                NBTTagCompound tag = describe(row.id);
                tag.setLong("in", row.in); tag.setLong("out", row.out);
                tag.setDouble("inRate", row.inRate); tag.setDouble("outRate", row.outRate);
                stream.add(tag); break;
            case GRAPHS:
                if (selected >= request.selected.length) { stage = Stage.FINISH; break; }
                int id = request.selected[selected];
                if (!visible.get(id)) { selected++; break; }
                if (reading == null) reading = graphs.get(id);
                if (reading == null) { reading = index.read(id, -1, request.level, runtime.world.tick(), true, coverage); break; }
                if (!reading.step()) break;
                NBTTagCompound graph = describe(id);
                graph.setLong("start", reading.start); graph.setLong("width", reading.width);
                graph.setByteArray("observed", InspectorData.longs(reading.observed));
                graph.setByteArray("totals", InspectorData.longs(reading.totals));
                for (int c = 0; c < 4; c++) graph.setByteArray("c" + c, InspectorData.longs(reading.points[c]));
                graphRows.appendTag(graph); graphTick = Math.max(graphTick, reading.now);
                reading = null; selected++; break;
            case DEVICE_ROWS:
                if (reading != null) {
                    if (!reading.step()) break;
                    stream.add(device(cursor - 1, reading)); reading = null; break;
                }
                int next = devices.nextSetBit(cursor);
                if (next < 0) { stage = Stage.FINISH; break; }
                cursor = next + 1;
                reading = index.read(resource, next, request.level, runtime.world.tick(), false, coverage); break;
            case FINISH:
                result.setLong("tick", graphTick == 0 ? runtime.world.tick() : graphTick);
                result.setIntArray("selected", request.selected);
                result.setTag("graphs", graphRows);
                if (active.getAsBoolean()) { stream.finish(result); completed.accept(result); }
                return true;
            default: throw new IllegalStateException("Query stage");
        }
        return false;
    }
    private boolean selected(int id) { for (int selected : request.selected) if (selected == id) return true; return false; }
    private boolean matches(int id) {
        com.aeinspector.core.ResourceDictionary.Entry entry = runtime.world.resources.get(id);
        if (!ResourceFilter.includes(request.filter, entry.kind)) return false;
        if (search.isEmpty()) return true;
        return (runtime.queries.name(id) + " " + entry.name + " #" + id + " " + entry.metadata).toLowerCase(java.util.Locale.ROOT).contains(search);
    }
    private NBTTagCompound describe(int id) { return InspectorData.resource(runtime.world.resources.get(id), runtime.queries.name(id)); }
    private NBTTagCompound device(int id, HistoryIndex.Read data) {
        DeviceDictionary.Device device = runtime.world.devices.get(id);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("id", id); tag.setInteger("dim", device.dimension);
        tag.setInteger("x", device.x); tag.setInteger("y", device.y); tag.setInteger("z", device.z);
        tag.setInteger("side", device.side); tag.setString("name", device.name);
        tag.setBoolean("configured", matcher.configured.get(id)); tag.setBoolean("stored", matcher.stored.get(id));
        tag.setBoolean("active", catalog.active(id));
        tag.setLong("in", data.count(true)); tag.setLong("out", data.count(false));
        tag.setDouble("inRate", data.rate(true)); tag.setDouble("outRate", data.rate(false));
        tag.setByteArray("windowCounts", InspectorData.longs(data.counts)); tag.setByteArray("totals", InspectorData.longs(data.totals));
        return tag;
    }
    @Override public void failed(Exception problem) { failure.accept(problem); }
    private static final class Row {
        final int id;
        final String name;
        final long in, out;
        final double inRate, outRate;
        Row(int id, String name, HistoryIndex.Read data) {
            this.id = id; this.name = name; in = data.count(true); out = data.count(false);
            inRate = data.rate(true); outRate = data.rate(false);
        }
    }
}
