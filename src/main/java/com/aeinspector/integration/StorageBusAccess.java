package com.aeinspector.integration;

import appeng.me.storage.MEInventoryHandler;

/** Reads the already attached adapter without rebuilding a bus or looking up/loading its neighbor. */
public interface StorageBusAccess {
    MEInventoryHandler<?> aeinspector$getStorageHandler();
}
