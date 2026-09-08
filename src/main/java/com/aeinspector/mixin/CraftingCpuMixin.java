package com.aeinspector.mixin;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.aeinspector.core.NetworkRecord;
import com.aeinspector.integration.FlowRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.data.IAEStack;
import appeng.helpers.DualityInterface;
import appeng.me.cluster.implementations.CraftingCPUCluster;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCpuMixin {
    @Shadow public abstract IGrid getGrid();
    @Shadow private MachineSource machineSrc;

    @WrapMethod(method = {"submitJob", "mergeJob"})
    private ICraftingLink aeinspector$reserve(IGrid grid, ICraftingJob job, BaseActionSource source,
            ICraftingRequester requester, Operation<ICraftingLink> original) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null) return original.call(grid, job, source, requester);
        runtime.transfers.enterReservation();
        try { return original.call(grid, job, source, requester); }
        finally { runtime.transfers.leaveReservation(); }
    }

    @WrapMethod(method = {"storeItems", "tryExtractItems"})
    private void aeinspector$moveReserved(Operation<Void> original) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null) { original.call(); return; }
        runtime.transfers.enterReservation();
        try { original.call(); }
        finally { runtime.transfers.leaveReservation(); }
    }

    @WrapMethod(method = "injectItems")
    private IAEStack<?> aeinspector$result(IAEStack<?> input, Actionable mode, BaseActionSource source,
            Operation<IAEStack<?>> original) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null || input == null || mode != Actionable.MODULATE || runtime.transfers.reserved()
                || getGrid() == null) return original.call(input, mode, source);
        int frame = runtime.begin(getGrid(), input, true, true, source);
        IAEStack<?> result;
        try { result = original.call(input, mode, source); }
        catch (RuntimeException | Error e) { runtime.transfers.abort(frame); throw e; }
        runtime.transfers.finish(frame, result == null ? 0 : result.getStackSize());
        return result;
    }

    @WrapOperation(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingMedium;pushPattern(Lappeng/api/networking/crafting/ICraftingPatternDetails;Lnet/minecraft/inventory/InventoryCrafting;)Z"))
    private boolean aeinspector$dispatch(ICraftingMedium medium, ICraftingPatternDetails details,
            InventoryCrafting inventory, Operation<Boolean> original) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null) return original.call(medium, details, inventory);
        // Take identity/quantity BEFORE the executor is allowed to mutate its input inventory.
        int slots = inventory.getSizeInventory();
        int[] ids = new int[slots];
        long[] amounts = new long[slots];
        for (int i = 0; i < slots; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0) {
                ids[i] = runtime.resources.resolve(stack);
                amounts[i] = stack.stackSize;
            }
        }
        boolean accepted = original.call(medium, details, inventory);
        if (accepted) {
            int endpoint = medium instanceof DualityInterface
                    ? runtime.endpoint(((DualityInterface) medium).getActionSource())
                    : medium instanceof IActionHost ? runtime.endpoint((IActionHost) medium) : runtime.endpoint(machineSrc);
            NetworkRecord network = runtime.network(getGrid());
            network.observe(runtime.world.tick());
            for (int i = 0; i < slots; i++) if (amounts[i] != 0) network.add(ids[i], endpoint, false, false, amounts[i]);
        }
        return accepted;
    }
}
