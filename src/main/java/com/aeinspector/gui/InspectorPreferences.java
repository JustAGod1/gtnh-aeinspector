package com.aeinspector.gui;

import java.io.File;
import java.lang.ref.WeakReference;
import net.minecraftforge.common.config.Configuration;

/** Presentation options on disk; dictionary IDs and scroll position live only within their connection. */
final class InspectorPreferences {
    private final Configuration config;
    private final InspectorProtocol.Request options = new InspectorProtocol.Request();
    private WeakReference<Object> connection = new WeakReference<>(null);
    private int[] selected = new int[0];
    private int offset;
    int scale, tableRows;

    InspectorPreferences(File file) {
        config = new Configuration(file); config.load();
        scale = config.getInt("guiScale", "client", 0, 0, 32,
                "AE Inspector only. 0 follows Minecraft's GUI scale; positive values are pixel scale factors.");
        tableRows = config.getInt("tableRows", "client", 0, 0, 40,
                "Inspector resource table height in rows. 0 uses the default; clamped to leave room for graphs.");
        options.level = config.getInt("period", "client", 0, 0, 8, "Last statistics period (0 = 5s, 8 = all).");
        options.sort = config.getInt("sort", "client", 0, 0, 2, "Last statistics sort order.");
        options.filter = config.getInt("filter", "client", 0, 0, 2, "Resource kind: 0 = all, 1 = items, 2 = fluids.");
        options.search = config.getString("search", "client", "", "Last resource search.");
        if (options.search.length() > 128) options.search = options.search.substring(0, 128);
    }
    InspectorProtocol.Request restore(Object currentConnection) {
        InspectorProtocol.Request restored = options.copy();
        if (currentConnection != null && connection.get() == currentConnection) {
            restored.selected = selected.clone(); restored.resourceOffset = offset;
        }
        return restored;
    }
    void remember(Object currentConnection, InspectorProtocol.Request request) {
        options.level = request.level; options.sort = request.sort;
        options.filter = request.filter; options.search = request.search;
        connection = new WeakReference<>(currentConnection);
        selected = request.selected.clone(); offset = request.resourceOffset;
        config.get("client", "period", 0).set(options.level);
        config.get("client", "sort", 0).set(options.sort);
        config.get("client", "filter", 0).set(options.filter);
        config.get("client", "search", "").set(options.search);
        save();
    }
    void setScale(int value) { scale = value; config.get("client", "guiScale", 0).set(value); save(); }
    void setTableRows(int value) { tableRows = value; config.get("client", "tableRows", 0).set(value); save(); }
    private void save() { if (config.hasChanged()) config.save(); }
}
