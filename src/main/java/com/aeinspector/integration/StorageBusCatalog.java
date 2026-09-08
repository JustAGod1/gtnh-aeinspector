package com.aeinspector.integration;

import appeng.api.config.IncludeExclude;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.MEInventoryHandler;
import appeng.util.IterationCounter;

/** Read-only resource matching for a Storage Bus, using its actual AE filter rather than duplicating it. */
@SuppressWarnings({"rawtypes", "unchecked"})
final class StorageBusCatalog {
    static boolean configured(MEInventoryHandler handler, IAEStack resource) {
        return handler != null && resource != null && handler.getWhitelist() == IncludeExclude.WHITELIST
                && !handler.getPartitionList().isEmpty() && handler.getPartitionList().isListed(resource);
    }
    static boolean contains(MEInventoryHandler handler, IAEStack resource) {
        if (handler == null || resource == null) return false;
        IAEStack stored = handler.getInternal().getAvailableItem(resource, IterationCounter.fetchNewId());
        return stored != null && stored.getStackSize() > 0;
    }
}
