package com.aeinspector.gui;

/** Bounds for the local client scrollbar. */
final class ScrollWindow {
    private ScrollWindow() {}
    static int clamp(int offset, int count, int rows) { return Math.max(0, Math.min(offset, Math.max(0, count - rows))); }
    static int at(double fraction, int count, int rows) {
        return clamp((int) Math.round(Math.max(0, Math.min(1, fraction)) * Math.max(0, count - rows)), count, rows);
    }
}
