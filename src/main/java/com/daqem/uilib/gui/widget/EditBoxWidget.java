package com.daqem.uilib.gui.widget;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

public class EditBoxWidget extends EditBox {
    public EditBoxWidget(Font font, int x, int y,
                         int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }
}
