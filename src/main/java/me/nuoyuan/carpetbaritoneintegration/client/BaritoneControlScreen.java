package me.nuoyuan.carpetbaritoneintegration.client;

import com.daqem.uilib.gui.AbstractScreen;
import com.daqem.uilib.gui.background.DarkenedBackground;
import com.daqem.uilib.gui.component.text.TextComponent;
import com.daqem.uilib.gui.component.EmptyComponent;
import com.daqem.uilib.gui.widget.ButtonWidget;
import com.daqem.uilib.gui.widget.ScrollContainerWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** First stage: a single command catalogue with no parameter clutter. */
public final class BaritoneControlScreen extends AbstractScreen {
    public BaritoneControlScreen() {
        super(Component.literal("Carpet Baritone 命令"));
        setBackground(new DarkenedBackground());
    }

    @Override
    protected void init() {
        clear();
        ClientControlOptions.request();
        int panelWidth = 320;
        int left = width / 2 - panelWidth / 2;
        int top = Math.max(18, height / 2 - 120);
        addComponent(new TextComponent(left, top,
                Component.literal("选择要执行的命令")));
        ScrollContainerWidget scroll = new ScrollContainerWidget(
                panelWidth, Math.min(220, height - top - 35), 4);
        scroll.setX(left);
        scroll.setY(top + 22);
        for (Category category : Category.values()) {
            List<ControlCommand> commands = new ArrayList<>();
            for (ControlCommand command : ControlCommand.values()) {
                if (command.category == category) commands.add(command);
            }
            if (commands.isEmpty()) continue;
            scroll.addComponent(new TextComponent(
                    2, 0, Component.literal(category.title)));
            for (int i = 0; i < commands.size(); i += 2) {
                EmptyComponent row = new EmptyComponent(
                        0, 0, panelWidth - 12, 24);
                addCommandButton(row, commands.get(i), 0);
                if (i + 1 < commands.size()) {
                    addCommandButton(row, commands.get(i + 1), 154);
                }
                scroll.addComponent(row);
            }
        }
        addWidget(scroll);
        super.init();
    }

    private void addCommandButton(
            EmptyComponent row, ControlCommand command, int x) {
        row.addWidget(new ButtonWidget(
                x, 0, 150, 22, Component.literal(command.title),
                button -> minecraft.gui.setScreen(command == ControlCommand.SETTINGS
                        ? new SettingsListScreen(this)
                        : command.kind == Kind.MULTI_BLOCK
                        || command.kind == Kind.MULTI_ITEM_AMOUNT_PLAYER
                        ? new MultiItemCommandScreen(this, command)
                        : command.kind.structured()
                        ? new StructuredCommandScreen(this, command)
                        : new CommandParameterScreen(this, command))));
    }

    enum ControlCommand {
        GOTO("goto", "前往坐标", Kind.POSITION, Category.NAVIGATION),
        COME("come", "来到我身边", Kind.NONE, Category.NAVIGATION),
        Y("y", "前往高度", Kind.INTEGER, Category.NAVIGATION, "高度"),
        SURFACE("surface", "前往地表", Kind.NONE, Category.NAVIGATION),
        THIS_WAY("thisway", "沿视线前进", Kind.INTEGER,
                Category.NAVIGATION, "距离"),
        AXIS("axis", "坐标轴公路", Kind.OPTIONAL_INTEGER,
                Category.NAVIGATION, "可选参数"),
        TUNNEL("tunnel", "挖掘隧道", Kind.TUNNEL,
                Category.NAVIGATION, "高度 宽度 长度"),
        HOME("home", "前往 Home", Kind.NONE, Category.NAVIGATION),
        SET_HOME("sethome", "设置 Home", Kind.NONE, Category.NAVIGATION),
        GOAL("goal", "设置/查看目标", Kind.GOAL,
                Category.NAVIGATION, "可选：x y z / clear"),
        PATH("path", "执行当前目标", Kind.NONE, Category.NAVIGATION),

        MINE("mine", "挖掘方块", Kind.MULTI_BLOCK, Category.TASK),
        AREA_MINE("areamine", "持续区域挖掘",
                Kind.MULTI_BLOCK, Category.TASK),
        COLLECT_ITEM("collectItem", "收集并交付物品",
                Kind.MULTI_ITEM_AMOUNT_PLAYER, Category.TASK),
        CLEAN("clean", "清空现有选区", Kind.NONE, Category.TASK),
        FARM("farm", "自动收菜", Kind.OPTIONAL_INTEGER,
                Category.TASK, "可选：范围"),
        BUILD("build", "建造", Kind.BUILD,
                Category.TASK, "名称及构建参数"),
        SCHEMATICA("schematica", "服务器最近蓝图",
                Kind.NONE, Category.TASK),
        LITEMATICA("litematica", "服务器 Litematica",
                Kind.OPTIONAL_INTEGER, Category.TASK,
                "可选：从 1 开始的蓝图索引"),
        ELYTRA("elytra", "鞘翅飞行", Kind.POSITION, Category.TASK),
        EXPLORE("explore", "探索世界", Kind.OPTIONAL_XZ,
                Category.TASK, "可选：x z"),
        FOLLOW("follow", "跟随玩家", Kind.PLAYER, Category.TASK),
        RUN_AWAY("runaway", "远离威胁", Kind.INTEGER,
                Category.TASK, "距离"),
        GET("get", "接近方块", Kind.ITEM, Category.TASK),
        BACKFILL("backfill", "回填开关", Kind.TOGGLE,
                Category.TASK, "on / off"),

        GIVE_ALL("giveAll", "交付全部物品", Kind.PLAYER,
                Category.INTERACTION),
        BREAK("break", "破坏方块", Kind.POSITION,
                Category.INTERACTION, "x y z"),
        PLACE("place", "放置方块", Kind.POSITION_ITEM,
                Category.INTERACTION, "x y z [方块ID]"),
        PICKUP("pickup", "拾取掉落物", Kind.ITEM,
                Category.INTERACTION, "可选参数"),
        TRASH("trash", "垃圾黑名单", Kind.TRASH,
                Category.INTERACTION, "list/add/remove ..."),
        BLACKLIST("blacklist", "拉黑最近目标", Kind.NONE,
                Category.INTERACTION),
        FIND("find", "查找方块", Kind.ITEM, Category.INTERACTION),

        POS1("pos1", "设置选区点 1", Kind.POSITION, Category.SELECTION),
        POS2("pos2", "设置选区点 2", Kind.POSITION, Category.SELECTION),
        SELECTION("sel", "选区管理", Kind.SELECTION_ACTION,
                Category.SELECTION, "clear / list / ..."),
        WAYPOINTS("waypoints", "路径点管理", Kind.WAYPOINT,
                Category.SELECTION, "list/save/delete/goto ..."),

        SETTINGS("settings", "设置管理", Kind.GENERIC,
                Category.SYSTEM, "设置名 [值]"),
        AVOID("avoid", "危险规避", Kind.TOGGLE,
                Category.SYSTEM, "on / off"),
        CACHE("cache", "缓存管理", Kind.CACHE,
                Category.SYSTEM, "status/repack/save/reload"),
        REPACK("repack", "重新扫描缓存", Kind.OPTIONAL_INTEGER,
                Category.SYSTEM, "可选：半径"),
        RELOAD_ALL("reloadall", "重载全部缓存", Kind.NONE, Category.SYSTEM),
        SAVE_ALL("saveall", "保存全部缓存", Kind.NONE, Category.SYSTEM),
        GC("gc", "请求垃圾回收", Kind.NONE, Category.SYSTEM),

        STOP("stop", "停止当前任务", Kind.NONE, Category.CONTROL),
        PAUSE("pause", "暂停", Kind.NONE, Category.CONTROL),
        RESUME("resume", "继续", Kind.NONE, Category.CONTROL),
        PAUSED("paused", "查询暂停状态", Kind.NONE, Category.CONTROL),
        STATUS("status", "任务状态", Kind.NONE, Category.CONTROL),
        PROCESS("proc", "当前 Process", Kind.NONE, Category.CONTROL),
        ETA("eta", "预计到达时间", Kind.NONE, Category.CONTROL),
        STATS("stats", "调度统计", Kind.NONE, Category.CONTROL),
        VERSION("version", "版本信息", Kind.NONE, Category.CONTROL),
        HELP("help", "命令帮助", Kind.COMMAND_PICKER,
                Category.CONTROL, "可选：命令名");

        final String command;
        final String title;
        final Kind kind;
        final Category category;
        final String hint;

        ControlCommand(String command, String title, Kind kind,
                       Category category) {
            this(command, title, kind, category, "参数");
        }

        ControlCommand(String command, String title, Kind kind,
                       Category category, String hint) {
            this.command = command;
            this.title = title;
            this.kind = kind;
            this.category = category;
            this.hint = hint;
        }
    }

    enum Kind {
        NONE, POSITION, SELECTION, ITEM, PLAYER,
        ITEM_AMOUNT_PLAYER, MULTI_BLOCK, MULTI_ITEM_AMOUNT_PLAYER,
        GENERIC, INTEGER, OPTIONAL_INTEGER,
        OPTIONAL_XZ, TOGGLE, TUNNEL, GOAL, POSITION_ITEM, TRASH,
        SELECTION_ACTION, WAYPOINT, CACHE, COMMAND_PICKER, BUILD;

        boolean structured() {
            return switch (this) {
                case MULTI_BLOCK, MULTI_ITEM_AMOUNT_PLAYER,
                        INTEGER, OPTIONAL_INTEGER, OPTIONAL_XZ, TOGGLE,
                        TUNNEL, GOAL, POSITION_ITEM, TRASH,
                        SELECTION_ACTION, WAYPOINT, CACHE,
                        COMMAND_PICKER, BUILD -> true;
                default -> false;
            };
        }
    }

    enum Category {
        NAVIGATION("导航"),
        TASK("任务"),
        INTERACTION("交互"),
        SELECTION("选区与路径点"),
        SYSTEM("设置与缓存"),
        CONTROL("控制与信息");

        final String title;
        Category(String title) {
            this.title = "── " + title + " ──";
        }
    }
}
