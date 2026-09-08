package com.aeinspector.item;

import appeng.api.config.PowerUnits;
import appeng.api.implementations.items.IAEItemPowerStorage;
import net.minecraft.item.ItemStack;

/** RF input into the existing AE battery: no second charge tag and no Ender IO dependency. */
final class InspectorRfCharging {
    private InspectorRfCharging() {}

    static int toRf(double ae) {
        double rf = PowerUnits.AE.convertTo(PowerUnits.RF, ae);
        return Double.isFinite(rf) && rf > 0 ? (int) Math.min(Integer.MAX_VALUE, rf) : 0;
    }

    static int receive(IAEItemPowerStorage battery, ItemStack stack, int offered, boolean simulate) {
        if (stack == null || offered <= 0) return 0;
        int accepted = Math.min(offered, toRf(battery.getAEMaxPower(stack) - battery.getAECurrentPower(stack)));
        if (accepted <= 0 || simulate) return accepted;
        double ae = PowerUnits.RF.convertTo(PowerUnits.AE, accepted);
        if (!Double.isFinite(ae) || ae <= 0) return 0;
        double remainder = battery.injectAEPower(stack, ae);
        return Math.max(0, accepted - (int) Math.ceil(PowerUnits.AE.convertTo(PowerUnits.RF, remainder)));
    }
}
