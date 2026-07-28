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

import java.util.List;

/** Second stage: only widgets required by the selected command are shown. */
final class CommandParameterScreen extends AbstractScreen {
    private final Screen parent;
    private final BaritoneControlScreen.ControlCommand command;
    private int fakeIndex;
    private int playerIndex;
    private boolean xyz = true;
    private ButtonWidget fakeSelector;
    private ButtonWidget playerSelector;
    private ButtonWidget coordinateMode;
    private ButtonWidget submit;
    private EditBoxWidget xBox;
    private EditBoxWidget yBox;
    private EditBoxWidget zBox;
    private EditBoxWidget x2Box;
    private EditBoxWidget y2Box;
    private EditBoxWidget z2Box;
    private EditBoxWidget itemBox;
    private EditBoxWidget amountBox;
    private EditBoxWidget genericBox;
    private ItemComponent itemPreview;
    private int requestRetryTicks;

    CommandParameterScreen(
            Screen parent,
            BaritoneControlScreen.ControlCommand command) {
        super(Component.literal(command.title));
        this.parent = parent;
        this.command = command;
        setBackground(new DarkenedBackground());
    }

    @Override
    public void tick() {
        super.tick();
        if (!ClientControlOptions.received()
                && ++requestRetryTicks >= 40) {
            requestRetryTicks = 0;
            ClientControlOptions.request();
            refreshSelectors();
        }
    }

    @Override
    protected void init() {
        clear();
        ClientControlOptions.request();
        fakeIndex = ClientControlOptions.selectedFakeIndex();
        int left = width / 2 - 150;
        int top = height / 2 - 112;
        addComponent(new TextComponent(left, top,
                Component.literal(command.title)));

        fakeSelector = new ButtonWidget(left, top + 24, 300, 22,
                Component.empty(), button -> {
                    fakeIndex++;
                    rememberCurrentFake();
                    refreshSelectors();
                });
        addWidget(fakeSelector);
        int row = top + 58;

        switch (command.kind) {
            case POSITION -> initPosition(left, row);
            case SELECTION -> initSelection(left, row);
            case ITEM -> initItem(left, row);
            case PLAYER -> initPlayer(left, row);
            case ITEM_AMOUNT_PLAYER -> {
                initItem(left, row);
                amountBox = edit(left, row + 32, 80, "数量");
                amountBox.setValue("1");
                initPlayer(left + 88, row + 32);
            }
            case GENERIC -> genericBox = edit(
                    left, row, 300, command.hint);
            case NONE -> addComponent(new TextComponent(left, row,
                    Component.literal("此命令不需要额外参数")));
            default -> throw new IllegalStateException(
                    "Structured command opened in legacy parameter screen: "
                            + command.kind);
        }

        addWidget(new ButtonWidget(left, top + 178, 96, 24,
                Component.literal("返回命令菜单"),
                button -> minecraft.setScreen(parent)));
        submit = new ButtonWidget(left + 104, top + 178, 196, 24,
                Component.literal("发送命令"), button -> submit());
        addWidget(submit);
        refreshSelectors();
        super.init();
    }

    private void initPosition(int left, int row) {
        xBox = edit(left, row, 70, "X");
        yBox = edit(left + 76, row, 70, "Y");
        zBox = edit(left + 152, row, 70, "Z");
        if (command == BaritoneControlScreen.ControlCommand.GOTO) {
            coordinateMode = new ButtonWidget(left + 228, row, 72, 20,
                    Component.literal("XYZ"), button -> {
                        xyz = !xyz;
                        yBox.visible = xyz;
                        coordinateMode.setMessage(
                                Component.literal(xyz ? "XYZ" : "XZ"));
                    });
            addWidget(coordinateMode);
        }
        addWidget(new ButtonWidget(left, row + 30, 300, 22,
                Component.literal("填入我的位置"),
                button -> fillCurrentPosition()));
        fillCurrentPosition();
    }

    private void initSelection(int left, int row) {
        addComponent(new TextComponent(left, row - 12,
                Component.literal("选区点 1")));
        xBox = edit(left, row, 70, "X1");
        yBox = edit(left + 76, row, 70, "Y1");
        zBox = edit(left + 152, row, 70, "Z1");
        addWidget(new ButtonWidget(left + 228, row, 72, 20,
                Component.literal("当前位置"),
                button -> fillSelectionCorner(false)));

        addComponent(new TextComponent(left, row + 28,
                Component.literal("选区点 2")));
        x2Box = edit(left, row + 40, 70, "X2");
        y2Box = edit(left + 76, row + 40, 70, "Y2");
        z2Box = edit(left + 152, row + 40, 70, "Z2");
        addWidget(new ButtonWidget(left + 228, row + 40, 72, 20,
                Component.literal("当前位置"),
                button -> fillSelectionCorner(true)));
        fillSelectionCorner(false);
        fillSelectionCorner(true);
    }

    private void initItem(int left, int row) {
        itemBox = edit(left, row, 196,
                "物品/方块 ID（无需 minecraft:）");
        itemBox.setResponder(this::refreshItemPreview);
        addWidget(new ButtonWidget(left + 202, row, 47, 20,
                Component.literal("主手"), button -> useMainHand()));
        addWidget(new ButtonWidget(left + 253, row, 47, 20,
                Component.literal("选择"), button ->
                minecraft.setScreen(new ItemPickerScreen(this,
                        command == BaritoneControlScreen.ControlCommand.GET
                                || command
                                == BaritoneControlScreen.ControlCommand.FIND,
                        value -> {
                            itemBox.setValue(value);
                            refreshItemPreview(value);
                        }))));
        itemPreview = new ItemComponent(left + 278, row - 22,
                ItemStack.EMPTY, true);
        addComponent(itemPreview);
    }

    private void initPlayer(int left, int row) {
        playerSelector = new ButtonWidget(left, row,
                left == width / 2 - 150 ? 300 : 212, 20,
                Component.empty(), button -> {
                    playerIndex++;
                    refreshSelectors();
                });
        addWidget(playerSelector);
    }

    private EditBoxWidget edit(
            int x, int y, int width, String hint) {
        EditBoxWidget box = new EditBoxWidget(
                font, x, y, width, 20, Component.literal(hint));
        box.setHint(Component.literal(hint));
        addWidget(box);
        return box;
    }

    void optionsUpdated() {
        fakeIndex = ClientControlOptions.selectedFakeIndex();
        playerIndex = clamp(playerIndex,
                ClientControlOptions.onlinePlayers());
        refreshSelectors();
    }

    private void refreshSelectors() {
        List<String> fakes = ClientControlOptions.fakePlayers();
        String fake = !ClientControlOptions.supported()
                ? "服务端未安装匹配版本"
                : !ClientControlOptions.received()
                ? "正在从服务端获取…"
                : selected(fakes, fakeIndex, "正在获取假人…",
                        "服务端没有 Carpet 假人");
        fakeSelector.setMessage(Component.literal("假人选择器: " + fake));
        fakeSelector.active = !fakes.isEmpty();

        if (playerSelector != null) {
            List<String> players = ClientControlOptions.onlinePlayers();
            String player = !ClientControlOptions.received()
                    ? "正在从服务端获取…"
                    : selected(players, playerIndex,
                            "正在获取玩家…", "没有在线玩家");
            playerSelector.setMessage(
                    Component.literal("玩家选择器: " + player));
            playerSelector.active = !players.isEmpty();
        }
        submit.active = !fakes.isEmpty()
                && (playerSelector == null
                || !ClientControlOptions.onlinePlayers().isEmpty());
    }

    private static String selected(List<String> values, int index,
                                   String loading, String empty) {
        if (values.isEmpty()) return empty;
        return values.get(Math.floorMod(index, values.size()));
    }

    private static int clamp(int index, List<String> values) {
        return values.isEmpty() ? 0 : Math.floorMod(index, values.size());
    }

    private void fillCurrentPosition() {
        if (minecraft == null || minecraft.player == null) return;
        var pos = minecraft.player.blockPosition();
        xBox.setValue(Integer.toString(pos.getX()));
        yBox.setValue(Integer.toString(pos.getY()));
        zBox.setValue(Integer.toString(pos.getZ()));
    }

    private void fillSelectionCorner(boolean second) {
        if (minecraft == null || minecraft.player == null) return;
        var pos = minecraft.player.blockPosition();
        EditBoxWidget targetX = second ? x2Box : xBox;
        EditBoxWidget targetY = second ? y2Box : yBox;
        EditBoxWidget targetZ = second ? z2Box : zBox;
        targetX.setValue(Integer.toString(pos.getX()));
        targetY.setValue(Integer.toString(pos.getY()));
        targetZ.setValue(Integer.toString(pos.getZ()));
    }

    private void rememberCurrentFake() {
        List<String> fakes = ClientControlOptions.fakePlayers();
        if (!fakes.isEmpty()) {
            ClientControlOptions.rememberFake(
                    fakes.get(Math.floorMod(fakeIndex, fakes.size())));
        }
    }

    private void useMainHand() {
        if (minecraft == null || minecraft.player == null) return;
        ItemStack stack = minecraft.player.getMainHandItem();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        itemBox.setValue(id.getNamespace().equals("minecraft")
                ? id.getPath() : id.toString());
        itemPreview.setItemStack(stack.copy());
    }

    private void refreshItemPreview(String value) {
        ResourceLocation id = ResourceLocation.tryParse(
                value.contains(":") ? value : "minecraft:" + value);
        itemPreview.setItemStack(id == null ? ItemStack.EMPTY
                : new ItemStack(BuiltInRegistries.ITEM.getValue(id)));
    }

    private void submit() {
        List<String> fakes = ClientControlOptions.fakePlayers();
        if (fakes.isEmpty() || minecraft == null
                || minecraft.getConnection() == null) return;
        String args = buildArguments();
        String fake = fakes.get(Math.floorMod(fakeIndex, fakes.size()));
        rememberCurrentFake();
        if (command.kind == BaritoneControlScreen.Kind.SELECTION) {
            sendTell(fake, "pos1", value(xBox) + " " + value(yBox)
                    + " " + value(zBox));
            sendTell(fake, "pos2", value(x2Box) + " " + value(y2Box)
                    + " " + value(z2Box));
            sendTell(fake, "clean", "");
        } else {
            sendTell(fake, command.command, args);
        }
        onClose();
    }

    private void sendTell(String fake, String label, String arguments) {
        ClientCommandSender.send(fake, label, arguments);
    }

    private String buildArguments() {
        return switch (command.kind) {
            case NONE -> "";
            case POSITION -> xyz
                    ? value(xBox) + " " + value(yBox) + " " + value(zBox)
                    : value(xBox) + " " + value(zBox);
            case SELECTION -> "";
            case ITEM -> normalizedItem();
            case PLAYER -> selectedPlayer();
            case ITEM_AMOUNT_PLAYER -> normalizedItem() + " "
                    + value(amountBox) + " " + selectedPlayer();
            case GENERIC -> value(genericBox);
            default -> "";
        };
    }

    private String selectedPlayer() {
        List<String> players = ClientControlOptions.onlinePlayers();
        return players.isEmpty() ? ""
                : players.get(Math.floorMod(playerIndex, players.size()));
    }

    private String normalizedItem() {
        String value = value(itemBox);
        return value.startsWith("minecraft:")
                ? value.substring("minecraft:".length()) : value;
    }

    private static String value(EditBoxWidget box) {
        return box == null ? "" : box.getValue().trim();
    }

    @Override public void onClose() {
        minecraft.setScreen(parent);
    }
}
