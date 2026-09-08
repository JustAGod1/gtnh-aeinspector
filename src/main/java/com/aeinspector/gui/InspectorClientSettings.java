package com.aeinspector.gui;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

/** Local presentation preference only; statistics still belong to Minecraft's world NBT. */
final class InspectorClientSettings {
    private static Configuration configuration;
    private static int scale;
    static int scale(File minecraftDirectory) {
        if (configuration == null) {
            configuration = new Configuration(new File(minecraftDirectory, "config/aeinspector-client.cfg"));
            configuration.load();
            scale = configuration.getInt("guiScale", "client", 0, 0, 32,
                    "AE Inspector only. 0 follows Minecraft's GUI scale; positive values are pixel scale factors.");
        }
        return scale;
    }
    static void setScale(int value) {
        scale = value;
        configuration.get("client", "guiScale", 0).set(value);
        configuration.save();
    }
    private InspectorClientSettings() {}
}
