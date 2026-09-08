package com.aeinspector;

import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.oredict.RecipeSorter;

import com.aeinspector.integration.FlowRuntime;
import com.aeinspector.integration.InspectorGridCache;
import com.aeinspector.item.ItemInspector;
import com.aeinspector.item.InspectorRecipe;
import com.aeinspector.gui.CommonGuiHandler;
import com.aeinspector.gui.InspectorProtocol;

import appeng.api.AEApi;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

@Mod(modid = InspectorMod.ID, name = "AE Inspector", version = Tags.VERSION,
        acceptedMinecraftVersions = "[1.7.10]",
        dependencies = "required-after:appliedenergistics2;required-after:ae2fc;required-after:unimixins")
public final class InspectorMod {
    public static final String ID = "aeinspector";
    @Mod.Instance(ID)
    public static InspectorMod instance;
    public static ItemInspector inspector;
    @SidedProxy(clientSide = "com.aeinspector.gui.ClientGuiHandler", serverSide = "com.aeinspector.gui.CommonGuiHandler")
    public static CommonGuiHandler gui;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        inspector = new ItemInspector();
        GameRegistry.registerItem(inspector, "inspector");
        NetworkRegistry.INSTANCE.registerGuiHandler(this, gui);
        InspectorProtocol.register();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        AEApi.instance().registries().gridCache().registerGridCache(InspectorGridCache.class, InspectorGridCache.class);
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        AEApi.instance().registries().wireless().registerWirelessHandler(inspector);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        RecipeSorter.register(ID + ":inspector", InspectorRecipe.class, RecipeSorter.Category.SHAPELESS, "after:minecraft:shapeless");
        ItemStack terminal = AEApi.instance().definitions().items().wirelessTerminal().maybeStack(1).get();
        terminal.setItemDamage(32767);
        GameRegistry.addRecipe(new InspectorRecipe(new ItemStack(inspector), terminal,
                AEApi.instance().definitions().materials().calcProcessor().maybeStack(1).get()));
    }

    @Mod.EventHandler
    public void aboutToStart(FMLServerAboutToStartEvent event) { FlowRuntime.reset(); InspectorProtocol.clear(); }

    @Mod.EventHandler
    public void stopped(FMLServerStoppedEvent event) { FlowRuntime.close(); InspectorProtocol.clear(); }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) InspectorProtocol.forget((EntityPlayerMP) event.player);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FlowRuntime runtime = FlowRuntime.get();
            if (runtime != null) runtime.endTick();
        }
    }

    @SubscribeEvent
    public void save(WorldEvent.Save event) {
        if (!event.world.isRemote && event.world.provider.dimensionId == 0) FlowRuntime.checkpoint();
    }
}
