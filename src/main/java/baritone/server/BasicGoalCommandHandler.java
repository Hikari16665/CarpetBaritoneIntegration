/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.server;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.schematic.FillSchematic;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import carpet.patches.EntityPlayerMPFake;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.nuoyuan.carpetbaritoneintegration.Carpetbaritoneintegration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses the small server-safe command set accepted through private messages. */
public final class BasicGoalCommandHandler {
    private static final long PRIMARY_TIMEOUT_MS = 2_000L;
    private static final long FAILURE_TIMEOUT_MS = 5_000L;

    private BasicGoalCommandHandler() {
    }

    public static boolean handle(ServerPlayer sender, ServerPlayer target, String message) {
        String trimmed = message.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String prefix;
        if (lower.equals("cbi") || lower.startsWith("cbi ")) {
            prefix = "cbi";
        } else if (lower.equals("baritone")
                || lower.startsWith("baritone ")) {
            prefix = "baritone";
        } else {
            return false;
        }
        if (!(target instanceof EntityPlayerMPFake)) {
            return false;
        }

        String command = trimmed.substring(prefix.length()).trim();
        try {
            Baritone baritone = Carpetbaritoneintegration.BARITONES.getOrCreate(
                    target.getServer(), target);
            if (!baritone.getCommandManager().executeAs(sender, target, command)) {
                throw new IllegalArgumentException("未知指令。发送 cbi help 查看帮助");
            }
        } catch (IllegalArgumentException exception) {
            reply(target, sender, "错误: " + exception.getMessage());
        } catch (Exception exception) {
            reply(target, sender, "寻路执行失败: " + exception.getClass().getSimpleName());
        }
        return true;
    }

    public static void executeCommand(
            ServerPlayer sender, ServerPlayer fakePlayer,
            String label, IArgConsumer consumer) {
        java.util.List<String> values = new java.util.ArrayList<>();
        values.add(label);
        while (consumer.hasAny()) {
            try {
                values.add(consumer.getString());
            } catch (baritone.api.command.exception.CommandException exception) {
                throw new IllegalArgumentException(exception.getMessage(), exception);
            }
        }
        execute(sender, fakePlayer, String.join(" ", values));
    }

    private static void execute(ServerPlayer sender, ServerPlayer fakePlayer, String command) {
        String[] args = command.isEmpty() ? new String[0] : command.split("\\s+");
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            reply(fakePlayer, sender,
                    "可用: goto, come, y, mine <方块...> [总数量], break <x> <y> <z>, "
                            + "place <方块> <x> <y> <z>, runaway <距离>, avoid <on|off>, "
                            + "collectItem <物品> <数量> <玩家>, follow <玩家>, "
                            + "giveAll <玩家>, trash <list|add|remove>, "
                            + "pos1, pos2, clean, areamine, set/settings, "
                            + "stop, status, help");
            return;
        }

        Baritone baritone = Carpetbaritoneintegration.BARITONES.getOrCreate(
                fakePlayer.getServer(),
                fakePlayer
        );
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "goto" -> startGoto(sender, fakePlayer, baritone, args);
            case "come" -> {
                if (args.length != 1) {
                    throw new IllegalArgumentException("用法: cbi come");
                }
                BetterBlockPos senderFeet = BetterBlockPos.from(sender.blockPosition());
                startGoal(sender, fakePlayer, baritone,
                        new GoalBlock(senderFeet), "发送者 " + sender.getScoreboardName());
            }
            case "y" -> {
                if (args.length != 2) {
                    throw new IllegalArgumentException("用法: cbi y <高度>");
                }
                int y = coordinate(args[1], "y");
                startGoal(sender, fakePlayer, baritone, new GoalYLevel(y), "高度 " + y);
            }
            case "mine" -> startMine(sender, fakePlayer, baritone, args);
            case "areamine" -> startAreaMine(
                    sender, fakePlayer, baritone, args);
            case "trash", "trashlist" ->
                    manageTrashList(sender, fakePlayer, args);
            case "collectitem", "collect_item", "collect" ->
                    startCollectItem(sender, fakePlayer, baritone, args);
            case "giveall", "give_all" ->
                    startGiveAll(sender, fakePlayer, baritone, args);
            case "break" -> startBreak(sender, fakePlayer, baritone, args);
            case "place" -> startPlace(sender, fakePlayer, baritone, args);
            case "follow" -> startFollow(sender, fakePlayer, baritone, args);
            case "explore" -> startExplore(sender, fakePlayer, baritone, args);
            case "get", "getto", "get_to_block" -> startGetToBlock(
                    sender, fakePlayer, baritone, args);
            case "backfill" -> setBackfill(sender, fakePlayer, args);
            case "farm" -> startFarm(sender, fakePlayer, baritone, args);
            case "build" -> startBuild(sender, fakePlayer, baritone, args);
            case "elytra", "fly" -> startElytra(sender, fakePlayer, baritone, args);
            case "runaway", "run_away" -> startRunAway(sender, fakePlayer, baritone, args);
            case "avoid", "avoidance" -> setAvoidance(sender, fakePlayer, args);
            case "set", "setting", "settings" ->
                    manageSettings(sender, fakePlayer, baritone, args);
            case "pos1" -> setSelectionCorner(
                    sender, fakePlayer, baritone, args, true);
            case "pos2" -> setSelectionCorner(
                    sender, fakePlayer, baritone, args, false);
            case "clean" -> startClean(
                    sender, fakePlayer, baritone, args);
            case "cache" -> manageCache(sender, fakePlayer, baritone, args);
            case "stop", "cancel" -> {
                baritone.cancelAll();
                reply(fakePlayer, sender, "已停止当前 cbi 任务");
            }
            case "status" -> reply(fakePlayer, sender, status(baritone));
            case "stats" -> reply(fakePlayer, sender, schedulerStats(baritone));
            default -> throw new IllegalArgumentException("未知指令。发送 cbi help 查看帮助");
        }
    }

    private static void startGoto(
            ServerPlayer sender,
            ServerPlayer fakePlayer,
            Baritone baritone,
            String[] args
    ) {
        Goal goal;
        String destination;
        if (args.length == 4) {
            int x = coordinate(args[1], "x");
            int y = coordinate(args[2], "y");
            int z = coordinate(args[3], "z");
            goal = new GoalBlock(x, y, z);
            destination = x + " " + y + " " + z;
        } else if (args.length == 3) {
            int x = coordinate(args[1], "x");
            int z = coordinate(args[2], "z");
            goal = new GoalXZ(x, z);
            destination = x + " " + z;
        } else {
            throw new IllegalArgumentException("用法: cbi goto <x> <y> <z> 或 cbi goto <x> <z>");
        }

        startGoal(sender, fakePlayer, baritone, goal, destination);
    }

    private static void startMine(
            ServerPlayer sender, ServerPlayer fakePlayer, Baritone baritone, String[] args
    ) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "用法: cbi mine <block_id...> [总数量]");
        }
        int blockEnd = args.length;
        int count = 1;
        if (args.length > 2 && args[args.length - 1].matches("[0-9]+")) {
            count = positive(args[args.length - 1], "数量");
            blockEnd--;
        }
        java.util.List<Block> targets = new java.util.ArrayList<>();
        for (int index = 1; index < blockEnd; index++) {
            for (String id : args[index].split(",")) {
                if (!id.isBlank()) targets.add(block(id));
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("至少提供一个目标方块 ID");
        }
        baritone.getMineProcess().mineWithFeedback(
                count,
                new BlockOptionalMetaLookup(targets.toArray(Block[]::new)),
                message -> reply(fakePlayer, sender, message));
        String names = targets.stream()
                .map(BuiltInRegistries.BLOCK::getKey)
                .map(ResourceLocation::toString)
                .collect(java.util.stream.Collectors.joining(", "));
        reply(fakePlayer, sender, "开始搜索并挖掘 [" + names
                + "]，总数量 " + count);
    }

    private static void startAreaMine(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "用法: areamine <block_id...>");
        }
        baritone.api.selection.ISelection selection =
                baritone.getSelectionManager().getOnlySelection();
        if (selection == null) {
            throw new IllegalArgumentException(
                    "请先使用 pos1 和 pos2 设置立体选区");
        }
        List<Block> targets = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            for (String id : args[index].split(",")) {
                if (!id.isBlank()) targets.add(block(id));
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "至少提供一个目标方块 ID");
        }
        baritone.getMineProcess().mineAreaWithFeedback(
                selection,
                new BlockOptionalMetaLookup(
                        targets.toArray(Block[]::new)),
                message -> reply(fakePlayer, sender, message));
        String names = targets.stream()
                .map(BuiltInRegistries.BLOCK::getKey)
                .map(ResourceLocation::toString)
                .collect(java.util.stream.Collectors.joining(", "));
        reply(fakePlayer, sender, "开始持续挖掘选区内的 ["
                + names + "]；目标暂时为空时会等待，"
                + "只有 stop 才会结束");
    }

    private static void manageTrashList(
            ServerPlayer sender, ServerPlayer fakePlayer, String[] args) {
        if (args.length == 1 || args.length == 2
                && args[1].equalsIgnoreCase("list")) {
            String values = Baritone.settings().trashItems.value.stream()
                    .map(BuiltInRegistries.ITEM::getKey)
                    .map(ResourceLocation::toString)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(", "));
            reply(fakePlayer, sender, "垃圾黑名单（命中后全部丢弃）: "
                    + (values.isEmpty() ? "空" : values));
            return;
        }
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "用法: cbi trash <list|add|remove> [item_id]");
        }
        Item selected = item(args[2]);
        if (args[1].equalsIgnoreCase("add")) {
            if (!Baritone.settings().trashItems.value.contains(selected)) {
                Baritone.settings().trashItems.value.add(selected);
            }
            reply(fakePlayer, sender, "已加入垃圾黑名单: "
                    + BuiltInRegistries.ITEM.getKey(selected));
        } else if (args[1].equalsIgnoreCase("remove")) {
            Baritone.settings().trashItems.value.remove(selected);
            reply(fakePlayer, sender, "已移出垃圾黑名单: "
                    + BuiltInRegistries.ITEM.getKey(selected));
        } else {
            throw new IllegalArgumentException(
                    "用法: cbi trash <list|add|remove> [item_id]");
        }
    }

    private static void startCollectItem(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "用法: cbi collectItem <item_id> <数量> <接收玩家>");
        }
        Item requested = item(args[1]);
        int count = positive(args[2], "数量");
        ServerPlayer recipient = fakePlayer.getServer().getPlayerList()
                .getPlayerByName(args[3]);
        if (recipient == null || recipient == fakePlayer) {
            throw new IllegalArgumentException(
                    "找不到可接收物品的玩家: " + args[3]);
        }
        baritone.cancelAll();
        baritone.getCollectItemProcess().collect(
                requested, count, recipient,
                message -> reply(fakePlayer, sender, message));
        reply(fakePlayer, sender, "开始收集 " + args[1] + " ×" + count
                + "，完成后交给 " + recipient.getScoreboardName());
    }

    private static void startGiveAll(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "用法: cbi giveAll <playerName>");
        }
        ServerPlayer recipient = fakePlayer.getServer().getPlayerList()
                .getPlayerByName(args[1]);
        if (recipient == null || recipient == fakePlayer) {
            throw new IllegalArgumentException(
                    "找不到可接收物品的玩家: " + args[1]);
        }
        baritone.cancelAll();
        baritone.getGiveAllProcess().giveAll(
                recipient,
                message -> reply(fakePlayer, sender, message));
        reply(fakePlayer, sender, "开始前往 "
                + recipient.getScoreboardName() + " 并交付全部物品");
    }

    private static void startBreak(
            ServerPlayer sender, ServerPlayer fakePlayer, Baritone baritone, String[] args
    ) {
        if (args.length != 4) {
            throw new IllegalArgumentException("用法: cbi break <x> <y> <z>");
        }
        BlockPos pos = new BlockPos(
                coordinate(args[1], "x"), coordinate(args[2], "y"), coordinate(args[3], "z"));
        baritone.startBlockTask(BlockInteractionTask.breakAt(
                baritone, pos, message -> reply(fakePlayer, sender, message)));
        reply(fakePlayer, sender, "开始破坏 " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    private static void startPlace(
            ServerPlayer sender, ServerPlayer fakePlayer, Baritone baritone, String[] args
    ) {
        if (args.length != 5) {
            throw new IllegalArgumentException("用法: cbi place <block_id> <x> <y> <z>");
        }
        Block block = block(args[1]);
        BlockPos pos = new BlockPos(
                coordinate(args[2], "x"), coordinate(args[3], "y"), coordinate(args[4], "z"));
        baritone.startBlockTask(BlockInteractionTask.place(
                baritone, block, pos, message -> reply(fakePlayer, sender, message)));
        reply(fakePlayer, sender, "开始放置 " + args[1] + " 到 "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    private static void startRunAway(
            ServerPlayer sender, ServerPlayer fakePlayer, Baritone baritone, String[] args
    ) {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法: cbi runaway <距离>");
        }
        int distance = positive(args[1], "距离");
        BlockPos[] threats = baritone.getPlayerContext().entitiesStream()
                .filter(entity -> entity instanceof Mob)
                .map(entity -> entity.blockPosition().immutable())
                .toArray(BlockPos[]::new);
        if (threats.length == 0) {
            throw new IllegalArgumentException("可视范围内没有需要远离的生物");
        }
        startGoal(
                sender,
                fakePlayer,
                baritone,
                new GoalRunAway(distance, fakePlayer.blockPosition().getY(), threats),
                "附近怪物至少 " + distance + " 格"
        );
    }

    private static void startFollow(
            ServerPlayer sender, ServerPlayer fakePlayer, Baritone baritone, String[] args
    ) {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法: cbi follow <玩家>");
        }
        ServerPlayer followed = fakePlayer.getServer().getPlayerList().getPlayerByName(args[1]);
        if (followed == null || followed == fakePlayer) {
            throw new IllegalArgumentException("找不到可跟随的玩家: " + args[1]);
        }
        java.util.UUID followedId = followed.getUUID();
        baritone.startFollowing(entity -> entity.getUUID().equals(followedId));
        reply(fakePlayer, sender, "开始持续跟随 " + followed.getScoreboardName());
    }

    private static void startExplore(
            ServerPlayer sender, ServerPlayer fakePlayer, Baritone baritone, String[] args
    ) {
        int centerX;
        int centerZ;
        if (args.length == 1) {
            centerX = fakePlayer.blockPosition().getX();
            centerZ = fakePlayer.blockPosition().getZ();
        } else if (args.length == 3) {
            centerX = coordinate(args[1], "x");
            centerZ = coordinate(args[2], "z");
        } else {
            throw new IllegalArgumentException("用法: cbi explore [x z]");
        }
        baritone.startExploring(centerX, centerZ);
        reply(fakePlayer, sender, "开始探索，中心为 " + centerX + " " + centerZ);
    }

    private static void startGetToBlock(
            ServerPlayer sender, ServerPlayer fakePlayer, Baritone baritone, String[] args
    ) {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法: cbi get <block_id>");
        }
        Block targetBlock = block(args[1]);
        baritone.startGetToBlock(targetBlock);
        reply(fakePlayer, sender, "开始寻找并接近 " + args[1]);
    }

    private static void startElytra(
            ServerPlayer sender, ServerPlayer fakePlayer, Baritone baritone, String[] args
    ) {
        if (args.length != 4) {
            throw new IllegalArgumentException("用法: cbi elytra <x> <y> <z>");
        }
        BlockPos destination = new BlockPos(
                coordinate(args[1], "x"), coordinate(args[2], "y"),
                coordinate(args[3], "z"));
        baritone.startElytra(destination);
        reply(fakePlayer, sender, "开始鞘翅飞行至 " + destination.toShortString());
    }

    private static void startBuild(
            ServerPlayer sender, ServerPlayer fakePlayer, Baritone baritone, String[] args
    ) {
        if ((args.length == 3 || args.length == 6)
                && args[1].equalsIgnoreCase("file")) {
            BlockPos origin = args.length == 6
                    ? new BlockPos(coordinate(args[3], "x"), coordinate(args[4], "y"),
                            coordinate(args[5], "z"))
                    : fakePlayer.blockPosition();
            File schematic = new File(args[2]);
            baritone.cancelAll();
            if (!baritone.getBuilderProcess().build(
                    schematic.getName(), schematic, origin)) {
                throw new IllegalArgumentException(
                        "无法加载蓝图（目前支持 Sponge .schem v1/v2）: "
                                + schematic.getPath());
            }
            reply(fakePlayer, sender, "开始在 " + origin.toShortString()
                    + " 建造蓝图 " + schematic.getName());
            return;
        }
        if (args.length == 9 && args[1].equalsIgnoreCase("fill")) {
            Block fill = block(args[2]);
            BlockPos a = new BlockPos(
                    coordinate(args[3], "x1"), coordinate(args[4], "y1"),
                    coordinate(args[5], "z1"));
            BlockPos b = new BlockPos(
                    coordinate(args[6], "x2"), coordinate(args[7], "y2"),
                    coordinate(args[8], "z2"));
            BlockPos min = new BlockPos(
                    Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                    Math.min(a.getZ(), b.getZ()));
            baritone.startBuilding(
                    "fill",
                    new FillSchematic(
                            Math.abs(a.getX() - b.getX()) + 1,
                            Math.abs(a.getY() - b.getY()) + 1,
                            Math.abs(a.getZ() - b.getZ()) + 1,
                            fill.defaultBlockState()),
                    min);
            reply(fakePlayer, sender, "开始填充区域");
            return;
        }
        if (args.length == 8 && args[1].equalsIgnoreCase("clear")) {
            BlockPos a = new BlockPos(
                    coordinate(args[2], "x1"), coordinate(args[3], "y1"),
                    coordinate(args[4], "z1"));
            BlockPos b = new BlockPos(
                    coordinate(args[5], "x2"), coordinate(args[6], "y2"),
                    coordinate(args[7], "z2"));
            baritone.cancelAll();
            baritone.getBuilderProcess().clearArea(a, b);
            reply(fakePlayer, sender, "开始清空区域");
            return;
        }
        throw new IllegalArgumentException(
                "用法: cbi build fill <block> <x1 y1 z1 x2 y2 z2>，"
                        + "或 build clear <x1 y1 z1 x2 y2 z2>");
    }

    private static void startFarm(
            ServerPlayer sender, ServerPlayer fakePlayer, Baritone baritone, String[] args
    ) {
        if (args.length > 2) {
            throw new IllegalArgumentException("用法: cbi farm [范围]");
        }
        int range = args.length == 2 ? positive(args[1], "范围") : 0;
        baritone.startFarming(range, fakePlayer.blockPosition());
        reply(fakePlayer, sender, "开始自动耕作" + (range == 0 ? "" : "，范围 " + range));
    }

    private static void setBackfill(
            ServerPlayer sender, ServerPlayer fakePlayer, String[] args
    ) {
        if (args.length != 2
                || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            throw new IllegalArgumentException("用法: cbi backfill <on|off>");
        }
        boolean enabled = args[1].equalsIgnoreCase("on");
        Baritone.settings().backfill.value = enabled;
        reply(fakePlayer, sender, "路径回填已" + (enabled ? "开启" : "关闭"));
    }

    private static void setAvoidance(
            ServerPlayer sender, ServerPlayer fakePlayer, String[] args
    ) {
        if (args.length != 2
                || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            throw new IllegalArgumentException("用法: cbi avoid <on|off>");
        }
        boolean enabled = args[1].equalsIgnoreCase("on");
        Baritone.settings().avoidance.value = enabled;
        reply(fakePlayer, sender, "怪物路径回避已" + (enabled ? "开启" : "关闭"));
    }

    private static void manageCache(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        String action = args.length == 1 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> reply(fakePlayer, sender,
                    "缓存区块 " + baritone.getWorldCache().cachedChunkCount()
                            + "，特殊方块位置 "
                            + baritone.getWorldCache().indexedLocationCount());
            case "repack" -> {
                if (args.length > 3) {
                    throw new IllegalArgumentException(
                            "用法: cbi cache repack [区块半径]");
                }
                int radius = args.length == 3 ? positive(args[2], "区块半径")
                        : fakePlayer.getServer().getPlayerList().getViewDistance();
                int packed = baritone.getWorldScanner().repack(
                        baritone.getPlayerContext(), radius);
                reply(fakePlayer, sender, "已排队扫描 " + packed + " 个区块");
            }
            case "save" -> {
                baritone.getWorldCache().saveAsync();
                reply(fakePlayer, sender, "世界缓存已排队保存");
            }
            case "reload" -> {
                baritone.getWorldCache().reloadAllFromDisk();
                reply(fakePlayer, sender, "世界缓存已从磁盘重新加载");
            }
            default -> throw new IllegalArgumentException(
                    "用法: cbi cache <status|repack [半径]|save|reload>");
        }
    }

    private static void manageSettings(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        Settings settings = BaritoneAPI.getSettings();
        List<Field> fields = settingFields();
        if (args.length == 1
                || args[1].equalsIgnoreCase("list")
                || args[1].equalsIgnoreCase("all")
                || args[1].equalsIgnoreCase("modified")) {
            boolean modified = args.length > 1
                    && args[1].equalsIgnoreCase("modified");
            int page = 1;
            String filter = "";
            int firstExtra = args.length == 1 ? 1 : 2;
            for (int i = firstExtra; i < args.length; i++) {
                try {
                    page = Math.max(1, Integer.parseInt(args[i]));
                } catch (NumberFormatException ignored) {
                    filter = args[i].toLowerCase(Locale.ROOT);
                }
            }
            final String nameFilter = filter;
            List<Field> shown = fields.stream()
                    .filter(field -> field.getName().toLowerCase(Locale.ROOT)
                            .contains(nameFilter))
                    .filter(field -> !modified
                            || isModified(readSetting(settings, field)))
                    .toList();
            int pageSize = 8;
            int pages = Math.max(1,
                    (shown.size() + pageSize - 1) / pageSize);
            page = Math.min(page, pages);
            int from = Math.min(shown.size(), (page - 1) * pageSize);
            int to = Math.min(shown.size(), from + pageSize);
            reply(fakePlayer, sender, "设置 "
                    + page + "/" + pages + "（共 " + shown.size()
                    + " 项，设置为全服共享）");
            for (Field field : shown.subList(from, to)) {
                Settings.Setting<?> setting = readSetting(settings, field);
                reply(fakePlayer, sender, field.getName() + " = "
                        + settingValue(setting.value));
            }
            return;
        }

        String operation = args[1].toLowerCase(Locale.ROOT);
        if (operation.equals("reset")) {
            if (args.length != 3) {
                throw new IllegalArgumentException(
                        "用法: set reset <设置名|all>");
            }
            if (args[2].equalsIgnoreCase("all")) {
                fields.forEach(field ->
                        readSetting(settings, field).reset());
                recalculateAfterSettingChange(baritone);
                reply(fakePlayer, sender, "已将全部设置恢复默认值");
                return;
            }
            Field field = findSetting(fields, args[2]);
            Settings.Setting<?> setting = readSetting(settings, field);
            setting.reset();
            recalculateAfterSettingChange(baritone);
            reply(fakePlayer, sender, field.getName() + " 已恢复为 "
                    + settingValue(setting.value));
            return;
        }

        if (operation.equals("toggle")) {
            if (args.length != 3) {
                throw new IllegalArgumentException(
                        "用法: set toggle <布尔设置名>");
            }
            Field field = findSetting(fields, args[2]);
            Settings.Setting<?> setting = readSetting(settings, field);
            if (!(setting.value instanceof Boolean current)) {
                throw new IllegalArgumentException(
                        field.getName() + " 不是布尔设置");
            }
            setSettingValue(setting, !current);
            recalculateAfterSettingChange(baritone);
            reply(fakePlayer, sender, field.getName() + " = "
                    + setting.value);
            return;
        }

        Field field = findSetting(fields, args[1]);
        Settings.Setting<?> setting = readSetting(settings, field);
        if (args.length == 2) {
            reply(fakePlayer, sender, field.getName() + " = "
                    + settingValue(setting.value) + "（默认 "
                    + settingValue(setting.defaultValue) + "）");
            return;
        }
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "设置值不能包含空格；列表请使用逗号分隔");
        }
        Object parsed = parseSettingValue(
                field, setting.defaultValue, args[2]);
        setSettingValue(setting, parsed);
        recalculateAfterSettingChange(baritone);
        reply(fakePlayer, sender, field.getName() + " = "
                + settingValue(setting.value));
    }

    private static void setSelectionCorner(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args, boolean first) {
        if (args.length != 1 && args.length != 4) {
            throw new IllegalArgumentException(
                    "用法: " + (first ? "pos1" : "pos2")
                            + " [x y z]");
        }
        BlockPos pos = args.length == 1
                ? sender.blockPosition()
                : new BlockPos(
                        coordinate(args[1], "x"),
                        coordinate(args[2], "y"),
                        coordinate(args[3], "z"));
        if (first) {
            baritone.setSelectionPos1(pos);
        } else {
            baritone.setSelectionPos2(pos);
        }
        reply(fakePlayer, sender,
                (first ? "pos1" : "pos2") + " = "
                        + pos.getX() + " " + pos.getY() + " "
                        + pos.getZ());
        if (baritone.getSelectionPos1() != null
                && baritone.getSelectionPos2() != null) {
            BetterBlockPos a = baritone.getSelectionPos1();
            BetterBlockPos b = baritone.getSelectionPos2();
            long volume = (long) (Math.abs(a.x - b.x) + 1)
                    * (Math.abs(a.y - b.y) + 1)
                    * (Math.abs(a.z - b.z) + 1);
            reply(fakePlayer, sender, "立体选区已就绪，共 "
                    + volume + " 个方块");
        }
    }

    private static void startClean(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("用法: clean");
        }
        baritone.api.selection.ISelection selection =
                baritone.getSelectionManager().getOnlySelection();
        if (selection == null) {
            throw new IllegalArgumentException(
                    "请先使用 pos1 和 pos2 设置立体选区");
        }
        baritone.cancelAll();
        baritone.getCleanProcess().clean(selection,
                message -> reply(fakePlayer, sender, message));
        reply(fakePlayer, sender,
                "开始从 Y=" + selection.max().y
                        + " 向下清理到 Y=" + selection.min().y
                        + "；流体会先填实，寻路不会进入流体");
    }

    private static List<Field> settingFields() {
        return java.util.Arrays.stream(Settings.class.getFields())
                .filter(field -> field.getType() == Settings.Setting.class)
                .sorted(Comparator.comparing(Field::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static Field findSetting(List<Field> fields, String name) {
        return fields.stream()
                .filter(field -> field.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知设置: " + name
                                + "；使用 settings list [筛选词] [页码]"));
    }

    private static Settings.Setting<?> readSetting(
            Settings settings, Field field) {
        try {
            return (Settings.Setting<?>) field.get(settings);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean isModified(Settings.Setting<?> setting) {
        return !java.util.Objects.equals(
                setting.value, setting.defaultValue);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setSettingValue(
            Settings.Setting setting, Object value) {
        setting.value = value;
    }

    private static Object parseSettingValue(
            Field field, Object defaultValue, String text) {
        try {
            if (defaultValue instanceof Boolean) {
                if (text.equalsIgnoreCase("true")
                        || text.equalsIgnoreCase("on")) return true;
                if (text.equalsIgnoreCase("false")
                        || text.equalsIgnoreCase("off")) return false;
                throw new IllegalArgumentException(
                        "布尔值必须是 true/false 或 on/off");
            }
            if (defaultValue instanceof Integer) return Integer.parseInt(text);
            if (defaultValue instanceof Long) return Long.parseLong(text);
            if (defaultValue instanceof Float) return Float.parseFloat(text);
            if (defaultValue instanceof Double) return Double.parseDouble(text);
            if (defaultValue instanceof String) return text;
            if (defaultValue instanceof Enum<?> value) {
                return parseEnum(value.getDeclaringClass(), text);
            }
            if (defaultValue instanceof List<?>) {
                return parseSettingList(field, text);
            }
            if (defaultValue instanceof Map<?, ?>) {
                throw new IllegalArgumentException(
                        field.getName() + " 暂不支持通过聊天编辑映射");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    field.getName() + " 的数字格式无效: " + text);
        }
        throw new IllegalArgumentException(
                field.getName() + " 的类型暂不支持聊天设置");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object parseEnum(
            Class<? extends Enum> type, String text) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(text)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("无效枚举值: " + text);
    }

    private static List<?> parseSettingList(Field field, String text) {
        if (text.equalsIgnoreCase("none")
                || text.equals("[]")) return new ArrayList<>();
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType settingType)
                || !(settingType.getActualTypeArguments()[0]
                instanceof ParameterizedType valueType)) {
            throw new IllegalArgumentException("无法识别列表元素类型");
        }
        Type element = valueType.getActualTypeArguments()[0];
        List<Object> values = new ArrayList<>();
        for (String token : text.split(",")) {
            if (element == Block.class) {
                values.add(block(token));
            } else if (element == Item.class) {
                values.add(item(token));
            } else {
                throw new IllegalArgumentException(
                        field.getName() + " 的列表类型暂不支持");
            }
        }
        return values;
    }

    private static String settingValue(Object value) {
        if (value instanceof Block block) {
            return BuiltInRegistries.BLOCK.getKey(block).toString();
        }
        if (value instanceof Item item) {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return "none";
            return list.stream().map(BasicGoalCommandHandler::settingValue)
                    .collect(java.util.stream.Collectors.joining(","));
        }
        return String.valueOf(value);
    }

    private static void recalculateAfterSettingChange(Baritone baritone) {
        Goal goal = baritone.getActiveGoal();
        if (goal != null) baritone.recalculateForProcess(goal);
    }

    private static void startGoal(
            ServerPlayer sender,
            ServerPlayer fakePlayer,
            Baritone baritone,
            Goal goal,
            String destination
    ) {
        reply(fakePlayer, sender, "正在计算到 " + destination + " 的路径");
        if (!baritone.pathToGoal(
                goal, PRIMARY_TIMEOUT_MS, FAILURE_TIMEOUT_MS)) {
            reply(fakePlayer, sender, "寻路调度队列已满，稍后重试");
        }
    }

    private static int coordinate(String value, String axis) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(axis + " 坐标必须是整数: " + value);
        }
    }

    private static int positive(String value, String name) {
        int result = coordinate(value, name);
        if (result <= 0) {
            throw new IllegalArgumentException(name + "必须大于 0");
        }
        return result;
    }

    private static Block block(String value) {
        ResourceLocation id = ResourceLocation.tryParse(
                value.indexOf(':') < 0 ? "minecraft:" + value : value);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("未知方块 ID: " + value);
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        if (block == Blocks.AIR) {
            throw new IllegalArgumentException("不能把空气作为方块目标");
        }
        return block;
    }

    private static Item item(String value) {
        ResourceLocation id = ResourceLocation.tryParse(
                value.indexOf(':') < 0 ? "minecraft:" + value : value);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("未知物品 ID: " + value);
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == net.minecraft.world.item.Items.AIR) {
            throw new IllegalArgumentException("不能把空气作为物品目标");
        }
        return item;
    }

    private static String status(Baritone baritone) {
        ServerPathExecutor executor = baritone.getPathExecutor();
        if (baritone.getBlockTask() != null) {
            return "方块任务: " + baritone.getBlockTask().status();
        }
        if (executor == null) {
            if (baritone.getPathingBehavior().getInProgress().isPresent()) {
                return "正在后台计算路径，调度队列 "
                        + ServerPathingScheduler.queuedCount()
                        + "，运行任务 "
                        + ServerPathingScheduler.activeCount();
            }
            java.util.Optional<baritone.api.process.IBaritoneProcess> process =
                    baritone.getPathingControlManager().mostRecentInControl();
            if (process.isPresent()) {
                return "当前进程: " + process.get().displayName();
            }
            return "当前没有执行中的 cbi 任务";
        }
        return "正在寻路，移动 " + executor.getPosition() + "/"
                + executor.getPath().movements().size()
                + "，状态 " + executor.getLastStatus();
    }

    private static String schedulerStats(Baritone baritone) {
        return String.format(Locale.ROOT,
                "寻路调度: 工作线程 %d，运行 %d，排队 %d，已提交 %d，"
                        + "已完成 %d，平均 %.1fms；主线程最近 %.2fms，"
                        + "峰值 %.2fms，超 50ms %d 次；世界缓存 %d 区块，索引 %d 个位置",
                ServerPathingScheduler.workerCount(),
                ServerPathingScheduler.activeCount(),
                ServerPathingScheduler.queuedCount(),
                ServerPathingScheduler.submittedCount(),
                ServerPathingScheduler.completedCount(),
                ServerPathingScheduler.averageCalculationMillis(),
                Carpetbaritoneintegration.BARITONES.lastTickMillis(),
                Carpetbaritoneintegration.BARITONES.maxTickMillis(),
                Carpetbaritoneintegration.BARITONES.overBudgetTicks(),
                baritone.getWorldCache().cachedChunkCount(),
                baritone.getWorldCache().indexedLocationCount());
    }

    private static void reply(ServerPlayer fakePlayer, ServerPlayer recipient, String message) {
        MinecraftServer server = fakePlayer.getServer();
        if (server == null) {
            return;
        }
        String command = "tell "
                + StringArgumentType.escapeIfRequired(recipient.getScoreboardName())
                + " "
                + StringArgumentType.escapeIfRequired("[CBI] " + message);
        server.getCommands().performPrefixedCommand(fakePlayer.createCommandSourceStack(), command);
    }
}
