package com.aeinspector.integration;

import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.aeinspector.core.ResourceDictionary;
import com.glodblock.github.loader.ItemAndBlockHolder;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAETagCompound;

/** Resolves full identity without constructing a Minecraft ItemStack on the usual AE2 path. */
public final class ResourceResolver {
    private final ResourceDictionary dictionary;
    private final Item drop;
    private final IdentityHashMap<Item, String> names = new IdentityHashMap<>();

    public ResourceResolver(ResourceDictionary dictionary) { this(dictionary, ItemAndBlockHolder.DROP); }
    ResourceResolver(ResourceDictionary dictionary, Item drop) { this.dictionary = dictionary; this.drop = drop; }

    /** AE2FC's newStack drops FluidStack.tag in 1.4.120; preserve it explicitly for filter matching. */
    static ItemStack fluidRepresentation(Item drop, FluidStack fluid) {
        if (fluid == null) return null;
        return fluidRepresentation(drop, fluid.getFluid().getName(), fluid.tag);
    }
    static ItemStack fluidRepresentation(Item drop, String name, NBTTagCompound fluidTag) {
        ItemStack result = new ItemStack(drop, 1);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Fluid", name);
        if (fluidTag != null) tag.setTag("FluidTag", fluidTag.copy());
        result.setTagCompound(tag);
        return result;
    }

    public int resolve(IAEStack<?> stack) {
        NBTTagCompound tag = tag(stack.getTagCompound());
        if (stack instanceof IAEItemStack) {
            IAEItemStack item = (IAEItemStack) stack;
            return resolveItem(item.getItem(), item.getItemDamage(), tag);
        }
        if (stack instanceof IAEFluidStack) {
            IAEFluidStack fluid = (IAEFluidStack) stack;
            return dictionary.resolve(ResourceDictionary.FLUID, fluid.getFluid().getName(), 0, tag);
        }
        throw new IllegalArgumentException("Unsupported AE storage channel");
    }

    public int resolve(ItemStack stack) {
        return resolveItem(stack.getItem(), stack.getItemDamage(), stack.getTagCompound());
    }

    private int resolveItem(Item item, int metadata, NBTTagCompound tag) {
        // Only the lossless, canonical drop representation is normalized. Any extra NBT remains an item.
        if (item == drop && metadata == 0 && tag != null && canonicalDrop(tag)) {
            String name = tag.getString("Fluid");
            Fluid fluid = FluidRegistry.getFluid(name);
            if (fluid != null && fluid.getName().equals(name)) {
                NBTTagCompound fluidTag = tag.hasKey("FluidTag", 10) ? tag.getCompoundTag("FluidTag") : null;
                return dictionary.resolve(ResourceDictionary.FLUID, name, 0, fluidTag);
            }
        }
        String name = names.get(item);
        if (name == null) {
            name = (String) Item.itemRegistry.getNameForObject(item);
            if (name == null) throw new IllegalArgumentException("Unregistered item");
            names.put(item, name);
        }
        return dictionary.resolve(ResourceDictionary.ITEM, name, metadata, tag);
    }

    static boolean canonicalDrop(NBTTagCompound tag) {
        if (!tag.hasKey("Fluid", 8)) return false;
        Set<?> keys = tag.func_150296_c();
        return keys.size() == 1 || (keys.size() == 2 && tag.hasKey("FluidTag", 10));
    }

    private static NBTTagCompound tag(IAETagCompound tag) {
        if (tag == null) return null;
        return tag instanceof NBTTagCompound ? (NBTTagCompound) tag : tag.getNBTTagCompoundCopy();
    }
}
