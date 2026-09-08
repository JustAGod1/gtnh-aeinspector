package com.aeinspector.smoke;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.launchwrapper.Launch;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Test-only companion loaded by the real Forge/Mixin launch chain. */
@Mod(modid = "aeinspector_smoketest", name = "AE Inspector transformation smoke test", version = "1",
        dependencies = "required-after:aeinspector")
public final class TransformationSmoke {
    private boolean done;
    @Mod.EventHandler public void postInit(FMLPostInitializationEvent event) throws Exception {
        List<String> lines = new ArrayList<>();
        String[] targets = {
                "appeng.me.storage.NetworkInventoryHandler",
                "appeng.me.cluster.implementations.CraftingCPUCluster",
                "appeng.me.storage.MEMonitorIInventory",
                "com.glodblock.github.inventory.MEMonitorIFluidHandler",
                "appeng.parts.automation.PartImportBus",
                "appeng.parts.automation.PartExportBus",
                "com.glodblock.github.common.parts.PartFluidImportBus",
                "com.glodblock.github.common.parts.PartFluidExportBus"
        };
        try {
            for (String target : targets) {
                Class<?> type = Class.forName(target, false, Launch.classLoader);
                int handlers = 0;
                for (Method method : type.getDeclaredMethods()) if (method.getName().contains("aeinspector$")) handlers++;
                if (handlers == 0) throw new AssertionError("No injected handlers in " + target);
                lines.add("PASS " + target + " injected handlers=" + handlers);
            }
            lines.add("PASS transformed all requested targets using the actual Forge launch class loader");
        } catch (Throwable failure) {
            lines.add("FAIL " + failure);
            Files.write(Paths.get("inspector-transformation-smoke.txt"), lines, StandardCharsets.UTF_8);
            throw new RuntimeException("AE Inspector transformation smoke failed", failure);
        }
        Files.write(Paths.get("inspector-transformation-smoke.txt"), lines, StandardCharsets.UTF_8);
        for (String line : lines) System.out.println("[AE Inspector smoke] " + line);
        FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (!done && event.phase == TickEvent.Phase.END && Minecraft.getMinecraft().currentScreen instanceof GuiMainMenu) {
            done = true;
            Minecraft.getMinecraft().shutdown();
        }
    }
}
