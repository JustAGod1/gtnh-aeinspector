package com.aeinspector.item;

import java.util.Arrays;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.nbt.NBTTagCompound;

/** Upgrade retains an independent copy of the terminal's complete tag, including charge and link. */
public final class InspectorRecipe extends ShapelessRecipes {
    private final ItemStack terminal;

    public InspectorRecipe(ItemStack result, ItemStack terminal, ItemStack processor) {
        super(result, Arrays.asList(terminal, processor));
        this.terminal = terminal.copy();
    }

    @Override public ItemStack getCraftingResult(InventoryCrafting inventory) {
        ItemStack result = getRecipeOutput().copy();
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack input = inventory.getStackInSlot(i);
            if (input != null && input.getItem() == terminal.getItem() && input.hasTagCompound()) {
                result.setTagCompound((NBTTagCompound) input.getTagCompound().copy());
                break;
            }
        }
        return result;
    }
}
