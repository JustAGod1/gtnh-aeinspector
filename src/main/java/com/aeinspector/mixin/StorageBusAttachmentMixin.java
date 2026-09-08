package com.aeinspector.mixin;

import appeng.api.networking.security.IActionHost;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.StorageChannel;
import appeng.me.storage.StorageBusInventoryHandler;
import appeng.me.storage.MEInventoryHandler;
import appeng.parts.misc.PartStorageBus;
import com.aeinspector.integration.FlowRuntime;
import com.aeinspector.integration.StorageBusAccess;
import com.glodblock.github.common.parts.PartFluidStorageBus;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {PartStorageBus.class, PartFluidStorageBus.class}, remap = false)
public abstract class StorageBusAttachmentMixin implements StorageBusAccess {
    // Multi-target mixins require this on the shadow itself, even with @Mixin(remap = false).
    @Shadow(remap = false) private MEInventoryHandler<?> handler;
    @Unique private IMEInventory<?> aeinspector$inventory;
    @Unique private MEInventoryHandler<?> aeinspector$handler;
    @Override public MEInventoryHandler<?> aeinspector$getStorageHandler() { return handler; }
    @Inject(method = "updateStatus", at = @At("RETURN"), require = 1)
    private void aeinspector$status(CallbackInfo ci) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime != null) runtime.storageBuses.statusChanged((IActionHost) this);
    }
    @Inject(method = "getInternalHandler", at = @At("RETURN"), require = 1)
    private void aeinspector$detach(CallbackInfoReturnable<MEInventoryHandler<?>> cir) {
        FlowRuntime runtime = FlowRuntime.get();
        MEInventoryHandler<?> current = cir.getReturnValue();
        if (runtime != null) {
            runtime.storageBuses.retain((IActionHost) this, current);
            if (current != null && current == aeinspector$handler && aeinspector$inventory != null)
                runtime.storageBuses.attach((IActionHost) this, current, aeinspector$inventory);
        }
        if (current == null) { aeinspector$inventory = null; aeinspector$handler = null; }
    }
    @WrapOperation(method = "getInternalHandler", at = @At(value = "NEW", target = "appeng/me/storage/StorageBusInventoryHandler"), require = 1)
    private StorageBusInventoryHandler<?> aeinspector$attach(IMEInventory<?> inventory, StorageChannel channel,
            Operation<StorageBusInventoryHandler<?>> original) {
        StorageBusInventoryHandler<?> handler = original.call(inventory, channel);
        aeinspector$inventory = inventory; aeinspector$handler = handler;
        return handler;
    }
}
