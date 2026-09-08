package com.aeinspector.core;

import java.util.Arrays;

/** Reusable server-thread call stack. Used by wrappers with an unconditional finally/abort. */
public final class TransferTracker {
    private long[] networks = new long[16];
    private int[] resources = new int[16];
    private int[] devices = new int[16];
    private long[] requested = new long[16];
    private boolean[] incoming = new boolean[16];
    private boolean[] count = new boolean[16];
    private boolean[] filtered = new boolean[16];
    private int depth;
    private int reservations;
    private final Sink sink;

    public TransferTracker(Sink sink) { this.sink = sink; }

    public void enterReservation() { reservations++; }
    public void leaveReservation() {
        if (reservations == 0) throw new IllegalStateException("Unbalanced reservation scope");
        reservations--;
    }

    public boolean reserved() { return reservations != 0; }

    public int begin(long network, int resource, int device, long amount, boolean insert, boolean modulate,
            boolean player) {
        if (amount < 0) throw new IllegalArgumentException("Negative request");
        if (depth == networks.length) grow();
        int frame = depth++;
        networks[frame] = network;
        resources[frame] = resource;
        devices[frame] = device;
        requested[frame] = amount;
        incoming[frame] = insert;
        filtered[frame] = !modulate || player || reservations != 0 || (frame > 0 && filtered[frame - 1]);
        count[frame] = !filtered[frame];
        // Even a filtered parent must suppress its nested callbacks: player actions stay excluded.
        for (int i = 0; i < frame; i++) {
            if (networks[i] == network && resources[i] == resource && incoming[i] == insert) {
                count[frame] = false;
                break;
            }
        }
        return frame;
    }

    /** Insert result is remainder, extraction result is the extracted amount. */
    public void finish(int frame, long resultAmount) {
        checkFrame(frame);
        if (resultAmount < 0 || resultAmount > requested[frame]) {
            depth--;
            throw new IllegalArgumentException("Invalid transfer result");
        }
        long transferred = incoming[frame] ? requested[frame] - resultAmount : resultAmount;
        depth--;
        if (count[frame] && transferred != 0) {
            sink.record(networks[frame], resources[frame], devices[frame], incoming[frame], transferred);
        }
    }

    /** Must be used on exceptional exits, which do not have a confirmed transfer amount. */
    public void abort(int frame) { checkFrame(frame); depth--; }

    private void checkFrame(int frame) {
        if (frame < 0 || frame != depth - 1) throw new IllegalStateException("Unbalanced transfer scope");
    }

    public int depth() { return depth; }

    private void grow() {
        int capacity = networks.length * 2;
        networks = Arrays.copyOf(networks, capacity);
        resources = Arrays.copyOf(resources, capacity);
        devices = Arrays.copyOf(devices, capacity);
        requested = Arrays.copyOf(requested, capacity);
        incoming = Arrays.copyOf(incoming, capacity);
        count = Arrays.copyOf(count, capacity);
        filtered = Arrays.copyOf(filtered, capacity);
    }

    @FunctionalInterface
    public interface Sink { void record(long network, int resource, int device, boolean incoming, long amount); }
}
