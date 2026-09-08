package com.aeinspector.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.StorageBusInventoryHandler;
import com.aeinspector.integration.FlowRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = MEInventoryHandler.class, remap = false)
public abstract class StorageBusTransferMixin {
    @WrapMethod(method = "injectItems")
    private IAEStack<?> aeinspector$insert(IAEStack<?> input, Actionable mode, BaseActionSource source, Operation<IAEStack<?>> original) {
        return aeinspector$operation(input, mode, source, original, true);
    }
    @WrapMethod(method = "extractItems")
    private IAEStack<?> aeinspector$extract(IAEStack<?> input, Actionable mode, BaseActionSource source, Operation<IAEStack<?>> original) {
        return aeinspector$operation(input, mode, source, original, false);
    }
    @Unique private IAEStack<?> aeinspector$operation(IAEStack<?> input, Actionable mode, BaseActionSource source,
            Operation<IAEStack<?>> original, boolean insert) {
        if (!((Object) this instanceof StorageBusInventoryHandler) || input == null || mode != Actionable.MODULATE)
            return original.call(input, mode, source);
        FlowRuntime runtime = FlowRuntime.get(); MEInventoryHandler<?> handler = (MEInventoryHandler<?>) (Object) this;
        if (runtime == null || !runtime.storageBuses.observes(handler)) return original.call(input, mode, source);
        int resource = runtime.resources.resolve(input); long requested = input.getStackSize();
        IAEStack<?> result;
        try { result = original.call(input, mode, source); }
        catch (RuntimeException | Error problem) { runtime.storageBuses.invalidate(handler); throw problem; }
        long returned = result == null ? 0 : result.getStackSize();
        runtime.storageBuses.operation(handler, resource, insert ? requested - returned : -returned);
        return result;
    }
}
