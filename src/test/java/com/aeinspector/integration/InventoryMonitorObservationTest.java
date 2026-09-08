package com.aeinspector.integration;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import com.aeinspector.core.LongCounters;
import org.junit.Test;

/** Real monitor receiver contract with synthetic adapters; no Minecraft world or specific mod tiles. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class InventoryMonitorObservationTest {
    @Test public void immediateAndDelayedCallbacksReconcileWithoutDependingOnSourceOrPayload() {
        for (boolean immediate : new boolean[] {true, false}) {
            Fixture f = new Fixture(StorageChannel.ITEMS); f.monitor.set(1, 1000); f.start();
            f.monitor.set(1, 900);
            if (immediate) f.monitor.notifyChange(); // callback can precede return from AE extraction
            f.observation.operation(1, -100);
            f.monitor.set(1, 936); f.monitor.notifyChange(); // combined/later notification, null source
            f.flush(); assertEquals(36, f.changes.get(1));
            f.changes.clear(); f.monitor.notifyChange(); f.flush(); assertEquals(0, f.changes.get(1));
        }
    }
    @Test public void netZeroCallbacksAndSilentChangesCanBeReconciled() {
        Fixture f = new Fixture(StorageChannel.ITEMS); f.monitor.set(3, 64); f.start();
        f.observation.operation(3, -64); // AE removes 64, physical input restores 64; no postChange
        f.flush(); assertEquals(64, f.changes.get(3));
        f.changes.clear(); f.monitor.set(3, 12);
        f.observation.sample(); // periodic fallback for an adapter without a notification
        assertEquals(-52, f.changes.get(3));
    }
    @Test public void lifecycleAndVisibilityResetDoNotGenerateInventorySizedFlows() {
        Fixture f = new Fixture(StorageChannel.ITEMS); f.monitor.set(1, 1000); f.start();
        assertEquals(1, f.monitor.listeners.size()); f.observation.connect(); assertEquals(1, f.monitor.listeners.size());
        f.monitor.set(1, 0); f.monitor.notifyVisibility(); f.flush(); assertEquals(0, f.changes.get(1));
        f.monitor.set(1, 1000); f.monitor.notifyVisibility(); f.flush(); assertEquals(0, f.changes.get(1));
        f.monitor.set(1, 1005); f.monitor.notifyChange(); f.flush(); assertEquals(5, f.changes.get(1));
        f.changes.clear(); f.observation.close(); assertTrue(f.monitor.listeners.isEmpty());
        assertFalse(f.observation.isValid(f.observation)); f.monitor.set(1, 9000); f.observation.sample();
        f.observation.connect(); f.flush(); assertEquals(0, f.changes.get(1));
        assertTrue(f.observation.isValid(f.observation)); assertFalse(f.observation.isValid(new Object()));
    }
    @Test public void fluidsAndFreshStackObjectsUseTheSameReceiverAndExactIds() {
        Fixture f = new Fixture(StorageChannel.FLUIDS); f.monitor.set(1, 100); f.monitor.set(2, 200); f.start();
        f.monitor.set(1, 80); f.monitor.set(2, 225); f.monitor.notifyChange(); f.flush();
        assertEquals(-20, f.changes.get(1)); assertEquals(25, f.changes.get(2));
        f.changes.clear(); f.monitor.notifyChange(); f.flush();
        assertEquals(0, f.changes.get(1)); assertEquals(0, f.changes.get(2));
    }
    private static final class Fixture {
        final FakeMonitor monitor;
        final LongCounters changes = new LongCounters();
        final InventoryMonitorObservation observation;
        boolean dirty;
        Fixture(StorageChannel channel) {
            monitor = new FakeMonitor(channel);
            observation = new InventoryMonitorObservation(monitor, stack -> monitor.ids.get(stack), changes::add, () -> dirty = true);
        }
        void start() { observation.connect(); flush(); }
        void flush() { if (dirty) { dirty = false; observation.sample(); } }
    }
    private static final class FakeMonitor implements IMEMonitor {
        final StorageChannel channel;
        final Map<Integer, Long> quantities = new LinkedHashMap<>();
        final IdentityHashMap<IAEStack<?>, Integer> ids = new IdentityHashMap<>();
        final IdentityHashMap<IMEMonitorHandlerReceiver, Object> listeners = new IdentityHashMap<>();
        FakeMonitor(StorageChannel channel) { this.channel = channel; }
        void set(int id, long count) { quantities.put(id, count); }
        void notifyChange() {
            // Receiver must neither trust a missing source nor retain/consume a reusable notification iterable.
            Iterable payload = () -> { throw new AssertionError("Notification deltas are only an invalidation hint"); };
            for (Map.Entry<IMEMonitorHandlerReceiver, Object> e : listeners.entrySet())
                if (e.getKey().isValid(e.getValue())) e.getKey().postChange(this, payload, null);
        }
        void notifyVisibility() { for (IMEMonitorHandlerReceiver listener : listeners.keySet()) listener.onListUpdate(); }
        @Override public void addListener(IMEMonitorHandlerReceiver receiver, Object token) { listeners.put(receiver, token); }
        @Override public void removeListener(IMEMonitorHandlerReceiver receiver) { listeners.remove(receiver); }
        @Override public StorageChannel getChannel() { return channel; }
        @Override public IItemList getStorageList() {
            List<IAEStack> stacks = new ArrayList<>(); ids.clear();
            for (Map.Entry<Integer, Long> e : quantities.entrySet()) {
                final long count = e.getValue();
                IAEStack stack = (IAEStack) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IAEStack.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getStackSize")) return count;
                            throw new AssertionError(method.getName());
                        });
                ids.put(stack, e.getKey()); stacks.add(stack);
            }
            return (IItemList) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IItemList.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("iterator")) return stacks.iterator();
                        throw new AssertionError(method.getName());
                    });
        }
        @Override public IAEStack injectItems(IAEStack stack, Actionable mode, BaseActionSource source) { throw new UnsupportedOperationException(); }
        @Override public IAEStack extractItems(IAEStack stack, Actionable mode, BaseActionSource source) { throw new UnsupportedOperationException(); }
        @Override public AccessRestriction getAccess() { return AccessRestriction.READ_WRITE; }
        @Override public boolean isPrioritized(IAEStack stack) { return false; }
        @Override public boolean canAccept(IAEStack stack) { return true; }
        @Override public int getPriority() { return 0; }
        @Override public int getSlot() { return 0; }
        @Override public boolean validForPass(int pass) { return true; }
    }
}
