package com.aeinspector.gui;

/** Playback between confirmed server snapshots: no extrapolated or invented item flow. */
final class GraphTimeline {
    private double from, to;
    private long started, duration = 1_000_000_000L, received;
    private boolean initialized;
    void accept(long tick, long now, boolean reset) {
        if (!initialized || reset) {
            from = to = tick; started = received = now; initialized = true; return;
        }
        if (tick <= to) return;
        from = position(now); to = tick; started = now;
        duration = Math.max(200_000_000L, Math.min(2_000_000_000L, now - received)); received = now;
    }
    double position(long now) {
        double progress = Math.max(0, Math.min(1, (now - started) / (double) duration));
        return from + (to - from) * progress;
    }
}
