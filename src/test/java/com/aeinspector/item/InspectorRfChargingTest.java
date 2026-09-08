package com.aeinspector.item;

import static org.junit.Assert.*;
import appeng.api.config.AccessRestriction;
import appeng.api.config.PowerUnits;
import appeng.api.implementations.items.IAEItemPowerStorage;
import cofh.api.energy.IEnergyContainerItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

public class InspectorRfChargingTest {
    private static final class Battery implements IAEItemPowerStorage {
        @Override public double getAEMaxPower(ItemStack stack) { return 100; }
        @Override public double getAECurrentPower(ItemStack stack) { return stack.getTagCompound().getDouble("charge"); }
        @Override public double injectAEPower(ItemStack stack, double amount) {
            double accepted = Math.min(amount, getAEMaxPower(stack) - getAECurrentPower(stack));
            stack.getTagCompound().setDouble("charge", getAECurrentPower(stack) + accepted);
            return amount - accepted;
        }
        @Override public double extractAEPower(ItemStack stack, double amount) { throw new AssertionError("Input only"); }
        @Override public AccessRestriction getPowerFlow(ItemStack stack) { return AccessRestriction.WRITE; }
    }

    @Test public void inspectorDeclaresRfInterfaceDirectly() {
        assertTrue(java.util.Arrays.asList(ItemInspector.class.getInterfaces()).contains(IEnergyContainerItem.class));
    }

    @Test public void chargerSimulationPartialFillAndBindingPreservation() {
        double oldAe = PowerUnits.AE.conversionRatio, oldRf = PowerUnits.RF.conversionRatio, oldEu = PowerUnits.EU.conversionRatio;
        try {
            PowerUnits.AE.conversionRatio = 1; PowerUnits.RF.conversionRatio = 0.5; PowerUnits.EU.conversionRatio = 2;
            Battery battery = new Battery(); ItemStack stack = new ItemStack(new Item());
            NBTTagCompound tag = new NBTTagCompound(); tag.setString("encryptionKey", "linked-network"); stack.setTagCompound(tag);
            assertEquals(80, InspectorRfCharging.receive(battery, stack, 80, true));
            assertEquals(0, battery.getAECurrentPower(stack), 0);
            assertEquals(80, InspectorRfCharging.receive(battery, stack, 80, false));
            assertEquals(40, battery.getAECurrentPower(stack), 0);
            assertEquals(80, InspectorRfCharging.toRf(battery.getAECurrentPower(stack)));
            assertEquals(120, InspectorRfCharging.receive(battery, stack, 10000, false));
            assertEquals(100, battery.getAECurrentPower(stack), 0);
            assertEquals(0, InspectorRfCharging.receive(battery, stack, 10000, false));
            assertEquals(0, InspectorRfCharging.receive(battery, stack, -1, false));
            assertEquals("linked-network", tag.getString("encryptionKey"));
        } finally {
            PowerUnits.AE.conversionRatio = oldAe; PowerUnits.RF.conversionRatio = oldRf; PowerUnits.EU.conversionRatio = oldEu;
        }
    }
}
