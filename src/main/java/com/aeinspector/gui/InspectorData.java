package com.aeinspector.gui;

import java.nio.ByteBuffer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import com.aeinspector.core.ResourceDictionary;
import com.glodblock.github.common.item.ItemFluidDrop;

/** Display descriptions and packet arrays; exact keys remain in the server dictionary. */
public final class InspectorData {
    private InspectorData() {}

    static NBTTagCompound resource(ResourceDictionary.Entry entry, String displayName) {
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("id", entry.id); result.setInteger("meta", entry.metadata);
        result.setString("registry", entry.name); result.setString("name", displayName);
        result.setBoolean("fluid", entry.kind == ResourceDictionary.FLUID);
        // Display rows carry stable IDs, not copies of potentially megabyte-sized resource keys.
        return result;
    }

    static String name(ResourceDictionary.Entry entry) {
        if (entry.kind == ResourceDictionary.FLUID) {
            Fluid fluid = FluidRegistry.getFluid(entry.name);
            return fluid == null ? entry.name : fluid.getLocalizedName(new FluidStack(fluid, 1, entry.copyTag()));
        }
        Item item = (Item) Item.itemRegistry.getObject(entry.name);
        if (item == null) return entry.name;
        ItemStack stack = new ItemStack(item, 1, entry.metadata);
        stack.setTagCompound(entry.copyTag());
        return stack.getDisplayName();
    }

    public static ItemStack icon(NBTTagCompound resource) {
        if (resource.getBoolean("fluid")) {
            Fluid fluid = FluidRegistry.getFluid(resource.getString("registry"));
            return fluid == null ? null : ItemFluidDrop.newDisplayStack(new FluidStack(fluid, 1));
        }
        Item item = (Item) Item.itemRegistry.getObject(resource.getString("registry"));
        return item == null ? null : new ItemStack(item, 1, resource.getInteger("meta"));
    }
    public static byte[] longs(long[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 8);
        for (long value : values) bytes.putLong(value);
        return bytes.array();
    }
    public static long[] longs(byte[] bytes) {
        if (bytes.length % 8 != 0 || bytes.length > 300 * 8) throw new IllegalArgumentException("Invalid graph array");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long[] values = new long[bytes.length / 8];
        for (int i = 0; i < values.length; i++) values[i] = buffer.getLong();
        return values;
    }
}
