package com.aeinspector.gui;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

/** Local presentation preference only; statistics still belong to Minecraft's world NBT. */
final class InspectorClientSettings {
    private static Configuration configuration;
    private static int scale, tableRows;
    static int scale(File minecraftDirectory) {
        if (configuration == null) {
            configuration = new Configuration(new File(minecraftDirectory, "config/aeinspector-client.cfg"));
            configuration.load();
            scale = configuration.getInt("guiScale", "client", 0, 0, 32,
                    "AE Inspector only. 0 follows Minecraft's GUI scale; positive values are pixel scale factors.");
            tableRows = configuration.getInt("tableRows", "client", 0, 0, 40,
                    "Inspector resource table height in rows. 0 uses the default; clamped to leave room for graphs.");
        }
        return scale;
    }
    static int tableRows(File minecraftDirectory) { scale(minecraftDirectory); return tableRows; }
    static void setTableRows(int value) {
        tableRows = value; configuration.get("client", "tableRows", 0).set(value); configuration.save();
    }
    static void setScale(int value) {
        scale = value;
        configuration.get("client", "guiScale", 0).set(value);
        configuration.save();
    }
    private InspectorClientSettings() {}
}
