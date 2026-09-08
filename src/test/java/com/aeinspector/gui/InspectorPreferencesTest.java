package com.aeinspector.gui;

import static org.junit.Assert.*;
import java.io.File;
import java.lang.reflect.Field;
import cpw.mods.fml.relauncher.FMLInjectionData;
import net.minecraftforge.common.config.Configuration;
import org.junit.Rule;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class InspectorPreferencesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Field minecraftHome;
    private Object previousHome;
    @Before public void supplyForgeConfigBaseWithoutStartingMinecraft() throws Exception {
        minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome"); minecraftHome.setAccessible(true);
        previousHome = minecraftHome.get(null); minecraftHome.set(null, folder.getRoot());
    }
    @After public void restoreForgeConfigBase() throws Exception { minecraftHome.set(null, previousHome); }

    @Test public void reopenRestoresOptionsSelectionAndScrollWithoutReusingRequestIdentity() throws Exception {
        InspectorPreferences preferences = new InspectorPreferences(folder.newFile());
        Object connection = new Object();
        InspectorProtocol.Request original = options();
        preferences.remember(connection, original);
        original.selected[0] = 999; original.level = 0;
        InspectorProtocol.Request restored = preferences.restore(connection);
        assertEquals(3, restored.level); assertEquals(1, restored.sort); assertEquals(2, restored.filter);
        assertEquals("Водород", restored.search); assertEquals(83, restored.resourceOffset);
        assertArrayEquals(new int[] {14, 27}, restored.selected);
        assertEquals(0, restored.sequence); assertEquals(0, restored.window); assertFalse(restored.devices);
        assertTrue(restored.subscribe);
        restored.selected[0] = 800;
        assertEquals(14, preferences.restore(connection).selected[0]);
    }
    @Test public void diskReloadPreservesPresentationButNeverAppliesDictionaryIdsToAnotherWorld() throws Exception {
        File file = folder.newFile(); Object firstConnection = new Object();
        InspectorPreferences preferences = new InspectorPreferences(file);
        preferences.setScale(3); preferences.setTableRows(12); preferences.remember(firstConnection, options());
        InspectorProtocol.Request differentWorld = preferences.restore(new Object());
        assertEquals(0, differentWorld.selected.length); assertEquals(0, differentWorld.resourceOffset);
        assertEquals(0, preferences.restore(null).selected.length);
        InspectorPreferences reloaded = new InspectorPreferences(file);
        assertEquals(3, reloaded.scale); assertEquals(12, reloaded.tableRows);
        InspectorProtocol.Request restored = reloaded.restore(firstConnection);
        assertEquals(3, restored.level); assertEquals(1, restored.sort); assertEquals(2, restored.filter);
        assertEquals("Водород", restored.search); assertEquals(0, restored.selected.length);
    }
    @Test public void savedOptionsCannotProduceInvalidProtocolValues() throws Exception {
        File file = folder.newFile(); Configuration config = new Configuration(file);
        config.get("client", "period", 0).set(999); config.get("client", "sort", 0).set(-9);
        config.get("client", "filter", 0).set(99); config.get("client", "guiScale", 0).set(99);
        config.get("client", "search", "").set(new String(new char[200]).replace('\0', 'a')); config.save();
        InspectorPreferences preferences = new InspectorPreferences(file);
        InspectorProtocol.Request request = preferences.restore(new Object());
        assertTrue(request.level >= 0 && request.level <= 8); assertTrue(request.sort >= 0 && request.sort <= 2);
        assertTrue(request.filter >= 0 && request.filter <= 2); assertTrue(preferences.scale <= 32);
        assertEquals(128, request.search.length());
    }
    private static InspectorProtocol.Request options() {
        InspectorProtocol.Request request = new InspectorProtocol.Request();
        request.level = 3; request.sort = 1; request.filter = 2; request.search = "Водород";
        request.selected = new int[] {14, 27}; request.resourceOffset = 83;
        request.window = 9; request.sequence = 42; return request;
    }
}
