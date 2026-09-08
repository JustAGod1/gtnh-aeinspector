package com.aeinspector.integration;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

@LateMixin
public final class InspectorLateMixins implements ILateMixinLoader {
    @Override
    public String getMixinConfig() { return "mixins.aeinspector.late.json"; }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        return Arrays.asList("NetworkInventoryMixin", "CraftingCpuMixin", "ItemStorageMonitorMixin", "FluidStorageMonitorMixin", "BusOperationMixin",
                "StorageBusAttachmentMixin", "StorageBusTransferMixin");
    }
}
