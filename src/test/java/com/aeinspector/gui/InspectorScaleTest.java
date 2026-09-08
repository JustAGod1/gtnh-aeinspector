package com.aeinspector.gui;

import static org.junit.Assert.*;
import org.junit.Test;

public class InspectorScaleTest {
    @Test public void overrideDoesNotDependOnGlobalScaleAndZeroFollowsIt() {
        InspectorScale local = new InspectorScale(2, 4, 2560, 1440, false);
        assertEquals(2, local.factor);
        assertEquals(1280, local.width);
        assertEquals(720, local.height);
        assertEquals(4, new InspectorScale(0, 4, 2560, 1440, false).factor);
        assertEquals(6, new InspectorScale(0, 0, 2560, 1440, false).factor);
    }

    @Test public void buttonCyclesOnlyUsableFactorsAndReturnsToGameSetting() {
        int scale = 0;
        for (int expected : new int[] {1, 2, 3, 4, 0, 1}) {
            scale = InspectorScale.next(scale, 1920, 1080, false);
            assertEquals(expected, scale);
        }
        scale = 0;
        for (int expected : new int[] {1, 2, 4, 0}) {
            scale = InspectorScale.next(scale, 1920, 1080, true);
            assertEquals(expected, scale);
        }
    }

    @Test public void shrinkingWindowClampsScaleAndStillFitsThePanel() {
        InspectorScale resized = new InspectorScale(6, 4, 853, 481, false);
        assertEquals(2, resized.factor);
        assertEquals(427, resized.width);
        assertEquals(241, resized.height);
        assertEquals(0, InspectorScale.next(6, 853, 481, false));
        assertEquals(1, new InspectorScale(32, 0, 640, 360, false).factor);
        assertEquals(2, new InspectorScale(3, 0, 1920, 1080, true).factor);
    }
}
