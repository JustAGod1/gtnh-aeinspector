package com.aeinspector.gui;

/** A matching response unlocks controls. Older revisions and backwards ticks never replace the screen. */
final class GuiRequestState {
    private int sequence = -1;
    private long tick = -1, sentAt;
    private boolean pending;
    void begin(int sequence, long now) { this.sequence = sequence; tick = -1; sentAt = now; pending = true; }
    boolean accept(int sequence, long tick) {
        if (sequence != this.sequence || tick < this.tick) return false;
        this.tick = tick; pending = false; return true;
    }
    boolean pending() { return pending; }
    boolean slow(long now) { return pending && now - sentAt >= 8_000_000_000L; }
}
