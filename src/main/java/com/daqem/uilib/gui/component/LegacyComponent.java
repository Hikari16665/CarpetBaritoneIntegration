package com.daqem.uilib.gui.component;

import net.minecraft.client.gui.GuiGraphics;

public interface LegacyComponent {
    int getX();
    int getY();
    int getWidth();
    int getHeight();
    void setX(int x);
    void setY(int y);
    void render(GuiGraphics graphics, int mouseX, int mouseY, float delta);
}
