package me.nuoyuan.carpetbaritoneintegration.client;

import com.daqem.uilib.gui.AbstractScreen;
import com.daqem.uilib.gui.background.DarkenedBackground;
import com.daqem.uilib.gui.component.item.ItemComponent;
import com.daqem.uilib.gui.component.text.TextComponent;
import com.daqem.uilib.gui.widget.ButtonWidget;
import com.daqem.uilib.gui.widget.EditBoxWidget;
import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsPayload.WaypointOption;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.List;

/** Heuristic, command-specific replacement for the old generic chat box. */
final class StructuredCommandScreen extends AbstractScreen {
    private final Screen parent;
    private final BaritoneControlScreen.ControlCommand command;
    private int fakeIndex;
    private ButtonWidget fakeButton;
    private ButtonWidget modeButton;
    private EditBoxWidget a;
    private EditBoxWidget b;
    private EditBoxWidget c;
    private EditBoxWidget d;
    private EditBoxWidget e;
    private EditBoxWidget f;
    private EditBoxWidget g;
    private ItemComponent preview;
    private int mode;
    private boolean optionalEnabled;
    private List<String> modes = List.of();
    private int waypointIndex;

    StructuredCommandScreen(
            Screen parent, BaritoneControlScreen.ControlCommand command) {
        super(Component.literal(command.title));
        this.parent = parent;
        this.command = command;
        setBackground(new DarkenedBackground());
    }

    @Override
    protected void init() {
        clear();
        ClientControlOptions.request();
        fakeIndex = ClientControlOptions.selectedFakeIndex();
        int left = width / 2 - 160;
        int top = height / 2 - 112;
        addComponent(new TextComponent(left, top,
                Component.literal(command.title)));
        fakeButton = new ButtonWidget(left, top + 22, 320, 20,
                Component.empty(), button -> {
                    fakeIndex++;
                    refreshFake();
                });
        addWidget(fakeButton);
        int row = top + 54;
        switch (command.kind) {
            case INTEGER -> a = edit(left, row, 320, command.hint, "1");
            case OPTIONAL_INTEGER -> initOptionalInteger(left, row);
            case OPTIONAL_XZ -> initOptionalXZ(left, row);
            case TOGGLE -> initModes(left, row, "on", "off");
            case TUNNEL -> {
                a = edit(left, row, 100, "高度", "2");
                b = edit(left + 110, row, 100, "宽度", "1");
                c = edit(left + 220, row, 100, "长度", "64");
            }
            case GOAL -> initGoal(left, row);
            case POSITION_ITEM -> initPositionItem(left, row);
            case TRASH -> initTrash(left, row);
            case SELECTION_ACTION -> initModes(left, row,
                    "list", "clear");
            case WAYPOINT -> initWaypoint(left, row);
            case CACHE -> initModes(left, row,
                    "status", "repack", "save", "reload");
            case COMMAND_PICKER -> initCommandPicker(left, row);
            case BUILD -> initBuild(left, row);
            default -> { }
        }
        addWidget(new ButtonWidget(left, top + 178, 100, 22,
                Component.literal("返回"), button ->
                minecraft.setScreen(parent)));
        addWidget(new ButtonWidget(left + 108, top + 178, 212, 22,
                Component.literal("执行"), button -> submit()));
        refreshFake();
        super.init();
    }

    private void initOptionalInteger(int x, int y) {
        modes = List.of("使用默认值", "指定数值");
        modeButton = new ButtonWidget(x, y, 140, 20,
                Component.empty(), button -> {
                    optionalEnabled = !optionalEnabled;
                    refreshMode();
                });
        addWidget(modeButton);
        a = edit(x + 148, y, 172, command.hint, "0");
        a.visible = optionalEnabled;
        refreshMode();
    }

    private void initOptionalXZ(int x, int y) {
        modes = List.of("从当前位置探索", "指定起始 XZ");
        modeButton = new ButtonWidget(x, y, 320, 20,
                Component.empty(), button -> {
                    optionalEnabled = !optionalEnabled;
                    a.visible = optionalEnabled;
                    b.visible = optionalEnabled;
                    refreshMode();
                });
        addWidget(modeButton);
        a = edit(x, y + 28, 154, "X", currentX());
        b = edit(x + 166, y + 28, 154, "Z", currentZ());
        a.visible = b.visible = optionalEnabled;
        refreshMode();
    }

    private void initGoal(int x, int y) {
        initModes(x, y, "query", "set", "clear");
        a = edit(x, y + 28, 100, "X", currentX());
        b = edit(x + 110, y + 28, 100, "Y", currentY());
        c = edit(x + 220, y + 28, 100, "Z", currentZ());
    }

    private void initPositionItem(int x, int y) {
        a = edit(x, y, 72, "X", currentX());
        b = edit(x + 78, y, 72, "Y", currentY());
        c = edit(x + 156, y, 72, "Z", currentZ());
        d = edit(x, y + 30, 228, "方块 ID", "stone");
        d.setResponder(this::refreshPreview);
        addWidget(new ButtonWidget(x + 234, y, 86, 20,
                Component.literal("使用主手"), button -> useMainHand()));
        preview = new ItemComponent(x + 298, y + 28,
                ItemStack.EMPTY, true);
        addComponent(preview);
    }

    private void initTrash(int x, int y) {
        initModes(x, y, "list", "add", "remove");
        a = edit(x, y + 30, 232, "物品 ID", "cobblestone");
        a.setResponder(this::refreshPreview);
        addWidget(new ButtonWidget(x + 238, y + 30, 82, 20,
                Component.literal("使用主手"), button -> {
                    useMainHandInto(a);
                    refreshPreview(a.getValue());
                }));
        preview = new ItemComponent(x + 298, y + 56,
                ItemStack.EMPTY, true);
        addComponent(preview);
    }

    private void initWaypoint(int x, int y) {
        initModes(x, y, "goto", "save", "delete", "list");
        a = edit(x, y + 30, 154, "路径点名称", "point");
        modes = List.of("goto", "save", "delete", "list");
        addWidget(new ButtonWidget(x + 160, y + 30, 160, 20,
                Component.literal("切换已有路径点"), button -> {
                    waypointIndex++;
                    refreshWaypoint();
                }));
        b = edit(x, y + 58, 100, "X", currentX());
        c = edit(x + 110, y + 58, 100, "Y", currentY());
        d = edit(x + 220, y + 58, 100, "Z", currentZ());
        refreshWaypoint();
    }

    private void initCommandPicker(int x, int y) {
        modes = Arrays.stream(
                        BaritoneControlScreen.ControlCommand.values())
                .map(value -> value.command).toList();
        modeButton = new ButtonWidget(x, y, 320, 20,
                Component.empty(), button -> {
                    mode = (mode + 1) % modes.size();
                    refreshMode();
                });
        addWidget(modeButton);
        refreshMode();
    }

    private void initBuild(int x, int y) {
        initModes(x, y, "fill", "clear", "file");
        a = edit(x, y + 26, 100, "方块ID / 蓝图路径", "stone");
        b = edit(x + 106, y + 26, 68, "X1", currentX());
        c = edit(x + 180, y + 26, 68, "Y1", currentY());
        d = edit(x + 254, y + 26, 66, "Z1", currentZ());
        e = edit(x + 106, y + 52, 68, "X2", currentX());
        f = edit(x + 180, y + 52, 68, "Y2", currentY());
        g = edit(x + 254, y + 52, 66, "Z2", currentZ());
    }

    private void initModes(int x, int y, String... values) {
        modes = List.of(values);
        modeButton = new ButtonWidget(x, y, 320, 20,
                Component.empty(), button -> {
                    mode = (mode + 1) % modes.size();
                    refreshMode();
                });
        addWidget(modeButton);
        refreshMode();
    }

    private EditBoxWidget edit(
            int x, int y, int width, String hint, String value) {
        EditBoxWidget box = new EditBoxWidget(
                font, x, y, width, 20, Component.literal(hint));
        box.setHint(Component.literal(hint));
        box.setValue(value);
        addWidget(box);
        return box;
    }

    private void refreshMode() {
        if (modeButton == null) return;
        String value = modes.isEmpty() ? ""
                : modes.get(Math.floorMod(mode, modes.size()));
        if (command.kind == BaritoneControlScreen.Kind.OPTIONAL_INTEGER
                || command.kind == BaritoneControlScreen.Kind.OPTIONAL_XZ) {
            value = modes.get(optionalEnabled ? 1 : 0);
        }
        modeButton.setMessage(Component.literal("模式: " + value
                + "（点击切换）"));
    }

    private void refreshWaypoint() {
        List<WaypointOption> values =
                ClientControlOptions.waypoints(fakeName());
        if (values.isEmpty()) return;
        WaypointOption selected = values.get(
                Math.floorMod(waypointIndex, values.size()));
        a.setValue(selected.name());
        b.setValue(Integer.toString(selected.x()));
        c.setValue(Integer.toString(selected.y()));
        d.setValue(Integer.toString(selected.z()));
    }

    private void refreshFake() {
        String fake = fakeName();
        fakeButton.setMessage(Component.literal("假人选择器: "
                + (fake.isEmpty() ? "没有可用假人" : fake)));
        fakeButton.active = !fake.isEmpty();
        if (!fake.isEmpty()) ClientControlOptions.rememberFake(fake);
    }

    void optionsUpdated() {
        fakeIndex = ClientControlOptions.selectedFakeIndex();
        refreshFake();
        if (command.kind == BaritoneControlScreen.Kind.WAYPOINT
                && a != null) refreshWaypoint();
    }

    private String fakeName() {
        List<String> fakes = ClientControlOptions.fakePlayers();
        return fakes.isEmpty() ? ""
                : fakes.get(Math.floorMod(fakeIndex, fakes.size()));
    }

    private void submit() {
        String args = arguments();
        if (!fakeName().isEmpty() && minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand("tell " + fakeName()
                    + " baritone " + command.command
                    + (args.isBlank() ? "" : " " + args));
        }
        minecraft.setScreen(parent);
    }

    private String arguments() {
        return switch (command.kind) {
            case INTEGER -> value(a);
            case OPTIONAL_INTEGER -> optionalEnabled ? value(a) : "";
            case OPTIONAL_XZ -> optionalEnabled
                    ? value(a) + " " + value(b) : "";
            case TOGGLE, SELECTION_ACTION, CACHE ->
                    modes.get(mode);
            case TUNNEL -> value(a) + " " + value(b) + " " + value(c);
            case GOAL -> switch (modes.get(mode)) {
                case "query" -> "";
                case "clear" -> "clear";
                default -> value(a) + " " + value(b) + " " + value(c);
            };
            case POSITION_ITEM -> value(a) + " " + value(b) + " "
                    + value(c) + " " + normalized(value(d));
            case TRASH -> modes.get(mode).equals("list")
                    ? "list" : modes.get(mode) + " " + normalized(value(a));
            case WAYPOINT -> waypointArguments();
            case COMMAND_PICKER -> modes.get(mode);
            case BUILD -> buildArguments();
            default -> "";
        };
    }

    private String buildArguments() {
        String action = modes.get(mode);
        if (action.equals("file")) {
            return "file " + value(a) + " " + value(b) + " "
                    + value(c) + " " + value(d);
        }
        String coordinates = value(b) + " " + value(c) + " "
                + value(d) + " " + value(e) + " " + value(f)
                + " " + value(g);
        return action.equals("fill")
                ? "fill " + normalized(value(a)) + " " + coordinates
                : "clear " + coordinates;
    }

    private String waypointArguments() {
        String action = modes.get(mode);
        if (action.equals("list")) return "list";
        if (action.equals("save")) {
            // Server save uses the sender's current position; the coordinate
            // fields remain a visual preview of that position.
            return "save user " + value(a);
        }
        List<WaypointOption> values =
                ClientControlOptions.waypoints(fakeName());
        if (values.isEmpty()) return "list";
        WaypointOption selected = values.get(
                Math.floorMod(waypointIndex, values.size()));
        return action + " " + selected.tag() + " " + selected.name();
    }

    private void useMainHand() {
        useMainHandInto(d);
        refreshPreview(d.getValue());
    }

    private void useMainHandInto(EditBoxWidget box) {
        if (minecraft.player == null) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(
                minecraft.player.getMainHandItem().getItem());
        box.setValue(id.getNamespace().equals("minecraft")
                ? id.getPath() : id.toString());
    }

    private void refreshPreview(String raw) {
        if (preview == null) return;
        ResourceLocation id = ResourceLocation.tryParse(
                raw.contains(":") ? raw : "minecraft:" + raw);
        preview.setItemStack(id == null ? ItemStack.EMPTY
                : new ItemStack(BuiltInRegistries.ITEM.getValue(id)));
    }

    private String currentX() {
        return minecraft.player == null ? "0"
                : Integer.toString(minecraft.player.blockPosition().getX());
    }
    private String currentY() {
        return minecraft.player == null ? "0"
                : Integer.toString(minecraft.player.blockPosition().getY());
    }
    private String currentZ() {
        return minecraft.player == null ? "0"
                : Integer.toString(minecraft.player.blockPosition().getZ());
    }
    private static String value(EditBoxWidget box) {
        return box == null ? "" : box.getValue().trim();
    }
    private static String normalized(String value) {
        return value.startsWith("minecraft:")
                ? value.substring(10) : value;
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
