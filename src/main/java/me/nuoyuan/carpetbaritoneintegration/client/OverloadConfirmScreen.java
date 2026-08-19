package me.nuoyuan.carpetbaritoneintegration.client;

import com.daqem.uilib.gui.AbstractScreen;
import com.daqem.uilib.gui.background.DarkenedBackground;
import com.daqem.uilib.gui.component.text.TextComponent;
import com.daqem.uilib.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit administrator confirmation for the global privileged mode. */
final class OverloadConfirmScreen extends AbstractScreen {
    private final Screen parent;
    private ButtonWidget enable;
    private ButtonWidget disable;

    OverloadConfirmScreen(Screen parent) {
        super(Component.literal("OVERLOAD MODE · 超载模式"));
        this.parent = parent;
        setBackground(new DarkenedBackground());
    }

    @Override
    protected void init() {
        clear();
        ClientControlOptions.request();
        int panelWidth = Math.min(520, Math.max(340, width - 40));
        int left = (width - panelWidth) / 2;
        int top = Math.max(18, (height - 212) / 2);
        addComponent(new TextComponent(left, top,
                Component.literal("OVERLOAD MODE · 超载模式")));
        addComponent(new TextComponent(left, top + 32,
                Component.literal("会让假人尽最快速度完成任务，并会授权假人")));
        addComponent(new TextComponent(left, top + 50,
                Component.literal("大于普通玩家的权限，但所有资源会正常消耗。")));
        addComponent(new TextComponent(left, top + 68,
                Component.literal("为保证游戏公平性，此功能请在考虑后由管理员开启。")));
        addComponent(new TextComponent(left, top + 86,
                Component.literal("移动会先规划并瞬间结算完整路径，再传送至终点。")));
        enable = new ButtonWidget(left, top + 122,
                (panelWidth - 8) / 2, 24,
                Component.literal("开启"), button ->
                ClientControlOptions.setOverload(true));
        disable = new ButtonWidget(left + (panelWidth + 8) / 2,
                top + 122, (panelWidth - 8) / 2, 24,
                Component.literal("关闭"), button ->
                ClientControlOptions.setOverload(false));
        addWidget(enable);
        addWidget(disable);
        addWidget(new ButtonWidget(left, top + 158, panelWidth, 22,
                Component.literal("返回"), button ->
                minecraft.setScreen(parent)));
        refresh();
        super.init();
    }

    void stateUpdated() {
        refresh();
    }

    private void refresh() {
        if (enable == null || disable == null) return;
        boolean allowed = ClientControlOptions.canManageOverload();
        enable.active = allowed
                && !ClientControlOptions.overloadEnabled();
        disable.active = allowed
                && ClientControlOptions.overloadEnabled();
    }
}
