package com.aeinspector.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.aeinspector.integration.FlowRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEStack;
import appeng.me.cache.SecurityCache;
import appeng.me.storage.NetworkInventoryHandler;

@Mixin(value = NetworkInventoryHandler.class, remap = false)
public abstract class NetworkInventoryMixin {
    @Shadow @Final private SecurityCache security;

    @WrapMethod(method = "injectItems")
    private IAEStack<?> aeinspector$insert(IAEStack<?> input, Actionable mode, BaseActionSource source,
            Operation<IAEStack<?>> original) {
        return aeinspector$transfer(input, mode, source, original, true);
    }

    @WrapMethod(method = "extractItems")
    private IAEStack<?> aeinspector$extract(IAEStack<?> request, Actionable mode, BaseActionSource source,
            Operation<IAEStack<?>> original) {
        return aeinspector$transfer(request, mode, source, original, false);
    }

    @Unique
    private IAEStack<?> aeinspector$transfer(IAEStack<?> input, Actionable mode, BaseActionSource source,
            Operation<IAEStack<?>> original, boolean incoming) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null || input == null || mode != Actionable.MODULATE || runtime.transfers.reserved()) {
            return original.call(input, mode, source);
        }
        int frame = runtime.begin(security.getGrid(), input, incoming, true, source);
        IAEStack<?> result;
        try { result = original.call(input, mode, source); }
        catch (RuntimeException | Error e) { runtime.transfers.abort(frame); throw e; }
        runtime.transfers.finish(frame, result == null ? 0 : result.getStackSize());
        return result;
    }
}
