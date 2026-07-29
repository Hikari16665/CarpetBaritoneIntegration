package com.daqem.uilib.gui.widget;

import com.daqem.uilib.gui.component.EmptyComponent;
import com.daqem.uilib.gui.component.LegacyComponent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ScrollContainerWidget extends AbstractWidget {
    private final int spacing;
    private final List<LegacyComponent> components = new ArrayList<>();
    private final Map<LegacyComponent, Integer> componentX = new IdentityHashMap<>();
    private final Map<ButtonWidget, int[]> buttonOffsets = new IdentityHashMap<>();
    private final Map<LegacyComponent, int[]> childOffsets = new IdentityHashMap<>();
    private double scroll;

    public ScrollContainerWidget(int width, int height, int spacing) {
        super(0, 0, width, height, Component.empty());
        this.spacing = spacing;
    }

    public ScrollContainerWidget(int width, int height) {
        this(width, height, 2);
    }

    public void addComponent(LegacyComponent component) {
        components.add(component);
        componentX.put(component, component.getX());
        if (component instanceof EmptyComponent empty) {
            for (ButtonWidget button : empty.widgets()) {
                buttonOffsets.put(button, new int[]{button.getX(), button.getY()});
            }
            for (LegacyComponent child : empty.components()) {
                childOffsets.put(child, new int[]{child.getX(), child.getY()});
            }
        }
    }

    public void clear() {
        components.clear();
        componentX.clear();
        buttonOffsets.clear();
        childOffsets.clear();
        scroll = 0;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX,
                                int mouseY, float delta) {
        graphics.enableScissor(getX(), getY(),
                getX() + width, getY() + height);
        int cursor = getY() - (int) scroll;
        for (LegacyComponent component : components) {
            component.setX(getX() + componentX.getOrDefault(component, 0));
            component.setY(cursor);
            if (component instanceof EmptyComponent empty) {
                for (ButtonWidget button : empty.widgets()) {
                    int[] offset = buttonOffsets.get(button);
                    button.setX(component.getX() + offset[0]);
                    button.setY(cursor + offset[1]);
                }
                for (LegacyComponent child : empty.components()) {
                    int[] offset = childOffsets.get(child);
                    child.setX(component.getX() + offset[0]);
                    child.setY(cursor + offset[1]);
                }
            }
            component.render(graphics, mouseX, mouseY, delta);
            cursor += component.getHeight() + spacing;
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontal, double vertical) {
        int content = components.stream()
                .mapToInt(value -> value.getHeight() + spacing).sum();
        scroll = Math.max(0, Math.min(
                Math.max(0, content - height), scroll - vertical * 16));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        for (LegacyComponent component : components) {
            if (component instanceof EmptyComponent empty) {
                for (ButtonWidget widget : empty.widgets()) {
                    if (widget.mouseClicked(mouseX, mouseY, button)) {
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (LegacyComponent component : components) {
            if (component instanceof EmptyComponent empty) {
                for (ButtonWidget widget : empty.widgets()) {
                    if (widget.mouseReleased(mouseX, mouseY, button)) {
                        return true;
                    }
                }
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected void updateWidgetNarration(
            net.minecraft.client.gui.narration.NarrationElementOutput out) {
    }
}
