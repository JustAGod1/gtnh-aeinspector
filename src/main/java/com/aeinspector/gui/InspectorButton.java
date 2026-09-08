package com.aeinspector.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import org.lwjgl.opengl.GL11;

final class InspectorButton extends GuiButton {
    boolean selected;
    InspectorButton(int id, int x, int y, int width, int height, String text) { super(id, x, y, width, height, text); }
    @Override public void drawButton(Minecraft mc, int mx, int my) {
        if (!visible) return;
        boolean hover = mx >= xPosition && mx < xPosition + width && my >= yPosition && my < yPosition + height;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_DEPTH_TEST);
            int border = selected ? 0xff63cdb4 : hover && enabled ? 0xff7798a8 : 0xff344651;
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, border);
            drawRect(xPosition + 1, yPosition + 1, xPosition + width - 1, yPosition + height - 1,
                    selected ? 0xff284c48 : hover && enabled ? 0xff30434f : 0xff1c2b35);
            drawCenteredString(mc.fontRenderer, mc.fontRenderer.trimStringToWidth(displayString, width - 6), xPosition + width / 2, yPosition + (height - 8) / 2,
                    selected ? 0x9ff3d7 : enabled ? 0xd9e4e9 : 0x657983);
        } finally { GL11.glPopAttrib(); }
    }
}
