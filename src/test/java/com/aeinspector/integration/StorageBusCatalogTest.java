package com.aeinspector.integration;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.Collections;
import appeng.api.config.AccessRestriction;
import appeng.api.config.IncludeExclude;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.MEInventoryHandler;
import appeng.util.prioitylist.IPartitionList;
import org.junit.Test;

@SuppressWarnings({"rawtypes", "unchecked"})
public class StorageBusCatalogTest {
    @Test public void configuredIdleBusesUseTheCompiledAeFilterWithoutReadingOrTransferringInventory() {
        Fixture f = new Fixture(StorageChannel.ITEMS);
        IAEStack other = stack(1);
        assertFalse(StorageBusCatalog.configured(f.handler, f.request)); // empty filter must not match every resource
        f.handler.setPartitionList(new IPartitionList() {
            @Override public boolean isListed(IAEStack value) { return value == f.request; }
            @Override public boolean isEmpty() { return false; }
            // A compiled ore/fuzzy predicate need not enumerate an exact list of item variants.
            @Override public Iterable getItems() { return Collections.emptyList(); }
        });
        assertTrue(StorageBusCatalog.configured(f.handler, f.request));
        assertFalse(StorageBusCatalog.configured(f.handler, other));
        f.handler.setWhitelist(IncludeExclude.BLACKLIST);
        assertFalse(StorageBusCatalog.configured(f.handler, f.request));
        assertFalse(StorageBusCatalog.configured(f.handler, other));
        assertEquals(0, f.reads);
    }
    @Test public void currentContentsAreFoundReadOnlyEvenWithoutConfiguredFiltersOrPastTransfers() {
        for (StorageChannel channel : StorageChannel.values()) {
            Fixture f = new Fixture(channel);
            assertFalse(StorageBusCatalog.contains(f.handler, f.request));
            f.count = 10_000_000_000L;
            assertTrue(StorageBusCatalog.contains(f.handler, f.request));
            f.handler.setBaseAccess(AccessRestriction.WRITE);
            assertTrue(StorageBusCatalog.contains(f.handler, f.request)); // physical stock, independently of bus visibility
            f.count = 0; assertFalse(StorageBusCatalog.contains(f.handler, f.request));
            assertEquals(4, f.reads);
        }
        assertFalse(StorageBusCatalog.contains(null, stack(1)));
    }
    private static final class Fixture {
        final IAEStack request = stack(1);
        final MEInventoryHandler handler;
        long count;
        int reads;
        Fixture(StorageChannel channel) {
            IMEInventoryHandler inventory = (IMEInventoryHandler) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {IMEInventoryHandler.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getAccess")) return AccessRestriction.READ_WRITE;
                        if (method.getName().equals("getChannel")) return channel;
                        if (method.getName().equals("getAvailableItem")) {
                            reads++; assertSame(request, args[0]); return count == 0 ? null : stack(count);
                        }
                        throw new AssertionError("Unexpected inventory action: " + method.getName());
                    });
            handler = new MEInventoryHandler(inventory, channel);
        }
    }
    private static IAEStack stack(long count) {
        return (IAEStack) Proxy.newProxyInstance(StorageBusCatalogTest.class.getClassLoader(), new Class<?>[] {IAEStack.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getStackSize")) return count;
                    throw new AssertionError(method.getName());
                });
    }
}
