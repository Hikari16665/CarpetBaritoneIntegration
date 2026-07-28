package me.nuoyuan.carpetbaritoneintegration.client;

import com.daqem.uilib.gui.AbstractScreen;
import com.daqem.uilib.gui.background.DarkenedBackground;
import com.daqem.uilib.gui.component.item.ItemComponent;
import com.daqem.uilib.gui.component.text.TextComponent;
import com.daqem.uilib.gui.widget.ButtonWidget;
import com.daqem.uilib.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Multi-row editor for mine targets and collectItem requirements. */
final class MultiItemCommandScreen extends AbstractScreen {
    record Entry(String id, int amount) { }

    private final Screen parent;
    private final BaritoneControlScreen.ControlCommand command;
    private final List<Entry> entries = new ArrayList<>();
    private int fakeIndex;
    private int playerIndex;
    private ButtonWidget fakeSelector;
    private ButtonWidget playerSelector;
    private int firstVisibleRow;
    private int listLeft;
    private int listTop;
    private static final int VISIBLE_ROWS = 5;

    MultiItemCommandScreen(Screen parent,
                           BaritoneControlScreen.ControlCommand command) {
        this(parent, command, List.of());
    }

    MultiItemCommandScreen(Screen parent,
                           BaritoneControlScreen.ControlCommand command,
                           List<Entry> initial) {
        super(Component.literal(command.title));
        this.parent = parent;
        this.command = command;
        entries.addAll(initial);
        if (entries.isEmpty()) entries.add(new Entry("", 1));
        setBackground(new DarkenedBackground());
    }

    @Override
    protected void init() {
        clear();
        ClientControlOptions.request();
        fakeIndex = ClientControlOptions.selectedFakeIndex();
        int left = width / 2 - 180;
        int top = height / 2 - 128;
        addComponent(new TextComponent(left, top,
                Component.literal(command.title)));
        fakeSelector = new ButtonWidget(left, top + 20, 360, 20,
                Component.empty(), button -> {
            fakeIndex++;
            refreshSelectors();
        });
        addWidget(fakeSelector);

        listLeft = left;
        listTop = top + 46;
        firstVisibleRow = Math.max(0, Math.min(firstVisibleRow,
                Math.max(0, entries.size() - VISIBLE_ROWS)));
        int end = Math.min(entries.size(),
                firstVisibleRow + VISIBLE_ROWS);
        for (int index = firstVisibleRow; index < end; index++) {
            final int rowIndex = index;
            Entry entry = entries.get(index);
            int rowY = listTop + (index - firstVisibleRow) * 28;
            ItemComponent icon = new ItemComponent(left + 2, rowY + 5,
                    stack(entry.id()), true);
            addComponent(icon);
            EditBoxWidget id = new EditBoxWidget(font, left + 24, rowY + 3,
                    command.kind == BaritoneControlScreen.Kind.MULTI_BLOCK
                            ? 190 : 138, 20, Component.literal("物品 ID"));
            id.setHint(Component.literal("物品 ID"));
            id.setValue(entry.id());
            id.setResponder(value -> {
                update(rowIndex, value, null);
                icon.setItemStack(stack(value));
            });
            addWidget(id);
            int x = command.kind == BaritoneControlScreen.Kind.MULTI_BLOCK
                    ? left + 220 : left + 162;
            if (command.kind
                    == BaritoneControlScreen.Kind.MULTI_ITEM_AMOUNT_PLAYER) {
                EditBoxWidget amount = new EditBoxWidget(
                        font, x, rowY + 3, 52, 20,
                        Component.literal("数量"));
                amount.setValue(Integer.toString(entry.amount()));
                amount.setResponder(value -> update(rowIndex, null,
                        parseAmount(value)));
                addWidget(amount);
                x += 58;
            }
            addWidget(new ButtonWidget(x, rowY + 3, 58, 20,
                    Component.literal("选择"), button ->
                    minecraft.setScreen(new ItemPickerScreen(this,
                            command.kind
                                    == BaritoneControlScreen.Kind.MULTI_BLOCK,
                            value -> {
                                update(rowIndex, value, null);
                                rebuild();
                            }))));
            addWidget(new ButtonWidget(x + 62, rowY + 3, 58, 20,
                    Component.literal("删除"), button -> {
                entries.remove(rowIndex);
                if (entries.isEmpty()) entries.add(new Entry("", 1));
                firstVisibleRow = Math.min(firstVisibleRow,
                        Math.max(0, entries.size() - VISIBLE_ROWS));
                rebuild();
            }));
        }
        addComponent(new TextComponent(left, top + 182,
                Component.literal("滚轮浏览："
                        + (firstVisibleRow + 1) + "-"
                        + end + " / " + entries.size())));
        addWidget(new ButtonWidget(left, top + 194, 92, 20,
                Component.literal("添加选项 (" + entries.size() + ")"),
                button -> {
            entries.add(new Entry("", 1));
            firstVisibleRow = Math.max(
                    0, entries.size() - VISIBLE_ROWS);
            rebuild();
        }));
        addWidget(new ButtonWidget(left + 98, top + 194, 92, 20,
                Component.literal("添加主手"), button -> {
            if (minecraft.player == null) return;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(
                    minecraft.player.getMainHandItem().getItem());
            entries.add(new Entry(normalize(id.toString()), 1));
            firstVisibleRow = Math.max(
                    0, entries.size() - VISIBLE_ROWS);
            rebuild();
        }));
        if (command.kind
                == BaritoneControlScreen.Kind.MULTI_ITEM_AMOUNT_PLAYER) {
            playerSelector = new ButtonWidget(left + 196, top + 194,
                    164, 20, Component.empty(), button -> {
                playerIndex++;
                refreshSelectors();
            });
            addWidget(playerSelector);
        }
        addWidget(new ButtonWidget(left, top + 222, 112, 22,
                Component.literal("返回"), button ->
                minecraft.setScreen(parent)));
        addWidget(new ButtonWidget(left + 118, top + 222, 242, 22,
                Component.literal("发送命令"), button -> submit()));
        refreshSelectors();
        super.init();
    }

    private void update(int index, String id, Integer amount) {
        if (index < 0 || index >= entries.size()) return;
        Entry old = entries.get(index);
        entries.set(index, new Entry(
                id == null ? old.id() : id,
                amount == null ? old.amount() : amount));
    }

    private void refreshSelectors() {
        List<String> fakes = ClientControlOptions.fakePlayers();
        fakeSelector.setMessage(Component.literal("假人选择器: "
                + selected(fakes, fakeIndex, "没有可用假人")));
        fakeSelector.active = !fakes.isEmpty();
        if (playerSelector != null) {
            List<String> players = ClientControlOptions.onlinePlayers();
            playerSelector.setMessage(Component.literal("接收玩家: "
                    + selected(players, playerIndex, "没有在线玩家")));
            playerSelector.active = !players.isEmpty();
        }
    }

    private void submit() {
        List<String> fakes = ClientControlOptions.fakePlayers();
        if (fakes.isEmpty() || minecraft.getConnection() == null) return;
        List<Entry> valid = entries.stream()
                .filter(entry -> !entry.id().isBlank()).toList();
        if (valid.isEmpty()) return;
        String args = valid.stream().map(entry ->
                normalize(entry.id())
                        + (command.kind == BaritoneControlScreen.Kind.MULTI_BLOCK
                        ? "" : " " + Math.max(1, entry.amount())))
                .reduce((a, b) -> a + " " + b).orElse("");
        if (playerSelector != null) {
            List<String> players = ClientControlOptions.onlinePlayers();
            if (players.isEmpty()) return;
            args += " " + selected(players, playerIndex, "");
        }
        String fake = selected(fakes, fakeIndex, "");
        ClientControlOptions.rememberFake(fake);
        ClientCommandSender.send(fake, command.command, args);
        minecraft.setScreen(parent);
    }

    private void rebuild() {
        MultiItemCommandScreen replacement = new MultiItemCommandScreen(
                parent, command, List.copyOf(entries));
        replacement.playerIndex = playerIndex;
        replacement.firstVisibleRow = firstVisibleRow;
        minecraft.setScreen(replacement);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount,
                                 double verticalAmount) {
        if (mouseX >= listLeft && mouseX < listLeft + 360
                && mouseY >= listTop
                && mouseY < listTop + VISIBLE_ROWS * 28) {
            int previous = firstVisibleRow;
            if (verticalAmount > 0) firstVisibleRow--;
            if (verticalAmount < 0) firstVisibleRow++;
            firstVisibleRow = Math.max(0, Math.min(firstVisibleRow,
                    Math.max(0, entries.size() - VISIBLE_ROWS)));
            if (previous != firstVisibleRow) {
                rebuild();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY,
                horizontalAmount, verticalAmount);
    }

    private static int parseAmount(String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String selected(List<String> values, int index,
                                   String empty) {
        return values.isEmpty() ? empty
                : values.get(Math.floorMod(index, values.size()));
    }

    private static String normalize(String id) {
        String value = id.trim();
        return value.startsWith("minecraft:")
                ? value.substring("minecraft:".length()) : value;
    }

    private static ItemStack stack(String value) {
        ResourceLocation id = ResourceLocation.tryParse(
                value.contains(":") ? value : "minecraft:" + value);
        return id == null ? ItemStack.EMPTY
                : new ItemStack(BuiltInRegistries.ITEM.getValue(id));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
