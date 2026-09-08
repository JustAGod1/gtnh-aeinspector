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
import com.glodblock.github.common.item.ItemFluidPacket;
import com.glodblock.github.common.parts.PartFluidExportBus;
import com.glodblock.github.common.parts.PartFluidImportBus;
import com.glodblock.github.common.parts.PartFluidStorageBus;
import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.config.Settings;
import appeng.api.config.Upgrades;
import appeng.api.networking.IGridNode;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IItemList;
import appeng.parts.automation.PartExportBus;
import appeng.parts.automation.PartImportBus;
import appeng.parts.automation.PartSharedItemBus;
import appeng.parts.automation.PartUpgradeable;
import appeng.parts.misc.PartStorageBus;
import appeng.me.storage.MEInventoryHandler;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import appeng.util.prioitylist.OreFilteredList;

/** Reads filters from already loaded nodes. It never allocates historical series or loads chunks. */
public final class DeviceCatalog {
    private final FlowRuntime runtime;
    private final Map<Integer, PartUpgradeable> buses = new TreeMap<>();

    public DeviceCatalog(FlowRuntime runtime) { this.runtime = runtime; }
    static boolean supported(Class<?> type) {
        return PartImportBus.class.isAssignableFrom(type) || PartExportBus.class.isAssignableFrom(type)
                || PartFluidImportBus.class.isAssignableFrom(type) || PartFluidExportBus.class.isAssignableFrom(type)
                || PartStorageBus.class.isAssignableFrom(type) || PartFluidStorageBus.class.isAssignableFrom(type);
    }
    private static boolean storage(PartUpgradeable bus) { return bus instanceof PartStorageBus || bus instanceof PartFluidStorageBus; }
    private static boolean fluidBus(PartUpgradeable bus) {
        return bus instanceof PartFluidImportBus || bus instanceof PartFluidExportBus || bus instanceof PartFluidStorageBus;
    }
    private static IInventory filters(PartUpgradeable bus) {
        return bus instanceof PartFluidStorageBus ? ((PartFluidStorageBus) bus).getConfig() : bus.getInventoryByName("config");
    }
    public void addNode(IGridNode node, BitSet visibleResources) {
        Object machine = node.getMachine();
        if (machine == null || !supported(machine.getClass())) return;
        PartUpgradeable bus = (PartUpgradeable) machine;
        int id = runtime.endpoint(bus);
        if (id == 0) return;
        buses.put(id, bus);
        IInventory filters = filters(bus);
        if (bus instanceof PartSharedItemBus && bus.getInstalledUpgrades(Upgrades.ORE_FILTER) > 0) return;
        if (bus instanceof PartStorageBus) {
            MEInventoryHandler<?> handler = ((StorageBusAccess) bus).aeinspector$getStorageHandler();
            if (handler != null && handler.getPartitionList() instanceof OreFilteredList) return;
        }
        for (int slot = 0; slot < slots(bus, filters); slot++) {
            ItemStack stack = filters.getStackInSlot(slot);
            if (stack == null) continue;
            if (fluidBus(bus)) {
                FluidStack fluid = ItemFluidPacket.getFluidStack(stack);
                if (fluid != null) visibleResources.set(runtime.resources.resolve(AEFluidStack.create(fluid)));
            } else visibleResources.set(runtime.resources.resolve(stack));
        }
    }

    public Matcher matcher(int resource) { return new Matcher(resource); }
    public final class Matcher {
        private final java.util.Iterator<Map.Entry<Integer, PartUpgradeable>> remaining = buses.entrySet().iterator();
        public final BitSet matches = new BitSet(), configured = new BitSet(), stored = new BitSet();
        private final int resource;
        private final ItemStack item;
        private final FluidStack fluid;
        private final IAEItemStack itemKey;
        private final IAEFluidStack fluidKey;
        private IItemList<IAEItemStack> candidate;
        private Matcher(int resource) {
            this.resource = resource;
            ResourceDictionary.Entry entry = runtime.world.resources.get(resource);
            item = representation(entry);
            fluid = entry.kind == ResourceDictionary.FLUID ? fluid(entry) : null;
            itemKey = item == null ? null : AEItemStack.create(item);
            fluidKey = fluid == null ? null : AEFluidStack.create(fluid);
            if (item != null) {
                candidate = AEApi.instance().storage().createItemList();
                candidate.add(itemKey);
            }
        }
        public boolean step() {
            if (!remaining.hasNext()) return true;
            Map.Entry<Integer, PartUpgradeable> device = remaining.next();
            PartUpgradeable bus = device.getValue();
            if (storage(bus)) {
                MEInventoryHandler<?> handler = ((StorageBusAccess) bus).aeinspector$getStorageHandler();
                if (handler == null) return false;
                IAEStack<?> key = handler.getChannel() == StorageChannel.FLUIDS ? fluidKey : itemKey;
                if (key == null) return false;
                if (StorageBusCatalog.configured(handler, key)) configured(device.getKey());
                // Custom adapters already have a reconciled snapshot; don't rescan them for each viewer.
                boolean present = runtime.storageBuses.observes(handler) ? runtime.storageBuses.contains(handler, resource)
                        : StorageBusCatalog.contains(handler, key);
                if (present) { stored.set(device.getKey()); matches.set(device.getKey()); }
                return false;
            }
            IInventory filters = filters(bus);
            boolean importBus = bus instanceof PartImportBus || bus instanceof PartFluidImportBus;
            boolean fluidBus = fluidBus(bus);
            if (fluidBus && fluid == null || !fluidBus && item == null) return false;
            if (bus instanceof PartSharedItemBus && bus.getInstalledUpgrades(Upgrades.ORE_FILTER) > 0) {
                String expression = ((PartSharedItemBus) bus).getFilter();
                Predicate<IAEItemStack> predicate = OreFilteredList.makeFilter(expression);
                if (predicate != null && predicate.test(itemKey)) configured(device.getKey());
                else if (importBus && expression.isEmpty()) configured(device.getKey());
                return false;
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
            if (matched || importBus && !configured) configured(device.getKey());
            return false;
        }
        private void configured(int device) { matches.set(device); configured.set(device); }
    }

    public boolean active(int device) {
        PartUpgradeable bus = buses.get(device);
        return bus != null && bus.getActionableNode() != null && bus.getActionableNode().isActive();
    }

    private static int slots(PartUpgradeable bus, IInventory inventory) {
        if (inventory == null) return 0;
        if (storage(bus)) return Math.min(inventory.getSizeInventory(), 18 + bus.getInstalledUpgrades(Upgrades.CAPACITY) * 9);
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
