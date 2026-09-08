package com.aeinspector.gui;

/** Local GUI geometry. Never changes Minecraft's global GUI scale. Zero follows the game setting. */
final class InspectorScale {
    final int factor, width, height;
    InspectorScale(int override, int gameScale, int pixelsWide, int pixelsHigh, boolean unicode) {
        int requested = override == 0 ? gameScale : override;
        factor = factor(requested, pixelsWide, pixelsHigh, unicode);
        width = (pixelsWide + factor - 1) / factor;
        height = (pixelsHigh + factor - 1) / factor;
    }
    private static int factor(int requested, int w, int h, boolean unicode) {
        int scale = 1, limit = requested == 0 ? Integer.MAX_VALUE : requested;
        while (scale < limit && w / (scale + 1) >= 320 && h / (scale + 1) >= 240) scale++;
        if (unicode && scale > 1 && (scale & 1) != 0) scale--;
        return scale;
    }
    static int next(int current, int w, int h, boolean unicode) {
        int maximum = factor(0, w, h, unicode);
        int next = current + 1;
        if (unicode && next > 1 && (next & 1) != 0) next++;
        return next > maximum ? 0 : next;
    }
}
