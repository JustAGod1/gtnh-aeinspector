package com.aeinspector.gui;

final class InspectorLayout {
    static final int SCROLL_WIDTH = 14, SCROLL_MARGIN = 6;
    static final class Controls {
        final int searchWidth, sortLeft, sortWidth, filterLeft, filterWidth, scaleLeft, scaleWidth;
        Controls(int width) {
            scaleWidth = width < 450 ? 76 : 90;
            filterWidth = width < 450 ? 64 : 104;
            sortWidth = width < 450 ? 62 : 140;
            searchWidth = width - 24 - scaleWidth - filterWidth - sortWidth - 18;
            sortLeft = 12 + searchWidth + 6;
            filterLeft = sortLeft + sortWidth + 6;
            scaleLeft = filterLeft + filterWidth + 6;
        }
    }
    static int outgoingColumn(int width, boolean devices) {
        int right = width - SCROLL_MARGIN - SCROLL_WIDTH - 5;
        return Math.min(width * 75 / 100, right - (devices ? 7 : 34) - 54);
    }
    static int incomingColumn(int width, boolean devices) { return Math.min(width * 54 / 100, outgoingColumn(width, devices) - 60); }
    final int rows, rowHeight, rowsTop, graphTop = 86, graphHeight;
    InspectorLayout(int height, boolean devices) { this(height, devices, 0); }
    InspectorLayout(int height, boolean devices, int preferredRows) {
        if (devices) {
            rows = Math.max(3, Math.min(10, (height - 110) / 22));
            rowHeight = Math.min(26, (height - 110) / rows); rowsTop = 86;
        } else {
            rowHeight = height < 280 ? 14 : 18;
            int defaultRows = height < 250 ? 2 : Math.max(3, Math.min(5, 3 + (height - 300) / 80));
            int maximumRows = Math.max(2, (height - graphTop - 55 - 26 - 24) / rowHeight);
            rows = Math.max(2, Math.min(maximumRows, preferredRows <= 0 ? defaultRows : preferredRows));
            rowsTop = height - 24 - rows * rowHeight;
        }
        graphHeight = rowsTop - graphTop - 26;
    }
    int dividerY() { return rowsTop - 21; }
    int rowsAtDivider(int height, int y) { return Math.max(2, (int) Math.round((height - 24 - y - 21) / (double) rowHeight)); }
}
