package com.aeinspector.gui;

import java.util.LinkedHashMap;
import java.util.Map;
import com.aeinspector.integration.FlowRuntime;
import com.aeinspector.storage.HistoryIndex;

/** World-owned query caches and a shared cooperative budget. No background execution. */
public final class InspectorQueries {
    public static final long BUDGET_NANOS = 2_000_000L;
    public final GuiWorkQueue work = new GuiWorkQueue();
    private final FlowRuntime runtime;
    private final Map<Integer, HistoryIndex> indices = new LinkedHashMap<Integer, HistoryIndex>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, HistoryIndex> entry) { return size() > 4; }
    };
    private final Map<Integer, String> names = new LinkedHashMap<Integer, String>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, String> entry) { return size() > 16384; }
    };
    public InspectorQueries(FlowRuntime runtime) { this.runtime = runtime; }
    public HistoryIndex index(int network) { return indices.computeIfAbsent(network, id -> new HistoryIndex(runtime.world, id)); }
    public String name(int resource) { return names.computeIfAbsent(resource, id -> InspectorData.name(runtime.world.resources.get(id))); }
    public void tick() { work.tick(BUDGET_NANOS); }
    public void close() { work.clear(); indices.clear(); names.clear(); }
}
