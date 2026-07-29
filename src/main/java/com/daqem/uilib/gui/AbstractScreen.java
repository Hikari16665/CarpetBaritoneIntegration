package com.daqem.uilib.gui;

import com.daqem.uilib.gui.component.LegacyComponent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class AbstractScreen extends Screen {
    private final List<LegacyComponent> components = new ArrayList<>();

    public AbstractScreen(Component title) {
        super(title);
    }

    public void setBackground(Object ignored) {
    }

    public void addComponent(LegacyComponent component) {
        components.add(component);
    }

    public <T extends AbstractWidget> T addWidget(T widget) {
        return addRenderableWidget(widget);
    }

    public void clear() {
        clearWidgets();
        components.clear();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                       float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        for (LegacyComponent component : components) {
            component.render(graphics, mouseX, mouseY, delta);
        }
        super.render(graphics, mouseX, mouseY, delta);
    }
}
