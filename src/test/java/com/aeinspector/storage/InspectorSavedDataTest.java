package com.aeinspector.storage;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import com.aeinspector.core.NetworkRecord;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import org.junit.Test;

public class InspectorSavedDataTest {
    @Test public void vanillaMapStorageSavesAndReloadsDictionaryAndHistory() throws Exception {
        Path root = Files.createTempDirectory("inspector-vanilla-nbt");
        try {
            ISaveHandler handler = (ISaveHandler) Proxy.newProxyInstance(ISaveHandler.class.getClassLoader(), new Class<?>[]{ISaveHandler.class}, (proxy, method, args) -> {
                if (method.getName().equals("getMapFileFromName")) return root.resolve(args[0]+".dat").toFile();
                throw new UnsupportedOperationException(method.getName());
            });
            MapStorage storage = new MapStorage(handler);
            InspectorSavedData saved = new InspectorSavedData(InspectorSavedData.NAME);
            storage.setData(InspectorSavedData.NAME, saved);
            WorldStatistics world = saved.statistics();
            NBTTagCompound full = new NBTTagCompound(); full.setIntArray("variant", new int[]{7,8,9});
            int resource = world.resources.resolve((byte)0,"test:item",3,full);
            int other = world.resources.resolve((byte)0,"test:item",4,full);
            int device = world.devices.resolve(0,5,64,-8,2,"export","Export Bus");
            NetworkRecord network = world.createNetwork();
            network.observe(0); network.add(resource,device,false,false,100000);
            network.add(other,device,true,true,73); world.endTick();
            saved.markDirty(); storage.saveAllData();
            assertTrue(Files.isRegularFile(root.resolve("aeinspector.dat")));
            assertFalse(Files.exists(root.resolve("metadata"))); assertFalse(Files.exists(root.resolve("series")));
            InspectorSavedData loaded = (InspectorSavedData) new MapStorage(handler).loadData(InspectorSavedData.class,InspectorSavedData.NAME);
            assertNotNull(loaded); WorldStatistics restored=loaded.statistics();
            assertEquals(1,restored.tick()); assertEquals(full,restored.resources.get(resource).copyTag());
            assertEquals(resource,restored.resources.resolve((byte)0,"test:item",3,full));
            assertNotEquals(resource,other);
            for(int level=0;level<=8;level++) {
                HistoryQuery.Result result=new HistoryQuery(restored).query(network.id,resource,device,level,restored.tick());
                assertEquals(100000,result.totals[1]); assertEquals(100000,result.windowCount(false));
            }
            assertEquals(73,new HistoryQuery(restored).query(network.id,other,device,8,restored.tick()).totals[2]);
            assertEquals(-8,restored.devices.get(device).z);
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(root)) {
                for(Path file:(Iterable<Path>)files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(file);
            }
        }
    }

    @Test public void importsOldSegmentsWithoutChangingThem() throws Exception {
        Path root=Files.createTempDirectory("inspector-legacy-import");
        try {
            try(WorldStatistics old=new WorldStatistics(root)) {
                int id=old.resources.resolve((byte)0,"test:coal",0,null);
                NetworkRecord network=old.createNetwork(); network.observe(0); network.add(id,0,true,false,987654); old.endTick();
            }
            byte[] before=Files.readAllBytes(root.resolve("metadata/world.aeis"));
            InspectorSavedData saved=new InspectorSavedData(InspectorSavedData.NAME); saved.importLegacy(root);
            assertArrayEquals(before,Files.readAllBytes(root.resolve("metadata/world.aeis")));
            NBTTagCompound tag=new NBTTagCompound(); saved.writeToNBT(tag);
            WorldStatistics restored=new WorldStatistics(tag);
            assertEquals(987654,new HistoryQuery(restored).query(0,0,-1,8,restored.tick()).totals[0]);
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(root)) {
                for(Path file:(Iterable<Path>)files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(file);
            }
        }
    }
}
