package com.aeinspector.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aeinspector.integration.FlowRuntime;
import com.aeinspector.integration.StorageObservation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.config.Actionable;
import appeng.api.config.StorageFilter;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.storage.MEMonitorIInventory;

@Mixin(value = MEMonitorIInventory.class, remap = false)
public abstract class ItemStorageMonitorMixin {
    @Shadow private BaseActionSource mySource;
    @Unique private final StorageObservation aeinspector$observation = new StorageObservation();

    @WrapMethod(method = "onTick")
    private TickRateModulation aeinspector$baseline(Operation<TickRateModulation> original) {
        TickRateModulation result = original.call();
        aeinspector$observation.observer.initialized();
        return result;
    }

    @Inject(method = "setMode", at = @At("RETURN"))
    private void aeinspector$visibility(StorageFilter mode, CallbackInfo ci) {
        aeinspector$observation.observer.invalidate();
    }

    @Inject(method = "postDifference", at = @At("HEAD"))
    private void aeinspector$changes(Iterable<IAEItemStack> changes, CallbackInfo ci) {
        if (changes != null && aeinspector$observation.bind(mySource)) aeinspector$observation.changes(changes);
    }

    @WrapMethod(method = "injectItems(Lappeng/api/storage/data/IAEItemStack;Lappeng/api/config/Actionable;Lappeng/api/networking/security/BaseActionSource;)Lappeng/api/storage/data/IAEItemStack;")
    private IAEItemStack aeinspector$insert(IAEItemStack input, Actionable mode, BaseActionSource source,
            Operation<IAEItemStack> original) {
        return aeinspector$operation(input, mode, source, original, true);
    }

    @WrapMethod(method = "extractItems(Lappeng/api/storage/data/IAEItemStack;Lappeng/api/config/Actionable;Lappeng/api/networking/security/BaseActionSource;)Lappeng/api/storage/data/IAEItemStack;")
    private IAEItemStack aeinspector$extract(IAEItemStack input, Actionable mode, BaseActionSource source,
            Operation<IAEItemStack> original) {
        return aeinspector$operation(input, mode, source, original, false);
    }

    @Unique
    private IAEItemStack aeinspector$operation(IAEItemStack input, Actionable mode, BaseActionSource source,
            Operation<IAEItemStack> original, boolean insert) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null || input == null || mode != Actionable.MODULATE || !aeinspector$observation.bind(mySource)) {
            return original.call(input, mode, source);
        }
        int resource = runtime.resources.resolve(input);
        long requested = input.getStackSize();
        aeinspector$observation.observer.beginOperation();
        IAEItemStack result;
        try { result = original.call(input, mode, source); }
        catch (RuntimeException | Error e) { aeinspector$observation.observer.abortOperation(); throw e; }
        long returned = result == null ? 0 : result.getStackSize();
        aeinspector$observation.observer.endOperation(resource, insert ? requested - returned : -returned);
        return result;
    }
}
