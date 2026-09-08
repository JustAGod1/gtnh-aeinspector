package com.aeinspector.mixin;

import org.spongepowered.asm.mixin.Mixin;
import com.aeinspector.integration.FlowRuntime;
import com.glodblock.github.common.parts.PartFluidExportBus;
import com.glodblock.github.common.parts.PartFluidImportBus;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.parts.automation.PartExportBus;
import appeng.parts.automation.PartImportBus;

@Mixin(value = {PartExportBus.class, PartImportBus.class, PartFluidExportBus.class, PartFluidImportBus.class}, remap = false)
public abstract class BusOperationMixin {
    @WrapMethod(method = "doBusWork")
    private TickRateModulation aeinspector$busOperation(Operation<TickRateModulation> original) {
        FlowRuntime runtime = FlowRuntime.get();
        if (runtime == null) return original.call();
        int scope = runtime.busTransfers.enter(runtime.endpoint((IActionHost) this));
        try { return original.call(); }
        finally { runtime.busTransfers.leave(scope); }
    }
}
