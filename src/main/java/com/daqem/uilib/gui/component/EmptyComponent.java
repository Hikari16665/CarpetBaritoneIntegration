package com.daqem.uilib.gui.component;

import com.daqem.uilib.gui.widget.ButtonWidget;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

public final class EmptyComponent implements LegacyComponent {
    private int x;
    private int y;
    private final int width;
    private final int height;
    private final List<ButtonWidget> widgets = new ArrayList<>();
    private final List<LegacyComponent> components = new ArrayList<>();

    public EmptyComponent(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void addWidget(ButtonWidget widget) {
        widgets.add(widget);
    }

    public void addComponent(LegacyComponent component) {
        components.add(component);
    }

    public List<ButtonWidget> widgets() { return widgets; }
    public List<LegacyComponent> components() { return components; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                       float delta) {
        for (LegacyComponent component : components) {
            component.render(graphics, mouseX, mouseY, delta);
        }
        for (ButtonWidget widget : widgets) {
            widget.render(graphics, mouseX, mouseY, delta);
        }
    }
}
