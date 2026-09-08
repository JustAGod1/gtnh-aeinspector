package com.aeinspector.integration;

import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import com.aeinspector.core.ResourceDictionary;
import com.glodblock.github.common.item.ItemFluidDrop;
import com.glodblock.github.common.item.ItemFluidPacket;
import com.glodblock.github.common.parts.PartFluidExportBus;
import com.glodblock.github.common.parts.PartFluidImportBus;
import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.config.Settings;
import appeng.api.config.Upgrades;
import appeng.api.networking.IGridNode;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.parts.automation.PartExportBus;
import appeng.parts.automation.PartImportBus;
import appeng.parts.automation.PartSharedItemBus;
import appeng.parts.automation.PartUpgradeable;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import appeng.util.prioitylist.OreFilteredList;

/** Reads filters from already loaded nodes. It never allocates historical series or loads chunks. */
public final class DeviceCatalog {
    private final FlowRuntime runtime;
    private final Map<Integer, PartUpgradeable> buses = new TreeMap<>();

    public DeviceCatalog(FlowRuntime runtime, InspectorGridCache grid, BitSet visibleResources) {
        this.runtime = runtime;
        for (IGridNode node : grid.nodes()) {
            Object machine = node.getMachine();
            if (!(machine instanceof PartImportBus || machine instanceof PartExportBus
                    || machine instanceof PartFluidImportBus || machine instanceof PartFluidExportBus)) continue;
            PartUpgradeable bus = (PartUpgradeable) machine;
            int id = runtime.endpoint(bus);
            if (id == 0) continue;
            buses.put(id, bus);
            IInventory filters = bus.getInventoryByName("config");
            if (bus instanceof PartSharedItemBus && bus.getInstalledUpgrades(Upgrades.ORE_FILTER) > 0) continue;
            for (int slot = 0; slot < slots(bus, filters); slot++) {
                ItemStack stack = filters.getStackInSlot(slot);
                if (stack == null) continue;
                if (bus instanceof PartFluidImportBus || bus instanceof PartFluidExportBus) {
                    FluidStack fluid = ItemFluidPacket.getFluidStack(stack);
                    if (fluid != null) visibleResources.set(runtime.resources.resolve(AEFluidStack.create(fluid)));
                } else visibleResources.set(runtime.resources.resolve(stack));
            }
        }
    }

    public BitSet matching(int resource) {
        BitSet matches = new BitSet();
        ResourceDictionary.Entry entry = runtime.world.resources.get(resource);
        ItemStack item = representation(entry);
        FluidStack fluid = entry.kind == ResourceDictionary.FLUID ? fluid(entry) : null;
        IItemList<IAEItemStack> candidate = null;
        if (item != null) {
            candidate = AEApi.instance().storage().createItemList();
            candidate.add(AEItemStack.create(item));
        }
        for (Map.Entry<Integer, PartUpgradeable> device : buses.entrySet()) {
            PartUpgradeable bus = device.getValue();
            IInventory filters = bus.getInventoryByName("config");
            boolean importBus = bus instanceof PartImportBus || bus instanceof PartFluidImportBus;
            boolean fluidBus = bus instanceof PartFluidImportBus || bus instanceof PartFluidExportBus;
            if (fluidBus && fluid == null || !fluidBus && item == null) continue;
            if (bus instanceof PartSharedItemBus && bus.getInstalledUpgrades(Upgrades.ORE_FILTER) > 0) {
                String expression = ((PartSharedItemBus) bus).getFilter();
                Predicate<IAEItemStack> predicate = OreFilteredList.makeFilter(expression);
                if (predicate != null && predicate.test(AEItemStack.create(item))) matches.set(device.getKey());
                else if (importBus && expression.isEmpty()) matches.set(device.getKey());
                continue;
            }
            boolean configured = false, matched = false;
            for (int slot = 0; slot < slots(bus, filters); slot++) {
                ItemStack filter = filters.getStackInSlot(slot);
                if (filter == null) continue;
                if (fluidBus) {
                    FluidStack wanted = ItemFluidPacket.getFluidStack(filter);
                    if (wanted == null) continue;
                    configured = true;
                    matched |= wanted.isFluidEqual(fluid);
                } else {
                    configured = true;
                    IAEItemStack wanted = AEItemStack.create(filter);
                    if (bus.getInstalledUpgrades(Upgrades.FUZZY) > 0) {
                        FuzzyMode mode = (FuzzyMode) bus.getConfigManager().getSetting(Settings.FUZZY_MODE);
                        matched |= !candidate.findFuzzy(wanted, mode).isEmpty();
                    } else matched |= candidate.findPrecise(wanted) != null;
                }
            }
            if (matched || importBus && !configured) matches.set(device.getKey());
        }
        return matches;
    }

    public boolean active(int device) {
        PartUpgradeable bus = buses.get(device);
        return bus != null && bus.getActionableNode() != null && bus.getActionableNode().isActive();
    }

    private static int slots(PartUpgradeable bus, IInventory inventory) {
        // AE2FC imports consult every filter slot; item buses and fluid exports honor capacity upgrades.
        return bus instanceof PartFluidImportBus ? inventory.getSizeInventory()
                : Math.min(inventory.getSizeInventory(), 1 + bus.getInstalledUpgrades(Upgrades.CAPACITY) * 4);
    }

    private static FluidStack fluid(ResourceDictionary.Entry entry) {
        Fluid fluid = FluidRegistry.getFluid(entry.name);
        return fluid == null ? null : new FluidStack(fluid, 1, entry.copyTag());
    }

    private static ItemStack representation(ResourceDictionary.Entry entry) {
        if (entry.kind == ResourceDictionary.FLUID) return ResourceResolver.fluidRepresentation(
                com.glodblock.github.loader.ItemAndBlockHolder.DROP, fluid(entry));
        Item item = (Item) Item.itemRegistry.getObject(entry.name);
        if (item == null) return null;
        ItemStack stack = new ItemStack(item, 1, entry.metadata);
        stack.setTagCompound(entry.copyTag());
        return stack;
    }
}
