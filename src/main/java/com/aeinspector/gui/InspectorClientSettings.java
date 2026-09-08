package com.aeinspector.gui;

import java.io.File;

/** Local presentation preference only; statistics still belong to Minecraft's world NBT. */
final class InspectorClientSettings {
    private static InspectorPreferences preferences;
    private static InspectorPreferences preferences(File minecraftDirectory) {
        if (preferences == null) preferences = new InspectorPreferences(new File(minecraftDirectory, "config/aeinspector-client.cfg"));
        return preferences;
    }
    static int scale(File minecraftDirectory) { return preferences(minecraftDirectory).scale; }
    static int tableRows(File minecraftDirectory) { return preferences(minecraftDirectory).tableRows; }
    static void setTableRows(int value) { preferences.setTableRows(value); }
    static void setScale(int value) { preferences.setScale(value); }
    static InspectorProtocol.Request restore(File minecraftDirectory, Object connection) { return preferences(minecraftDirectory).restore(connection); }
    static void remember(Object connection, InspectorProtocol.Request request) { preferences.remember(connection, request); }
    private InspectorClientSettings() {}
}
