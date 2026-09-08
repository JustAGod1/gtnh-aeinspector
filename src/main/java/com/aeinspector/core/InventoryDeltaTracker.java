package com.aeinspector.core;

/** Absolute inventory snapshots minus confirmed AE operations since the preceding snapshot. */
public final class InventoryDeltaTracker {
    private LongCounters previous = new LongCounters(), current = new LongCounters();
    private final LongCounters ae = new LongCounters(), changes = new LongCounters();
    private final LongCounters.Consumer sink;
    private boolean initialized;

    public InventoryDeltaTracker(LongCounters.Consumer sink) { this.sink = sink; }
    public boolean contains(int resource) { return initialized && previous.get(resource) > 0; }
    public void aeChange(int resource, long amount) { if (initialized) ae.add(resource, amount); }
    public void beginSample() { current.clear(); }
    public void sample(int resource, long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative stored amount");
        current.add(resource, amount);
    }
    public void endSample() {
        if (initialized) {
            changes.clear();
            current.forEach(changes::add);
            previous.forEach((id, amount) -> changes.add(id, -amount));
            ae.forEach((id, amount) -> changes.add(id, -amount));
            changes.forEach((id, amount) -> { if (amount != 0) sink.accept(id, amount); });
            changes.clear();
        }
        LongCounters swap = previous; previous = current; current = swap;
        ae.clear(); initialized = true;
    }
    public void invalidate() { initialized = false; previous.clear(); current.clear(); ae.clear(); changes.clear(); }
}
