package com.daqem.uilib.gui.component.item;

import com.daqem.uilib.gui.component.LegacyComponent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

public final class ItemComponent implements LegacyComponent {
    private int x;
    private int y;
    private ItemStack stack;
    private boolean decorated;

    public ItemComponent(int x, int y, ItemStack stack) {
        this(x, y, stack, false);
    }

    public ItemComponent(int x, int y, ItemStack stack, boolean decorated) {
        this.x = x;
        this.y = y;
        this.stack = stack;
        this.decorated = decorated;
    }

    public ItemStack getItemStack() { return stack; }
    public void setItemStack(ItemStack stack) { this.stack = stack; }
    public boolean isDecorated() { return decorated; }
    public void setDecorated(boolean decorated) {
        this.decorated = decorated;
    }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return 16; }
    public int getHeight() { return 16; }
    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                       float delta) {
        if (stack == null || stack.isEmpty()) return;
        graphics.renderItem(stack, x, y);
        if (decorated) {
            graphics.renderItemDecorations(null, stack, x, y);
        }
    }
}
