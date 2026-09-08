package com.aeinspector.core;

import java.util.ArrayList;

/** Nets extraction/return legs only inside one bus operation and for that bus's source. */
public final class BusTransferScope implements TransferTracker.Sink {
    private final TransferTracker.Sink downstream;
    private final ArrayList<Frame> frames = new ArrayList<>();
    private int depth;

    public BusTransferScope(TransferTracker.Sink downstream) { this.downstream = downstream; }

    public int enter(int device) {
        if (depth == frames.size()) frames.add(new Frame());
        Frame frame = frames.get(depth);
        frame.device = device;
        return depth++;
    }

    /** Always close, including exceptional exits: already confirmed transfers still happened. */
    public void leave(int scope) {
        if (scope < 0 || scope != depth - 1) throw new IllegalStateException("Unbalanced bus operation");
        Frame frame = frames.get(--depth);
        try { frame.deltas.forEach(frame); }
        finally { frame.deltas.clear(); }
    }

    @Override public void record(long network, int resource, int device, boolean incoming, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Nonpositive transfer");
        for (int i = depth - 1; i >= 0; i--) {
            Frame frame = frames.get(i);
            if (frame.device == device) {
                if (network < 0 || network > Integer.MAX_VALUE || resource < 0) throw new IllegalArgumentException("ID");
                long key = (network << 32) | (resource & 0xffffffffL);
                frame.deltas.add(key, incoming ? amount : -amount);
                return;
            }
        }
        downstream.record(network, resource, device, incoming, amount);
    }

    private final class Frame implements LongCounters.Consumer {
        final LongCounters deltas = new LongCounters();
        int device;
        @Override public void accept(long key, long net) {
            if (net != 0) record(key >>> 32, (int) key, device, net > 0, net > 0 ? net : Math.negateExact(net));
        }
    }
}
