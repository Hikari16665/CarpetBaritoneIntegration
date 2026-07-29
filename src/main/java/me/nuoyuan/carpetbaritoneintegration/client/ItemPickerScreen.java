package me.nuoyuan.carpetbaritoneintegration.client;

import com.daqem.uilib.gui.AbstractScreen;
import com.daqem.uilib.gui.background.DarkenedBackground;
import com.daqem.uilib.gui.component.item.ItemComponent;
import com.daqem.uilib.gui.widget.ButtonWidget;
import com.daqem.uilib.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/** Searchable registry-backed item picker shared by all command forms. */
final class ItemPickerScreen extends AbstractScreen {
    private final Screen parent;
    private final boolean blocksOnly;
    private final Consumer<String> selected;
    private String query = "";
    private int page;
    private int firstVisibleRow;
    private int listLeft;
    private int listTop;
    private static final int PAGE_SIZE = 96;
    private static final int VISIBLE_ROWS = 8;

    ItemPickerScreen(Screen parent, boolean blocksOnly,
                     Consumer<String> selected) {
        super(Component.literal(blocksOnly ? "选择方块" : "选择物品"));
        this.parent = parent;
        this.blocksOnly = blocksOnly;
        this.selected = selected;
        setBackground(new DarkenedBackground());
    }

    @Override
    protected void init() {
        clear();
        int left = width / 2 - 170;
        int top = height / 2 - 120;
        EditBoxWidget search = new EditBoxWidget(font, left, top, 274, 20,
                Component.literal("搜索物品 ID"));
        search.setHint(Component.literal("搜索物品 ID"));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            page = 0;
            firstVisibleRow = 0;
        });
        addWidget(search);
        addWidget(new ButtonWidget(left + 280, top, 60, 20,
                Component.literal("搜索"), button -> rebuild()));

        List<Item> matches = matchingItems();
        List<Item> pageItems = matches.stream()
                .skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).toList();
        firstVisibleRow = Math.max(0, Math.min(firstVisibleRow,
                Math.max(0, pageItems.size() - VISIBLE_ROWS)));
        listLeft = left;
        listTop = top + 26;
        int end = Math.min(pageItems.size(),
                firstVisibleRow + VISIBLE_ROWS);
        for (int index = firstVisibleRow; index < end; index++) {
            Item item = pageItems.get(index);
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            int rowY = listTop + (index - firstVisibleRow) * 22;
            addComponent(new ItemComponent(
                    left + 2, rowY + 2, new ItemStack(item), true));
            addWidget(new ButtonWidget(left, rowY, 340, 20,
                    Component.literal(display(id)), button -> {
                minecraft.gui.setScreen(parent);
                selected.accept(normalized(id));
            }));
        }
        addWidget(new ButtonWidget(left, top + 222, 106, 22,
                Component.literal("上一页"), button -> {
            if (page > 0) {
                page--;
                firstVisibleRow = 0;
                rebuild();
            }
        }));
        addWidget(new ButtonWidget(left + 112, top + 222, 116, 22,
                Component.literal((page + 1) + "/"
                        + Math.max(1, (matches.size() + PAGE_SIZE - 1)
                        / PAGE_SIZE)), button -> { }));
        addWidget(new ButtonWidget(left + 234, top + 222, 106, 22,
                Component.literal("下一页"), button -> {
            if ((page + 1) * PAGE_SIZE < matches.size()) {
                page++;
                firstVisibleRow = 0;
                rebuild();
            }
        }));
        super.init();
    }

    private void rebuild() {
        ItemPickerScreen replacement = new ItemPickerScreen(
                parent, blocksOnly, selected);
        replacement.query = query;
        replacement.page = page;
        replacement.firstVisibleRow = firstVisibleRow;
        minecraft.gui.setScreen(replacement);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount,
                                 double verticalAmount) {
        if (mouseX >= listLeft && mouseX < listLeft + 340
                && mouseY >= listTop
                && mouseY < listTop + VISIBLE_ROWS * 22) {
            int pageSize = Math.min(PAGE_SIZE,
                    Math.max(0, matchingItems().size()
                            - page * PAGE_SIZE));
            int previous = firstVisibleRow;
            if (verticalAmount > 0) firstVisibleRow--;
            if (verticalAmount < 0) firstVisibleRow++;
            firstVisibleRow = Math.max(0, Math.min(firstVisibleRow,
                    Math.max(0, pageSize - VISIBLE_ROWS)));
            if (previous != firstVisibleRow) {
                rebuild();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY,
                horizontalAmount, verticalAmount);
    }

    private List<Item> matchingItems() {
        String needle = query.trim().toLowerCase();
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> !blocksOnly || item instanceof BlockItem)
                .filter(item -> BuiltInRegistries.ITEM.getKey(item)
                        .toString().toLowerCase().contains(needle))
                .sorted((a, b) -> BuiltInRegistries.ITEM.getKey(a).toString()
                        .compareTo(BuiltInRegistries.ITEM.getKey(b).toString()))
                .toList();
    }

    private static String display(Identifier id) {
        return "    " + id;
    }

    private static String normalized(Identifier id) {
        return id.getNamespace().equals("minecraft")
                ? id.getPath() : id.toString();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
