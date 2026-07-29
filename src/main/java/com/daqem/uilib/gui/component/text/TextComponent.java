package com.daqem.uilib.gui.component.text;

import com.daqem.uilib.gui.component.LegacyComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class TextComponent implements LegacyComponent {
    private int x;
    private int y;
    private final Component text;
    private final int color;

    public TextComponent(int x, int y, Component text) {
        this(x, y, text, 0xFFFFFFFF);
    }

    public TextComponent(int x, int y, Component text, int color) {
        this.x = x;
        this.y = y;
        this.text = text;
        this.color = color;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() {
        return Minecraft.getInstance().font.width(text);
    }
    public int getHeight() { return 9; }
    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                       float delta) {
        graphics.drawString(Minecraft.getInstance().font,
                text, x, y, color);
    }
}
