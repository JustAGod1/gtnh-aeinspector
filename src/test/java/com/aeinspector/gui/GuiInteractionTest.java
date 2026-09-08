package com.aeinspector.gui;

import static org.junit.Assert.*;
import org.junit.Test;

public class GuiInteractionTest {
    @Test public void changingPeriodWaitsForItsOwnReplyAndIgnoresOldUpdates() {
        GuiRequestState state = new GuiRequestState();
        state.begin(4, 0);
        assertTrue(state.pending());
        assertFalse(state.accept(3, 200)); // old period's periodic update
        assertTrue(state.pending());
        assertTrue(state.accept(4, 180));
        assertFalse(state.pending());
        state.begin(5, 1_000_000_000L);
        assertFalse(state.accept(4, 210)); // old response cannot apply another filter
        assertTrue(state.pending());
        assertTrue(state.accept(5, 210));
        assertFalse(state.accept(5, 209));
        assertTrue(state.accept(5, 230)); // subsequent live update
    }

    @Test public void SlowOrMissingResponseNeverUnlocksTheControls() {
        GuiRequestState state = new GuiRequestState();
        state.begin(9, 1_000_000_000L);
        assertFalse(state.slow(8_999_999_999L));
        assertTrue(state.slow(9_000_000_000L));
        assertTrue(state.pending());
        assertFalse(state.accept(8, 1000));
        assertTrue(state.pending());
        assertTrue(state.accept(9, 1001)); // retry uses the same revision
        assertFalse(state.slow(20_000_000_000L));
    }

    @Test public void fiveSecondGraphMovesContinuouslyBetweenConfirmedTicks() {
        GraphTimeline timeline = new GraphTimeline();
        timeline.accept(100, 0, true);
        timeline.accept(120, 1_000_000_000L, false);
        assertEquals(100, timeline.position(1_000_000_000L), 0);
        assertEquals(105, timeline.position(1_250_000_000L), 0);
        assertEquals(110, timeline.position(1_500_000_000L), 0);
        assertEquals(120, timeline.position(2_000_000_000L), 0);
        assertEquals(120, timeline.position(20_000_000_000L), 0); // no fabricated future ticks
    }

    @Test public void EarlySnapshotDoesNotJumpPlaybackAndPeriodChangeResetsIt() {
        GraphTimeline timeline = new GraphTimeline();
        timeline.accept(100, 0, true);
        timeline.accept(120, 1_000_000_000L, false);
        double before = timeline.position(1_500_000_000L);
        timeline.accept(130, 1_500_000_000L, false);
        assertEquals(before, timeline.position(1_500_000_000L), 0);
        assertEquals(120, timeline.position(1_750_000_000L), 0);
        timeline.accept(130, 1_750_000_000L, false); // duplicate does not restart animation
        assertEquals(130, timeline.position(2_000_000_000L), 0);
        timeline.accept(135, 2_000_000_000L, true);
        assertEquals(135, timeline.position(2_000_000_000L), 0);
    }

    @Test public void scrollbarCanReachFirstAndLastResourceWithoutBlankTailPages() {
        assertEquals(0, ScrollWindow.clamp(-1, 5420, 5));
        assertEquals(5415, ScrollWindow.clamp(99999, 5420, 5));
        assertEquals(0, ScrollWindow.at(-0.5, 5420, 5));
        assertEquals(5415, ScrollWindow.at(1.5, 5420, 5));
        assertEquals(50, ScrollWindow.at(0.5, 105, 5));
        assertEquals(0, ScrollWindow.clamp(70, 2, 5)); // filtering shrank the list
        assertEquals(0, ScrollWindow.at(1, 0, 5));
    }

    @Test public void graphsAxesTableAndFooterFitEverySupportedGuiHeight() {
        for (int height = 220; height <= 600; height++) {
            InspectorLayout main = new InspectorLayout(height, false);
            assertTrue(main.rows >= 2 && main.rows <= 5);
            assertTrue(main.graphHeight - 33 >= 22); // two Y labels cannot overlap
            assertTrue(main.graphTop + main.graphHeight < main.rowsTop - 13);
            assertTrue(main.rowsTop + main.rows * main.rowHeight <= height - 24);
            InspectorLayout devices = new InspectorLayout(height, true);
            assertTrue(devices.rows >= 3 && devices.rows <= 10);
            assertTrue(devices.rowHeight >= 22);
            assertTrue(devices.rowsTop + devices.rows * devices.rowHeight <= height - 24);
        }
        assertTrue(new InspectorLayout(340, false).graphHeight > 130);
        assertTrue(new InspectorLayout(600, false).graphHeight > 350);
    }
    @Test public void expandedResourceTablePreservesGraphsAndDividerDragMatchesRows() {
        for (int height = 220; height <= 600; height++) {
            for (int requested : new int[] {0, 2, 6, 10, 20, 40, 1000}) {
                InspectorLayout layout = new InspectorLayout(height, false, requested);
                assertTrue(layout.rows >= 2); assertTrue(layout.graphHeight >= 55);
                assertTrue(layout.dividerY() - 5 >= layout.graphTop + layout.graphHeight);
                assertTrue(layout.dividerY() + 5 < layout.rowsTop - 13);
                assertEquals(height - 24, layout.rowsTop + layout.rows * layout.rowHeight);
                assertEquals(layout.rows, layout.rowsAtDivider(height, layout.dividerY()));
            }
        }
        InspectorLayout expanded = new InspectorLayout(600, false, 20);
        assertEquals(20, expanded.rows);
        assertEquals(21, expanded.rowsAtDivider(600, expanded.dividerY() - expanded.rowHeight));
        assertEquals(19, expanded.rowsAtDivider(600, expanded.dividerY() + expanded.rowHeight));
        assertEquals(new InspectorLayout(600, false).rows, new InspectorLayout(600, false, 0).rows);
    }
}
