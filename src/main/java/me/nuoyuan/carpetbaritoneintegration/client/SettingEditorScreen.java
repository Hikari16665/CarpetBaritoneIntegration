package me.nuoyuan.carpetbaritoneintegration.client;

import com.daqem.uilib.gui.AbstractScreen;
import com.daqem.uilib.gui.background.DarkenedBackground;
import com.daqem.uilib.gui.component.item.ItemComponent;
import com.daqem.uilib.gui.component.EmptyComponent;
import com.daqem.uilib.gui.component.text.TextComponent;
import com.daqem.uilib.gui.widget.ButtonWidget;
import com.daqem.uilib.gui.widget.EditBoxWidget;
import com.daqem.uilib.gui.widget.ScrollContainerWidget;
import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsPayload.SettingOption;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Type-directed setting editor; complex values never use a raw text box. */
final class SettingEditorScreen extends AbstractScreen {
    private final Screen parent;
    private final SettingOption option;
    private final String fake;
    private String encoded;
    private EditBoxWidget number;
    private EditBoxWidget vectorX;
    private EditBoxWidget vectorY;
    private EditBoxWidget vectorZ;
    private ButtonWidget valueButton;
    private int enumIndex;
    private final int[] rgba = new int[4];
    private int channel;
    private final List<String> entries = new ArrayList<>();
    private int entryIndex;
    private List<String> candidates = List.of();
    private int candidateIndex;
    private final Map<String, List<String>> mappings =
            new LinkedHashMap<>();
    private int sourceIndex;
    private int targetIndex;
    private ItemComponent preview;
    private final List<ButtonWidget> listRows = new ArrayList<>();
    private final List<ItemComponent> listIcons = new ArrayList<>();
    private final List<String> displayedEntries = new ArrayList<>();
    private ButtonWidget listModeButton;
    private int listPage;
    private boolean selectedOnly;
    private static final int LIST_PAGE_SIZE = 48;

    SettingEditorScreen(
            Screen parent, SettingOption option, String fake) {
        super(Component.literal(option.name()));
        this.parent = parent;
        this.option = option;
        this.fake = fake;
        this.encoded = option.value();
        setBackground(new DarkenedBackground());
    }

    @Override
    protected void init() {
        clear();
        int left = width / 2 - 170;
        int top = height / 2 - 112;
        addComponent(new TextComponent(left, top,
                Component.literal(option.name())));
        addComponent(new TextComponent(left, top + 14,
                Component.literal("类型: " + option.type()
                        + "  默认: " + option.defaultValue())));
        switch (option.type()) {
            case "BOOLEAN" -> initBoolean(left, top + 42);
            case "INTEGER", "LONG", "FLOAT", "DOUBLE" ->
                    initNumber(left, top + 42);
            case "ENUM" -> initEnum(left, top + 42);
            case "COLOR" -> initColor(left, top + 42);
            case "VECTOR" -> initVector(left, top + 42);
            case "BLOCK_LIST", "ITEM_LIST", "STRING_LIST" ->
                    initList(left, top + 42);
            case "BLOCK_MAP" -> initMap(left, top + 42);
            default -> initNumber(left, top + 42);
        }
        addWidget(new ButtonWidget(left, top + 174, 82, 22,
                Component.literal("恢复默认"), button -> reset()));
        addWidget(new ButtonWidget(left + 88, top + 174, 82, 22,
                Component.literal("返回"), button ->
                minecraft.gui.setScreen(parent)));
        addWidget(new ButtonWidget(left + 176, top + 174, 164, 22,
                Component.literal("应用设置"), button -> apply()));
        super.init();
    }

    private void initBoolean(int x, int y) {
        encoded = Boolean.toString(Boolean.parseBoolean(encoded));
        valueButton = new ButtonWidget(x, y, 340, 24,
                Component.empty(), button -> {
                    encoded = Boolean.toString(!Boolean.parseBoolean(encoded));
                    refreshValueButton();
                });
        addWidget(valueButton);
        refreshValueButton();
    }

    private void initNumber(int x, int y) {
        number = edit(x + 76, y, 188, encoded, "数值");
        addWidget(new ButtonWidget(x, y, 70, 20,
                Component.literal("-1"), button -> stepNumber(-1D)));
        addWidget(new ButtonWidget(x + 270, y, 70, 20,
                Component.literal("+1"), button -> stepNumber(1D)));
        addWidget(new ButtonWidget(x, y + 28, 70, 20,
                Component.literal("÷10"), button -> scaleNumber(0.1D)));
        addWidget(new ButtonWidget(x + 270, y + 28, 70, 20,
                Component.literal("×10"), button -> scaleNumber(10D)));
    }

    private void initEnum(int x, int y) {
        enumIndex = Math.max(0, option.choices().indexOf(encoded));
        valueButton = new ButtonWidget(x, y, 340, 24,
                Component.empty(), button -> {
                    enumIndex = (enumIndex + 1)
                            % Math.max(1, option.choices().size());
                    encoded = option.choices().get(enumIndex);
                    refreshValueButton();
                });
        addWidget(valueButton);
        refreshValueButton();
    }

    private void initColor(int x, int y) {
        long argb = Long.parseLong(encoded.replace("#", ""), 16);
        rgba[0] = (int) (argb >> 16) & 255;
        rgba[1] = (int) (argb >> 8) & 255;
        rgba[2] = (int) argb & 255;
        rgba[3] = encoded.length() > 7 ? (int) (argb >> 24) & 255 : 255;
        valueButton = new ButtonWidget(x, y, 340, 22,
                Component.empty(), button -> {
                    channel = (channel + 1) % 4;
                    refreshColor();
                });
        addWidget(valueButton);
        addWidget(new ButtonWidget(x, y + 28, 80, 20,
                Component.literal("-16"), button -> changeColor(-16)));
        addWidget(new ButtonWidget(x + 86, y + 28, 80, 20,
                Component.literal("-1"), button -> changeColor(-1)));
        addWidget(new ButtonWidget(x + 174, y + 28, 80, 20,
                Component.literal("+1"), button -> changeColor(1)));
        addWidget(new ButtonWidget(x + 260, y + 28, 80, 20,
                Component.literal("+16"), button -> changeColor(16)));
        refreshColor();
    }

    private void initVector(int x, int y) {
        String[] parts = encoded.split(",");
        vectorX = edit(x, y, 108, part(parts, 0), "X");
        vectorY = edit(x + 116, y, 108, part(parts, 1), "Y");
        vectorZ = edit(x + 232, y, 108, part(parts, 2), "Z");
        addComponent(new TextComponent(x, y + 28,
                Component.literal("三轴分别编辑，无需输入 x,y,z 复合文本")));
    }

    private void initList(int x, int y) {
        if (entries.isEmpty() && !encoded.equals("none")
                && !encoded.isBlank()) {
            entries.addAll(Arrays.asList(encoded.split(",")));
        }
        candidates = registryCandidates(option.type());
        ScrollContainerWidget scroll =
                new ScrollContainerWidget(340, 94, 2);
        scroll.setX(x);
        scroll.setY(y + 24);
        listRows.clear();
        listIcons.clear();
        for (int rowIndex = 0; rowIndex < LIST_PAGE_SIZE; rowIndex++) {
            final int clickedRow = rowIndex;
            EmptyComponent row = new EmptyComponent(0, 0, 326, 22);
            int buttonX = 0;
            int buttonWidth = 326;
            if (option.type().equals("ITEM_LIST")) {
                ItemComponent icon = new ItemComponent(
                        2, 2, ItemStack.EMPTY, true);
                row.addComponent(icon);
                listIcons.add(icon);
            }
            ButtonWidget button = new ButtonWidget(
                    buttonX, 0, buttonWidth, 20,
                    Component.empty(), ignored ->
                    toggleDisplayedEntry(clickedRow));
            row.addWidget(button);
            listRows.add(button);
            scroll.addComponent(row);
        }
        addWidget(scroll);
        addWidget(new ButtonWidget(x, y, 106, 20,
                Component.literal("上一页"), button -> {
                    listPage = Math.max(0, listPage - 1);
                    refreshListRows();
                }));
        listModeButton = new ButtonWidget(x + 112, y, 112, 20,
                Component.empty(), button -> {
                    selectedOnly = !selectedOnly;
                    listPage = 0;
                    refreshListRows();
                });
        addWidget(listModeButton);
        addWidget(new ButtonWidget(x + 230, y, 110, 20,
                Component.literal("下一页"), button -> {
                    int size = activeListValues().size();
                    if ((listPage + 1) * LIST_PAGE_SIZE < size) listPage++;
                    refreshListRows();
                }));
        refreshListRows();
    }

    private void initMap(int x, int y) {
        parseMap(encoded);
        candidates = registryCandidates("BLOCK_LIST");
        valueButton = new ButtonWidget(x, y, 340, 22,
                Component.empty(), button -> {
                    sourceIndex = (sourceIndex + 1) % candidates.size();
                    refreshMap();
                });
        addWidget(valueButton);
        addWidget(new ButtonWidget(x, y + 28, 108, 20,
                Component.literal("切换目标方块"), button -> {
                    targetIndex = (targetIndex + 1) % candidates.size();
                    refreshMap();
                }));
        addWidget(new ButtonWidget(x + 116, y + 28, 108, 20,
                Component.literal("添加替代关系"), button -> addMapping()));
        addWidget(new ButtonWidget(x + 232, y + 28, 108, 20,
                Component.literal("移除源方块"), button -> {
                    mappings.remove(candidate(sourceIndex));
                    refreshMap();
                }));
        refreshMap();
    }

    private EditBoxWidget edit(
            int x, int y, int width, String value, String hint) {
        EditBoxWidget box = new EditBoxWidget(
                font, x, y, width, 20, Component.literal(hint));
        box.setHint(Component.literal(hint));
        box.setValue(value);
        addWidget(box);
        return box;
    }

    private void refreshValueButton() {
        valueButton.setMessage(Component.literal("当前值: " + encoded
                + (option.type().equals("BOOLEAN") ? "（点击切换）" : "")));
    }

    private void stepNumber(double amount) {
        try {
            double value = Double.parseDouble(number.getValue()) + amount;
            number.setValue(formatNumber(value));
        } catch (NumberFormatException ignored) { }
    }

    private void scaleNumber(double multiplier) {
        try {
            double value = Double.parseDouble(number.getValue()) * multiplier;
            number.setValue(formatNumber(value));
        } catch (NumberFormatException ignored) { }
    }

    private String formatNumber(double value) {
        return switch (option.type()) {
            case "INTEGER", "LONG" -> Long.toString(Math.round(value));
            case "FLOAT" -> Float.toString((float) value);
            default -> Double.toString(value);
        };
    }

    private void changeColor(int delta) {
        rgba[channel] = Math.max(0, Math.min(255, rgba[channel] + delta));
        refreshColor();
    }

    private void refreshColor() {
        encoded = String.format("#%02X%02X%02X%02X",
                rgba[3], rgba[0], rgba[1], rgba[2]);
        String[] names = {"红", "绿", "蓝", "透明度"};
        valueButton.setMessage(Component.literal(encoded
                + "  当前通道: " + names[channel]
                + "=" + rgba[channel]));
    }

    private List<String> activeListValues() {
        return selectedOnly ? List.copyOf(entries) : candidates;
    }

    private void toggleDisplayedEntry(int row) {
        if (row < 0 || row >= displayedEntries.size()) return;
        String value = displayedEntries.get(row);
        if (entries.contains(value)) entries.remove(value);
        else entries.add(value);
        encoded = entries.isEmpty() ? "none" : String.join(",", entries);
        refreshListRows();
    }

    private void refreshListRows() {
        if (listModeButton != null) {
            listModeButton.setMessage(Component.literal(
                    (selectedOnly ? "仅已选择" : "全部候选")
                            + " (" + entries.size() + ")"));
        }
        List<String> values = activeListValues();
        int start = listPage * LIST_PAGE_SIZE;
        displayedEntries.clear();
        for (int row = 0; row < listRows.size(); row++) {
            int index = start + row;
            ButtonWidget button = listRows.get(row);
            boolean present = index < values.size();
            button.visible = present;
            if (!present) {
                if (row < listIcons.size()) {
                    listIcons.get(row).setItemStack(ItemStack.EMPTY);
                }
                continue;
            }
            String value = values.get(index);
            displayedEntries.add(value);
            button.setMessage(Component.literal(
                    (entries.contains(value) ? "✓ 点击移除  " : "＋ 点击添加  ")
                            + value));
            if (row < listIcons.size()) {
                Identifier id = Identifier.tryParse(value);
                listIcons.get(row).setItemStack(id == null
                        ? ItemStack.EMPTY : new ItemStack(
                        BuiltInRegistries.ITEM.getValue(id)));
            }
        }
    }

    private void addMapping() {
        mappings.computeIfAbsent(candidate(sourceIndex),
                ignored -> new ArrayList<>()).add(candidate(targetIndex));
        refreshMap();
    }

    private void refreshMap() {
        String source = candidate(sourceIndex);
        String target = candidate(targetIndex);
        List<String> existing = mappings.getOrDefault(source, List.of());
        valueButton.setMessage(Component.literal("源: " + source
                + " → 候选: " + target + " | 已有: " + existing.size()));
    }

    private void parseMap(String value) {
        if (value.isBlank() || value.equals("none")) return;
        for (String entry : value.split(";")) {
            String[] pair = entry.split("=", 2);
            if (pair.length == 2) {
                mappings.put(pair[0], new ArrayList<>(
                        Arrays.asList(pair[1].split("\\|"))));
            }
        }
    }

    private List<String> registryCandidates(String type) {
        if (type.equals("ITEM_LIST")) {
            return BuiltInRegistries.ITEM.keySet().stream()
                    .map(Identifier::toString).sorted().toList();
        }
        if (type.equals("STRING_LIST")) {
            return BuiltInRegistries.BLOCK.stream()
                    .flatMap(block -> block.getStateDefinition()
                            .getProperties().stream())
                    .map(property -> property.getName())
                    .distinct().sorted().toList();
        }
        return BuiltInRegistries.BLOCK.keySet().stream()
                .map(Identifier::toString).sorted().toList();
    }

    private String candidate(int index) {
        return candidates.isEmpty() ? ""
                : candidates.get(Math.floorMod(index, candidates.size()));
    }

    private void apply() {
        encoded = switch (option.type()) {
            case "INTEGER", "LONG", "FLOAT", "DOUBLE", "STRING" ->
                    number.getValue().trim();
            case "VECTOR" -> vectorX.getValue().trim() + ","
                    + vectorY.getValue().trim() + ","
                    + vectorZ.getValue().trim();
            case "BLOCK_LIST", "ITEM_LIST", "STRING_LIST" ->
                    entries.isEmpty() ? "none" : String.join(",", entries);
            case "BLOCK_MAP" -> encodeMap();
            default -> encoded;
        };
        send("settings " + option.name() + " " + encoded);
        minecraft.gui.setScreen(parent);
    }

    private String encodeMap() {
        if (mappings.isEmpty()) return "none";
        List<String> values = new ArrayList<>();
        mappings.forEach((source, targets) ->
                values.add(source + "=" + String.join("|", targets)));
        return String.join(";", values);
    }

    private void reset() {
        send("settings reset " + option.name());
        minecraft.gui.setScreen(parent);
    }

    private void send(String arguments) {
        if (fake.isBlank() || minecraft.getConnection() == null) return;
        ClientCommandSender.sendCommand(fake, arguments);
    }

    private static String part(String[] parts, int index) {
        return index < parts.length ? parts[index] : "0";
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
