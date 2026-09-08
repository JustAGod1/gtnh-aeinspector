package com.aeinspector.gui;

import static org.junit.Assert.*;
import com.aeinspector.core.ResourceDictionary;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

public class ResourceFilterTest {
    @Test public void filteringByKindKeepsMetadataAndNbtVariantsSeparate() {
        ResourceDictionary resources = new ResourceDictionary();
        NBTTagCompound tag = new NBTTagCompound(); tag.setString("variant", "one");
        resources.resolve(ResourceDictionary.ITEM, "test:resource", 0, null);
        resources.resolve(ResourceDictionary.ITEM, "test:resource", 1, null);
        resources.resolve(ResourceDictionary.ITEM, "test:resource", 1, tag);
        resources.resolve(ResourceDictionary.FLUID, "water", 0, null);
        resources.resolve(ResourceDictionary.FLUID, "water", 0, tag);
        int[] counts = new int[3];
        for (int id = 0; id < resources.size(); id++) {
            ResourceDictionary.Entry entry = resources.get(id);
            assertTrue(ResourceFilter.includes(ResourceFilter.ALL, entry.kind));
            assertNotEquals(ResourceFilter.includes(ResourceFilter.ITEMS, entry.kind), ResourceFilter.includes(ResourceFilter.FLUIDS, entry.kind));
            for (int filter = 0; filter < 3; filter++) if (ResourceFilter.includes(filter, entry.kind)) counts[filter]++;
        }
        assertArrayEquals(new int[] {5, 3, 2}, counts);
        assertEquals(5, resources.size());
    }
}
