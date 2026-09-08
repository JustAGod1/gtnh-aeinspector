package com.aeinspector.item;

import java.util.List;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import com.aeinspector.InspectorMod;
import com.google.common.base.Optional;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.util.IConfigManager;
import appeng.core.AEConfig;
import appeng.items.tools.powered.powersink.AEBasePoweredItem;
import appeng.util.ConfigManager;
import appeng.util.Platform;
import cofh.api.energy.IEnergyContainerItem;

/** Uses AE2's battery integrations and security-station encoding protocol. */
public final class ItemInspector extends AEBasePoweredItem implements IWirelessTermHandler, IEnergyContainerItem {
    public ItemInspector() {
        super(AEConfig.instance.wirelessTerminalBattery, Optional.absent());
        setUnlocalizedName("aeinspector.inspector");
        setTextureName("aeinspector:inspector");
        setCreativeTab(CreativeTabs.tabTools);
    }

    // Explicitly expose the RF API even when AE2 strips its optional RFItem interface from RedstoneFlux.
    @Override public int receiveEnergy(ItemStack stack, int amount, boolean simulate) {
        return InspectorRfCharging.receive(this, stack, amount, simulate);
    }
    @Override public int extractEnergy(ItemStack stack, int amount, boolean simulate) { return 0; }
    @Override public int getEnergyStored(ItemStack stack) {
        return InspectorRfCharging.toRf(getAECurrentPower(stack));
    }
    @Override public int getMaxEnergyStored(ItemStack stack) {
        return InspectorRfCharging.toRf(getAEMaxPower(stack));
    }

    @Override public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (!world.isRemote) {
            if (getEncryptionKey(stack).isEmpty()) player.addChatMessage(new ChatComponentTranslation("aeinspector.unlinked"));
            else if (!hasPower(player, 0.5, stack)) player.addChatMessage(new ChatComponentTranslation("aeinspector.no_power"));
            else player.openGui(InspectorMod.instance, 0, world, player.inventory.currentItem, 0, 0);
        }
        return stack;
    }

    @Override public boolean canHandle(ItemStack stack) { return stack != null && stack.getItem() == this; }
    @Override public boolean hasPower(EntityPlayer player, double amount, ItemStack stack) {
        return getAECurrentPower(stack) >= amount;
    }
    @Override public boolean usePower(EntityPlayer player, double amount, ItemStack stack) {
        return extractAEPower(stack, amount) >= amount - 0.5;
    }
    @Override public String getEncryptionKey(ItemStack stack) {
        return stack.hasTagCompound() ? stack.getTagCompound().getString("encryptionKey") : "";
    }
    @Override public void setEncryptionKey(ItemStack stack, String key, String name) {
        NBTTagCompound tag = Platform.openNbtData(stack);
        tag.setString("encryptionKey", key == null ? "" : key);
        tag.setString("name", name == null ? "" : name);
    }
    @Override public IConfigManager getConfigManager(ItemStack stack) {
        return new ConfigManager((manager, setting, value) -> manager.writeToNBT(Platform.openNbtData(stack)));
    }
    @Override public void addCheckedInformation(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced) {
        super.addCheckedInformation(stack, player, lines, advanced);
        lines.add(StatCollector.translateToLocal(getEncryptionKey(stack).isEmpty() ? "aeinspector.unlinked" : "aeinspector.linked"));
    }
}
