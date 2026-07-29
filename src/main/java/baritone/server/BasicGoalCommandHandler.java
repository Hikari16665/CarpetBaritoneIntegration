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
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalAxis;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.schematic.FillSchematic;
import baritone.api.pathing.goals.GoalStrictDirection;
import baritone.api.cache.IWaypoint;
import baritone.api.cache.Waypoint;
import baritone.api.utils.PathCalculationResult;
import baritone.cache.ServerWorldCache;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import me.nuoyuan.carpetbaritoneintegration.compat.SyncmaticaBridge;
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
        ExecutionResult result = executeDirect(sender, target, command);
        if (!result.success()) {
            reply(target, sender, result.message());
        }
        return true;
    }

    public static ExecutionResult executeDirect(
            ServerPlayer sender, ServerPlayer target, String command) {
        if (!(target instanceof EntityPlayerMPFake)) {
            return new ExecutionResult(false,
                    "错误: 目标玩家不是 Carpet 假人");
        }
        try {
            Baritone baritone = Carpetbaritoneintegration.BARITONES.getOrCreate(
                    target.getServer(), target);
            if (!baritone.getCommandManager().executeAs(sender, target, command)) {
                throw new IllegalArgumentException("未知指令。发送 cbi help 查看帮助");
            }
            return new ExecutionResult(true, "命令已提交");
        } catch (IllegalArgumentException exception) {
            return new ExecutionResult(false,
                    "错误: " + exception.getMessage());
        } catch (Exception exception) {
            return new ExecutionResult(false,
                    "寻路执行失败: "
                            + exception.getClass().getSimpleName());
        }
    }

    public record ExecutionResult(boolean success, String message) { }

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
        if (args.length == 0
                || args[0].equalsIgnoreCase("help")
                || args[0].equalsIgnoreCase("commands")
                || args[0].equals("?")) {
            showHelp(sender, fakePlayer, args);
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
                startGoal(sender, fakePlayer, baritone,
                        new GoalNear(sender.blockPosition(), 2),
                        "发送者 " + sender.getScoreboardName(), true);
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
            case "schematica" -> {
                requireNoArguments(args, "schematica");
                baritone.cancelAll();
                baritone.getBuilderProcess().setFeedback(
                        message -> reply(fakePlayer, sender, message));
                baritone.getBuilderProcess().buildOpenSchematic();
                if (baritone.getBuilderProcess().isActive()) {
                    reply(fakePlayer, sender,
                            "已加载服务器最近修改的蓝图");
                } else {
                    baritone.getBuilderProcess().setFeedback(null);
                }
            }
            case "litematica" -> {
                if (args.length > 2) {
                    throw new IllegalArgumentException(
                            "用法: cbi litematica [索引，起始为1]");
                }
                int index = args.length == 2
                        ? positive(args[1], "索引") - 1 : 0;
                baritone.cancelAll();
                baritone.getBuilderProcess().setFeedback(
                        message -> reply(fakePlayer, sender, message));
                baritone.getBuilderProcess()
                        .buildOpenLitematic(index);
                if (baritone.getBuilderProcess().isActive()) {
                    reply(fakePlayer, sender,
                            "已加载服务器 Litematica 蓝图 #"
                                    + (index + 1));
                } else {
                    baritone.getBuilderProcess().setFeedback(null);
                }
            }
            case "elytra", "fly" -> startElytra(sender, fakePlayer, baritone, args);
            case "runaway", "run_away" -> startRunAway(sender, fakePlayer, baritone, args);
            case "goal" -> manageGoal(sender, fakePlayer, baritone, args);
            case "path" -> startCurrentGoal(
                    sender, fakePlayer, baritone, args);
            case "proc" -> reply(fakePlayer, sender, status(baritone));
            case "eta" -> reportEta(sender, fakePlayer, baritone, args);
            case "version" -> reportVersion(sender, fakePlayer, args);
            case "surface", "top" ->
                    startSurface(sender, fakePlayer, baritone, args);
            case "thisway", "forward" ->
                    setThisWay(sender, fakePlayer, baritone, args);
            case "axis", "highway" ->
                    setAxisGoal(sender, fakePlayer, baritone, args);
            case "tunnel" ->
                    startTunnel(sender, fakePlayer, baritone, args);
            case "sel", "selection", "s" ->
                    manageSelection(sender, fakePlayer, baritone, args);
            case "waypoints", "waypoint", "wp" ->
                    manageWaypoints(sender, fakePlayer, baritone, args);
            case "sethome" ->
                    saveHome(sender, fakePlayer, baritone, args);
            case "home" ->
                    goHome(sender, fakePlayer, baritone, args);
            case "blacklist" ->
                    blacklistClosest(sender, fakePlayer, baritone, args);
            case "find" ->
                    findCachedBlocks(sender, fakePlayer, baritone, args);
            case "pickup" ->
                    startPickup(sender, fakePlayer, baritone, args);
            case "reloadall" -> {
                requireNoArguments(args, "reloadall");
                baritone.getWorldCache().reloadAllFromDisk();
                reply(fakePlayer, sender, "已重新加载世界缓存");
            }
            case "saveall" -> {
                requireNoArguments(args, "saveall");
                ServerWorldCache.saveAll();
                reply(fakePlayer, sender, "已保存全部世界缓存");
            }
            case "gc" -> {
                requireNoArguments(args, "gc");
                System.gc();
                reply(fakePlayer, sender, "已请求 Java 垃圾回收");
            }
            case "repack" -> {
                String[] forwarded = new String[args.length + 1];
                forwarded[0] = "cache";
                forwarded[1] = "repack";
                if (args.length > 1) {
                    System.arraycopy(args, 1, forwarded, 2,
                            args.length - 1);
                }
                manageCache(sender, fakePlayer, baritone, forwarded);
            }
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
                baritone.getPauseProcess().setPaused(false);
                baritone.cancelAll();
                reply(fakePlayer, sender, "已停止当前 cbi 任务");
            }
            case "pause", "p", "paws" -> {
                requireNoArguments(args, "pause");
                if (baritone.getPauseProcess().isPaused()) {
                    throw new IllegalArgumentException("任务已经暂停");
                }
                baritone.getPauseProcess().setPaused(true);
                reply(fakePlayer, sender, "已暂停当前任务");
            }
            case "resume", "r", "unpause", "unpaws" -> {
                requireNoArguments(args, "resume");
                boolean builderPaused =
                        baritone.getBuilderProcess().isPaused();
                boolean globallyPaused =
                        baritone.getPauseProcess().isPaused();
                if (!builderPaused && !globallyPaused) {
                    throw new IllegalArgumentException("任务当前没有暂停");
                }
                baritone.getBuilderProcess().resume();
                baritone.getPauseProcess().setPaused(false);
                reply(fakePlayer, sender, "已恢复当前任务");
            }
            case "paused" -> {
                requireNoArguments(args, "paused");
                reply(fakePlayer, sender,
                        (baritone.getPauseProcess().isPaused()
                                || baritone.getBuilderProcess().isPaused())
                                ? "Baritone 已暂停"
                                : "Baritone 未暂停");
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
        if (args.length < 4 || args.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "用法: cbi collectItem <item_id> <数量> "
                            + "[<item_id> <数量> ...] <接收玩家>");
        }
        java.util.Map<Item, Integer> requested =
                new java.util.LinkedHashMap<>();
        for (int index = 1; index < args.length - 1; index += 2) {
            Item selected = item(args[index]);
            int requestedCount = positive(args[index + 1], "amount");
            requested.merge(selected, requestedCount, Integer::sum);
        }
        ServerPlayer recipient = fakePlayer.getServer().getPlayerList()
                .getPlayerByName(args[args.length - 1]);
        if (recipient == null || recipient == fakePlayer) {
            throw new IllegalArgumentException(
                    "找不到可接收物品的玩家: "
                            + args[args.length - 1]);
        }
        baritone.cancelAll();
        baritone.getCollectItemProcess().collect(
                requested, recipient,
                message -> reply(fakePlayer, sender, message));
        reply(fakePlayer, sender, "开始收集 " + requested.size()
                + " 种物品，完成后交给 "
                + recipient.getScoreboardName());
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

    private static void manageGoal(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length == 1) {
            Goal goal = baritone.getCustomGoalProcess().getGoal();
            if (goal == null) goal = baritone.getActiveGoal();
            reply(fakePlayer, sender,
                    goal == null ? "当前没有目标" : "当前目标: " + goal);
            return;
        }
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "用法: goal [x y z]");
        }
        Goal goal = new GoalBlock(
                coordinate(args[1], "x"),
                coordinate(args[2], "y"),
                coordinate(args[3], "z"));
        baritone.cancelAll();
        baritone.getCustomGoalProcess().setGoal(goal);
        reply(fakePlayer, sender, "已设置目标: " + goal);
    }

    private static void startCurrentGoal(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("用法: path");
        }
        Goal goal = baritone.getCustomGoalProcess().getGoal();
        if (goal == null) {
            throw new IllegalArgumentException(
                    "请先使用 goal x y z 设置目标");
        }
        baritone.getCustomGoalProcess().path();
        reply(fakePlayer, sender, "开始前往当前目标");
    }

    private static void reportEta(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("用法: eta");
        }
        java.util.Optional<Double> ticks =
                baritone.getPathingBehavior().estimatedTicksToGoal();
        if (ticks.isEmpty()) {
            reply(fakePlayer, sender, "当前没有可估算的执行路径");
            return;
        }
        reply(fakePlayer, sender, String.format(
                Locale.ROOT, "预计剩余 %.1f 秒",
                ticks.get() / 20.0D));
    }

    private static void reportVersion(
            ServerPlayer sender, ServerPlayer fakePlayer,
            String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("用法: version");
        }
        String version = net.fabricmc.loader.api.FabricLoader
                .getInstance().getModContainer(
                        "carpetbaritoneintegration")
                .map(container -> container.getMetadata()
                        .getVersion().getFriendlyString())
                .orElse("unknown");
        reply(fakePlayer, sender,
                "CarpetBaritoneIntegration " + version);
    }

    private static void startSurface(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("用法: surface");
        }
        BlockPos feet = fakePlayer.blockPosition();
        for (int y = Math.max(feet.getY() + 1,
                fakePlayer.level().getSeaLevel());
             y < fakePlayer.level().getMaxBuildHeight(); y++) {
            BlockPos solid = new BlockPos(feet.getX(), y, feet.getZ());
            if (!fakePlayer.level().getBlockState(solid).isAir()
                    && fakePlayer.level().getBlockState(
                            solid.above()).isAir()
                    && fakePlayer.level().getBlockState(
                            solid.above(2)).isAir()) {
                startGoal(sender, fakePlayer, baritone,
                        new GoalBlock(solid.above()),
                        "地表 " + solid.above().toShortString());
                return;
            }
        }
        throw new IllegalArgumentException("当前位置上方没有可用地表");
    }

    private static void setThisWay(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "用法: thisway <距离>");
        }
        int distance = positive(args[1], "距离");
        double yaw = Math.toRadians(fakePlayer.getYHeadRot());
        int x = fakePlayer.blockPosition().getX()
                + (int) Math.round(-Math.sin(yaw) * distance);
        int z = fakePlayer.blockPosition().getZ()
                + (int) Math.round(Math.cos(yaw) * distance);
        baritone.cancelAll();
        baritone.getCustomGoalProcess().setGoal(new GoalXZ(x, z));
        reply(fakePlayer, sender,
                "已设置前方目标: " + x + " " + z);
    }

    private static void setAxisGoal(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("用法: axis");
        }
        baritone.cancelAll();
        GoalAxis goal = new GoalAxis();
        baritone.getCustomGoalProcess().setGoal(goal);
        reply(fakePlayer, sender, "已设置最近坐标轴目标");
    }

    private static void startTunnel(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length == 1) {
            baritone.cancelAll();
            GoalStrictDirection goal = new GoalStrictDirection(
                    BetterBlockPos.from(fakePlayer.blockPosition()),
                    fakePlayer.getDirection());
            baritone.getCustomGoalProcess().setGoal(goal);
            baritone.getCustomGoalProcess().path();
            reply(fakePlayer, sender, "开始沿当前方向持续开掘隧道");
            return;
        }
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "用法: tunnel [高度 宽度 深度]");
        }
        int height = positive(args[1], "高度");
        int width = positive(args[2], "宽度");
        int depth = positive(args[3], "深度");
        if (height < 2) {
            throw new IllegalArgumentException("隧道高度至少为 2");
        }
        BlockPos feet = fakePlayer.blockPosition();
        net.minecraft.core.Direction facing = fakePlayer.getDirection();
        int left = (width - 1) / 2;
        int right = width - 1 - left;
        BlockPos corner1;
        BlockPos corner2;
        switch (facing) {
            case EAST -> {
                corner1 = feet.offset(0, 0, -left);
                corner2 = feet.offset(depth, height - 1, right);
            }
            case WEST -> {
                corner1 = feet.offset(0, 0, right);
                corner2 = feet.offset(-depth, height - 1, -left);
            }
            case NORTH -> {
                corner1 = feet.offset(-left, 0, 0);
                corner2 = feet.offset(right, height - 1, -depth);
            }
            case SOUTH -> {
                corner1 = feet.offset(right, 0, 0);
                corner2 = feet.offset(-left, height - 1, depth);
            }
            default -> throw new IllegalStateException(
                    "无效水平朝向 " + facing);
        }
        baritone.cancelAll();
        baritone.getBuilderProcess().clearArea(corner1, corner2);
        reply(fakePlayer, sender, "开始开掘 "
                + height + "×" + width + "×" + depth + " 隧道");
    }

    private static void manageSelection(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "用法: sel <clear|fill|cleararea|expand|contract|shift>");
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("clear")) {
            baritone.getSelectionManager().removeAllSelections();
            reply(fakePlayer, sender, "已清除全部选区");
            return;
        }
        baritone.api.selection.ISelection selection =
                baritone.getSelectionManager().getOnlySelection();
        if (selection == null) {
            throw new IllegalArgumentException(
                    "当前操作需要且仅允许一个完整选区");
        }
        if (action.equals("fill") || action.equals("set")
                || action.equals("cleararea")) {
            if (action.equals("cleararea") && args.length != 2
                    || !action.equals("cleararea") && args.length != 3) {
                throw new IllegalArgumentException(
                        "用法: sel fill <block_id> 或 sel cleararea");
            }
            Block block = action.equals("cleararea")
                    ? Blocks.AIR : block(args[2]);
            baritone.cancelAll();
            baritone.getBuilderProcess().build(
                    action,
                    new FillSchematic(
                            selection.size().getX(),
                            selection.size().getY(),
                            selection.size().getZ(),
                            block.defaultBlockState()),
                    selection.min());
            reply(fakePlayer, sender,
                    "开始执行选区 " + action);
            return;
        }
        if (action.equals("expand") || action.equals("contract")
                || action.equals("shift")) {
            if (args.length != 4) {
                throw new IllegalArgumentException(
                        "用法: sel " + action + " <方向> <格数>");
            }
            net.minecraft.core.Direction direction =
                    net.minecraft.core.Direction.byName(
                            args[2].toLowerCase(Locale.ROOT));
            if (direction == null) {
                throw new IllegalArgumentException(
                        "无效方向: " + args[2]);
            }
            int blocks = positive(args[3], "格数");
            if (action.equals("expand")) {
                baritone.getSelectionManager().expand(
                        selection, direction, blocks);
            } else if (action.equals("contract")) {
                baritone.getSelectionManager().contract(
                        selection, direction, blocks);
            } else {
                baritone.getSelectionManager().shift(
                        selection, direction, blocks);
            }
            reply(fakePlayer, sender,
                    "已执行选区 " + action);
            return;
        }
        throw new IllegalArgumentException(
                "未知选区操作: " + action);
    }

    private static void requireNoArguments(
            String[] args, String command) {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "用法: " + command);
        }
    }

    private static void manageWaypoints(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        var waypoints = baritone.getWorldProvider()
                .getCurrentWorld().getWaypoints();
        String action = args.length == 1
                ? "list" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            if (args.length > 3) {
                throw new IllegalArgumentException(
                        "用法: waypoints list [类型]");
            }
            IWaypoint.Tag tag = args.length == 3
                    ? waypointTag(args[2]) : null;
            java.util.Collection<IWaypoint> values = tag == null
                    ? waypoints.getAllWaypoints()
                    : waypoints.getByTag(tag);
            if (values.isEmpty()) {
                reply(fakePlayer, sender, "没有已保存的 waypoint");
                return;
            }
            values.stream()
                    .sorted(Comparator.comparingLong(
                            IWaypoint::getCreationTimestamp).reversed())
                    .limit(20)
                    .forEach(waypoint -> reply(fakePlayer, sender,
                            waypoint.getTag().getName() + " "
                                    + waypoint.getName() + " @ "
                                    + waypoint.getLocation()
                                            .toShortString()));
            return;
        }
        if (action.equals("save")) {
            if (args.length != 4) {
                throw new IllegalArgumentException(
                        "用法: waypoints save <类型> <名称>");
            }
            IWaypoint.Tag tag = waypointTag(args[2]);
            Waypoint waypoint = new Waypoint(
                    args[3], tag,
                    BetterBlockPos.from(fakePlayer.blockPosition()));
            waypoints.addWaypoint(waypoint);
            reply(fakePlayer, sender,
                    "已保存 waypoint: " + tag.getName()
                            + " " + args[3]);
            return;
        }
        if (action.equals("delete") || action.equals("remove")) {
            if (args.length != 4) {
                throw new IllegalArgumentException(
                        "用法: waypoints delete <类型> <名称>");
            }
            IWaypoint.Tag tag = waypointTag(args[2]);
            IWaypoint selected = waypoints.getByTag(tag).stream()
                    .filter(waypoint -> waypoint.getName()
                            .equalsIgnoreCase(args[3]))
                    .max(Comparator.comparingLong(
                            IWaypoint::getCreationTimestamp))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "找不到 waypoint: " + args[3]));
            waypoints.removeWaypoint(selected);
            reply(fakePlayer, sender,
                    "已删除 waypoint: " + args[3]);
            return;
        }
        if (action.equals("goto")) {
            if (args.length < 3 || args.length > 4) {
                throw new IllegalArgumentException(
                        "用法: waypoints goto <类型> [名称]");
            }
            IWaypoint.Tag tag = waypointTag(args[2]);
            IWaypoint selected = args.length == 3
                    ? waypoints.getMostRecentByTag(tag)
                    : waypoints.getByTag(tag).stream()
                            .filter(waypoint -> waypoint.getName()
                                    .equalsIgnoreCase(args[3]))
                            .max(Comparator.comparingLong(
                                    IWaypoint::getCreationTimestamp))
                            .orElse(null);
            if (selected == null) {
                throw new IllegalArgumentException(
                        "找不到对应 waypoint");
            }
            startGoal(sender, fakePlayer, baritone,
                    new GoalBlock(selected.getLocation()),
                    selected.getTag().getName() + " "
                            + selected.getName());
            return;
        }
        throw new IllegalArgumentException(
                "用法: waypoints <list|save|delete|goto>");
    }

    private static void saveHome(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        requireNoArguments(args, "sethome");
        var waypoints = baritone.getWorldProvider()
                .getCurrentWorld().getWaypoints();
        waypoints.addWaypoint(new Waypoint(
                "home", IWaypoint.Tag.HOME,
                BetterBlockPos.from(fakePlayer.blockPosition())));
        reply(fakePlayer, sender, "已保存 home waypoint");
    }

    private static void goHome(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        requireNoArguments(args, "home");
        IWaypoint waypoint = baritone.getWorldProvider()
                .getCurrentWorld().getWaypoints()
                .getMostRecentByTag(IWaypoint.Tag.HOME);
        if (waypoint == null) {
            throw new IllegalArgumentException(
                    "尚未设置 home waypoint");
        }
        startGoal(sender, fakePlayer, baritone,
                new GoalBlock(waypoint.getLocation()), "home");
    }

    private static IWaypoint.Tag waypointTag(String value) {
        IWaypoint.Tag tag = IWaypoint.Tag.getByName(value);
        if (tag == null) {
            throw new IllegalArgumentException(
                    "无效 waypoint 类型: " + value);
        }
        return tag;
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
        if (args.length == 3 && args[1].equalsIgnoreCase("syncmatica")) {
            UUID id;
            try {
                id = UUID.fromString(args[2]);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("无效的 Syncmatica 投影 ID");
            }
            var shared = SyncmaticaBridge.find(fakePlayer.getServer(), id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "找不到该 Syncmatica 共享投影，或文件尚未下载到服务端"));
            String currentDimension = fakePlayer.level().dimension()
                    .location().toString();
            if (!currentDimension.equals(shared.dimension())) {
                throw new IllegalArgumentException(
                        "共享投影位于 " + shared.dimension()
                                + "，假人当前位于 " + currentDimension);
            }
            baritone.cancelAll();
            baritone.getBuilderProcess().setFeedback(
                    message -> reply(fakePlayer, sender, message));
            if (!baritone.getBuilderProcess().buildTransformed(
                    shared.name(), shared.file(), shared.origin(),
                    shared.mirror(), shared.rotation())) {
                baritone.getBuilderProcess().setFeedback(null);
                throw new IllegalArgumentException(
                        "无法读取 Syncmatica 共享投影文件: "
                                + shared.file().getName());
            }
            reply(fakePlayer, sender, "开始在共享位置 "
                    + shared.origin().toShortString() + " 建造 Syncmatica 投影 "
                    + shared.name());
            return;
        }
        boolean explicitFile = (args.length == 3 || args.length == 6)
                && args[1].equalsIgnoreCase("file");
        boolean upstreamFileSyntax = (args.length == 2
                || args.length == 5)
                && !args[1].equalsIgnoreCase("fill")
                && !args[1].equalsIgnoreCase("clear");
        if (explicitFile || upstreamFileSyntax) {
            int fileIndex = explicitFile ? 2 : 1;
            int coordinateIndex = fileIndex + 1;
            boolean customOrigin = args.length == coordinateIndex + 3;
            BlockPos origin = customOrigin
                    ? new BlockPos(
                            coordinate(args[coordinateIndex], "x"),
                            coordinate(args[coordinateIndex + 1], "y"),
                            coordinate(args[coordinateIndex + 2], "z"))
                    : fakePlayer.blockPosition();
            File schematic = resolveSchematicFile(args[fileIndex]);
            baritone.cancelAll();
            baritone.getBuilderProcess().setFeedback(
                    message -> reply(fakePlayer, sender, message));
            if (!baritone.getBuilderProcess().build(
                    schematic.getName(), schematic, origin)) {
                baritone.getBuilderProcess().setFeedback(null);
                throw new IllegalArgumentException(
                        "无法加载蓝图（支持 .schematic、.schem、.litematic）: "
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
            baritone.getBuilderProcess().setFeedback(
                    message -> reply(fakePlayer, sender, message));
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
            baritone.getBuilderProcess().setFeedback(
                    message -> reply(fakePlayer, sender, message));
            baritone.getBuilderProcess().clearArea(a, b);
            reply(fakePlayer, sender, "开始清空区域");
            return;
        }
        throw new IllegalArgumentException(
                "用法: cbi build <蓝图文件> [x y z]，"
                        + "build file <蓝图文件> [x y z]，"
                        + "build syncmatica <共享投影UUID>，"
                        + "build fill <block> <x1 y1 z1 x2 y2 z2>，"
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

    private static File resolveSchematicFile(String value) {
        String suffix = new File(value).getName().contains(".")
                ? "" : "." + Baritone.settings()
                        .schematicFallbackExtension.value;
        File requested = new File(value + suffix);
        if (requested.isAbsolute()) return requested;
        File inSchematicDirectory =
                new File("schematics", value + suffix);
        if (inSchematicDirectory.isFile()) {
            return inSchematicDirectory;
        }
        if (requested.isFile()) return requested;
        // Prefer the canonical server schematic directory in the eventual
        // error message and for GUI-provided relative paths.
        return inSchematicDirectory;
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

    private static void blacklistClosest(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        requireNoArguments(args, "blacklist");
        if (!baritone.getGetToBlockProcess().isActive()) {
            throw new IllegalArgumentException(
                    "GetToBlockProcess 当前没有运行");
        }
        if (!baritone.getGetToBlockProcess().blacklistClosest()) {
            throw new IllegalArgumentException(
                    "没有已知目标可加入黑名单");
        }
        reply(fakePlayer, sender, "已将最近的目标方块加入黑名单");
    }

    private static void findCachedBlocks(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "用法: find <方块> [方块...]");
        }
        BetterBlockPos origin = BetterBlockPos.from(fakePlayer.blockPosition());
        List<BlockPos> found = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            Block target = block(args[index]);
            ServerWorldCache.registerTrackedBlocks(List.of(target));
            found.addAll(baritone.getWorldCache().getLocationsOf(
                    BuiltInRegistries.BLOCK.getKey(target).toString(),
                    64, origin.x, origin.z, 16));
        }
        found.stream()
                .distinct()
                .sorted(Comparator.comparingDouble(origin::distSqr))
                .limit(64)
                .forEach(pos -> reply(fakePlayer, sender,
                        pos.getX() + " " + pos.getY() + " " + pos.getZ()));
        if (found.isEmpty()) {
            reply(fakePlayer, sender,
                    "缓存中没有已知位置；新加入跟踪的方块会从后续区块扫描开始记录");
        }
    }

    private static void startPickup(
            ServerPlayer sender, ServerPlayer fakePlayer,
            Baritone baritone, String[] args) {
        if (args.length == 1) {
            baritone.getFollowProcess().pickup(stack -> true);
            reply(fakePlayer, sender, "开始拾取所有掉落物");
            return;
        }
        java.util.Set<Item> targets = new java.util.HashSet<>();
        for (int index = 1; index < args.length; index++) {
            targets.add(item(args[index]));
        }
        baritone.getFollowProcess().pickup(
                stack -> targets.contains(stack.getItem()));
        reply(fakePlayer, sender, "开始拾取: "
                + targets.stream()
                .map(BuiltInRegistries.ITEM::getKey)
                .map(Object::toString)
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse(""));
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
            if (defaultValue instanceof java.awt.Color) {
                String normalized = text.startsWith("#")
                        ? text.substring(1) : text;
                if (normalized.length() != 6
                        && normalized.length() != 8) {
                    throw new IllegalArgumentException(
                            field.getName()
                                    + " 必须使用 #RRGGBB 或 #AARRGGBB");
                }
                return new java.awt.Color(
                        (int) Long.parseLong(normalized, 16),
                        normalized.length() == 8);
            }
            if (defaultValue instanceof net.minecraft.core.Vec3i) {
                String[] components = text.split("[,:]");
                if (components.length != 3) {
                    throw new IllegalArgumentException(
                            field.getName()
                                    + " 必须使用 x,y,z 格式");
                }
                return new net.minecraft.core.Vec3i(
                        Integer.parseInt(components[0]),
                        Integer.parseInt(components[1]),
                        Integer.parseInt(components[2]));
            }
            if (defaultValue instanceof Enum<?> value) {
                return parseEnum(value.getDeclaringClass(), text);
            }
            if (defaultValue instanceof List<?>) {
                return parseSettingList(field, text);
            }
            if (defaultValue instanceof Map<?, ?>) {
                return parseBlockMap(text);
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
            } else if (element == String.class) {
                values.add(token);
            } else {
                throw new IllegalArgumentException(
                        field.getName() + " 的列表类型暂不支持");
            }
        }
        return values;
    }

    private static Map<Block, List<Block>> parseBlockMap(String text) {
        Map<Block, List<Block>> result = new java.util.LinkedHashMap<>();
        if (text.equalsIgnoreCase("none") || text.equals("{}")) {
            return result;
        }
        for (String mapping : text.split(";")) {
            String[] pair = mapping.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank()
                    || pair[1].isBlank()) {
                throw new IllegalArgumentException(
                        "映射必须使用 source=target1|target2 格式");
            }
            List<Block> replacements = Arrays.stream(pair[1].split("\\|"))
                    .map(BasicGoalCommandHandler::block)
                    .toList();
            result.put(block(pair[0]), new ArrayList<>(replacements));
        }
        return result;
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
        if (value instanceof net.minecraft.core.Vec3i vector) {
            return vector.getX() + "," + vector.getY()
                    + "," + vector.getZ();
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "none";
            return map.entrySet().stream().map(entry ->
                    settingValue(entry.getKey()) + "="
                            + ((List<?>) entry.getValue()).stream()
                            .map(BasicGoalCommandHandler::settingValue)
                            .collect(java.util.stream.Collectors.joining("|")))
                    .collect(java.util.stream.Collectors.joining(";"));
        }
        if (value instanceof java.awt.Color color) {
            return String.format("#%08X", color.getRGB());
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
        startGoal(sender, fakePlayer, baritone, goal, destination, false);
    }

    private static void startGoal(
            ServerPlayer sender,
            ServerPlayer fakePlayer,
            Baritone baritone,
            Goal goal,
            String destination,
            boolean suppressTrashDiscard
    ) {
        reply(fakePlayer, sender, "正在计算到 " + destination + " 的路径");
        // A naked pathToGoal has no process owner and is correctly reclaimed
        // by PathingControlManager on the next tick. Keep direct navigation
        // under CustomGoalProcess, matching upstream goal/path commands.
        baritone.cancelAll();
        baritone.getCustomGoalProcess().setGoalAndPath(
                goal, suppressTrashDiscard);
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
        Block block = BuiltInRegistries.BLOCK.get(id);
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
        Item item = BuiltInRegistries.ITEM.get(id);
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

    private static void showHelp(
            ServerPlayer sender, ServerPlayer fakePlayer,
            String[] args) {
        if (args.length > 1) {
            reply(fakePlayer, sender, "命令帮助查询: " + args[1]);
            if (args[1].equalsIgnoreCase("collectItem")) {
                reply(fakePlayer, sender,
                        "collectItem <物品ID> <数量> "
                                + "[<物品ID> <数量> ...] <接收玩家>");
                reply(fakePlayer, sender,
                        "每种物品独立搜索；缺货项会结束搜索并继续下一项，"
                                + "背包满时会先分批交付。");
                return;
            }
            if (args[1].equalsIgnoreCase("mine")) {
                reply(fakePlayer, sender,
                        "mine <方块ID...> [总数量]；多个 ID 互为备选目标");
                return;
            }
        }
        reply(fakePlayer, sender,
                "导航: goto, come, y, goal, path, surface, thisway, axis, tunnel");
        reply(fakePlayer, sender,
                "任务: mine, areamine, collectItem, farm, build, schematica, litematica, clean, explore, follow, elytra");
        reply(fakePlayer, sender,
                "交互: break, place, giveAll, trash, pickup, blacklist, find");
        reply(fakePlayer, sender,
                "管理: pos1, pos2, sel, waypoint, settings, pause, resume, stop, status, stats");
        reply(fakePlayer, sender,
                "格式: /tell <假人> baritone <命令>；也可使用前缀 cbi");
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
