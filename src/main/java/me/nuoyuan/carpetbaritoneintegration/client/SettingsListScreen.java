package me.nuoyuan.carpetbaritoneintegration.client;

import com.daqem.uilib.gui.AbstractScreen;
import com.daqem.uilib.gui.background.DarkenedBackground;
import com.daqem.uilib.gui.component.EmptyComponent;
import com.daqem.uilib.gui.component.text.TextComponent;
import com.daqem.uilib.gui.widget.ButtonWidget;
import com.daqem.uilib.gui.widget.EditBoxWidget;
import com.daqem.uilib.gui.widget.ScrollContainerWidget;
import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsPayload.SettingOption;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Searchable catalogue; each setting opens its type-specific editor. */
final class SettingsListScreen extends AbstractScreen {
    private final Screen parent;
    private final String filter;
    private int fakeIndex;
    private ButtonWidget fakeSelector;
    private EditBoxWidget search;
    private boolean awaitingInitialOptions;

    SettingsListScreen(Screen parent) {
        this(parent, "");
    }

    private SettingsListScreen(Screen parent, String filter) {
        super(Component.literal("Baritone 设置"));
        this.parent = parent;
        this.filter = filter;
        setBackground(new DarkenedBackground());
    }

    @Override
    protected void init() {
        clear();
        awaitingInitialOptions = ClientControlOptions.settings().isEmpty();
        if (awaitingInitialOptions) {
            ClientControlOptions.request();
        }
        fakeIndex = ClientControlOptions.selectedFakeIndex();
        int left = width / 2 - 190;
        int top = Math.max(12, height / 2 - 135);
        addComponent(new TextComponent(left, top,
                Component.literal("Baritone 设置（按类型编辑）")));
        fakeSelector = new ButtonWidget(left, top + 20, 380, 20,
                Component.empty(), button -> {
                    fakeIndex++;
                    rememberFake();
                    refreshFake();
                });
        addWidget(fakeSelector);
        search = new EditBoxWidget(font, left, top + 44, 300, 20,
                Component.literal("搜索设置"));
        search.setHint(Component.literal("搜索设置名称"));
        search.setValue(filter);
        addWidget(search);
        addWidget(new ButtonWidget(left + 304, top + 44, 76, 20,
                Component.literal("筛选"), button ->
                minecraft.gui.setScreen(new SettingsListScreen(
                        parent, search.getValue().trim()))));
        addSettingsScroll(filter);
        addWidget(new ButtonWidget(left, top + 244, 380, 22,
                Component.literal("返回命令菜单"),
                button -> minecraft.gui.setScreen(parent)));
        refreshFake();
        super.init();
    }

    protected void addSettingsScroll(String filter) {
        int left = width / 2 - 190;
        int top = Math.max(12, height / 2 - 135);
        ScrollContainerWidget scroll =
                new ScrollContainerWidget(380, 174, 3);
        scroll.setX(left);
        scroll.setY(top + 68);
        String normalized = filter.toLowerCase(Locale.ROOT);
        for (SettingOption option : ClientControlOptions.settings()) {
            if (!option.name().toLowerCase(Locale.ROOT)
                    .contains(normalized)) continue;
            EmptyComponent row = new EmptyComponent(0, 0, 366, 24);
            String value = option.value().length() > 32
                    ? option.value().substring(0, 29) + "..."
                    : option.value();
            row.addWidget(new ButtonWidget(0, 0, 366, 22,
                    Component.literal(option.name() + " = " + value),
                    button -> minecraft.gui.setScreen(
                            new SettingEditorScreen(this, option,
                                    fakeName(fakeIndex)))));
            scroll.addComponent(row);
        }
        addWidget(scroll);
    }

    private String fakeName(int index) {
        List<String> fakes = ClientControlOptions.fakePlayers();
        return fakes.isEmpty() ? ""
                : fakes.get(Math.floorMod(index, fakes.size()));
    }

    private void rememberFake() {
        String fake = fakeName(fakeIndex);
        if (!fake.isEmpty()) ClientControlOptions.rememberFake(fake);
    }

    private void refreshFake() {
        List<String> fakes = ClientControlOptions.fakePlayers();
        String fake = fakeName(fakeIndex);
        fakeSelector.setMessage(Component.literal("假人选择器: "
                + (fake.isEmpty() ? "没有可用假人" : fake)));
        fakeSelector.active = !fakes.isEmpty();
    }

    void optionsUpdated() {
        if (!awaitingInitialOptions) return;
        awaitingInitialOptions = false;
        minecraft.gui.setScreen(new SettingsListScreen(parent, filter));
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

}
