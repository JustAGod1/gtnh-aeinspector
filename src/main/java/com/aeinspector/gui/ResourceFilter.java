package com.aeinspector.gui;

import com.aeinspector.core.ResourceDictionary;

final class ResourceFilter {
    static final int ALL = 0, ITEMS = 1, FLUIDS = 2;
    private ResourceFilter() {}
    static boolean includes(int filter, byte kind) {
        return filter == ALL || filter == ITEMS && kind == ResourceDictionary.ITEM || filter == FLUIDS && kind == ResourceDictionary.FLUID;
    }
}
