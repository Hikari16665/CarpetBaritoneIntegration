package baritone.server;

import baritone.Baritone;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Edge-triggered public messages for states that actually need an operator's
 * attention. Routine movement, eating, combat and hazard avoidance stay quiet.
 */
public final class FakePlayerStatusMessenger {
    private static final int MAX_MESSAGE_LENGTH = 256;
    private static final int STUCK_MIN_TICKS = 200;
    private static final int STUCK_FAILURES = 3;

    private final Baritone baritone;
    private final AutoEatController autoEat;
    private final Map<Event, Long> lastSent = new EnumMap<>(Event.class);
    private final Map<Event, Boolean> active = new EnumMap<>(Event.class);
    private boolean wasFinalPathFailure;
    private Vec3 lastProgressPosition;
    private long lastProgressTick;
    private int observedPathFailures;
    private String taskName = "任务";
    private long taskStartTick = -1L;

    public FakePlayerStatusMessenger(Baritone baritone,
                                     AutoEatController autoEat) {
        this.baritone = baritone;
        this.autoEat = autoEat;
    }

    /** Polls only the few conditions that have no authoritative process event. */
    public void tick() {
        ServerPlayer player = baritone.getPlayerContext().player();
        int food = player.getFoodData().getFoodLevel();
        int threshold = Math.max(0, Math.min(20,
                Baritone.settings().autoEatHungerThreshold.value));
        boolean needsFood = food < threshold
                || player.getHealth() < player.getMaxHealth() && food < 20;
        condition(Event.NO_FOOD, needsFood && !autoEat.hasAvailableFood(),
                Map.of());

        boolean finalFailure = baritone.getConsecutivePathFailures() > 0
                && baritone.getActiveGoal() == null
                && baritone.hasActiveTask();
        if (finalFailure && !wasFinalPathFailure) {
            taskFailure("多次自动重算后仍找不到可行路径");
        }
        wasFinalPathFailure = finalFailure;
        updateStuckState(player);
    }

    private void updateStuckState(ServerPlayer player) {
        long now = player.level().getGameTime();
        if (!baritone.hasActiveTask()) {
            lastProgressPosition = player.position();
            lastProgressTick = now;
            observedPathFailures = 0;
            condition(Event.STUCK, false, Map.of());
            return;
        }
        if (lastProgressPosition == null
                || player.position().distanceToSqr(lastProgressPosition)
                >= 0.25D) {
            lastProgressPosition = player.position();
            lastProgressTick = now;
            observedPathFailures = 0;
            condition(Event.STUCK, false, Map.of());
        }
        observedPathFailures = Math.max(observedPathFailures,
                baritone.getConsecutivePathFailures());
        boolean stuck = now - lastProgressTick >= STUCK_MIN_TICKS
                && observedPathFailures >= STUCK_FAILURES;
        condition(Event.STUCK, stuck, Map.of());
    }

    public void beginTask(String name) {
        taskName = nonBlank(name, "任务");
        taskStartTick = now();
        wasFinalPathFailure = false;
        condition(Event.TASK_FAILURE, false, Map.of());
        condition(Event.STUCK, false, Map.of());
        condition(Event.MISSING_TOOL, false, Map.of());
        condition(Event.MISSING_MATERIALS, false, Map.of());
        condition(Event.NO_BRIDGE_BLOCKS, false, Map.of());
        condition(Event.INVENTORY_BLOCKED, false, Map.of());
    }

    public void cancelTask() {
        baritone.getTaskLifecycleTracker().cancel("任务已取消");
        taskStartTick = -1L;
        taskName = "任务";
        for (Event event : Event.values()) {
            if (event != Event.NO_FOOD) active.put(event, false);
        }
    }

    public void taskFailure(String reason) {
        baritone.getTaskLifecycleTracker().fail(
                "TASK_FAILED", nonBlank(reason, "未知原因"), Map.of());
        alert(Event.TASK_FAILURE, values("task", taskName,
                "reason", nonBlank(reason, "未知原因")));
        taskStartTick = -1L;
    }

    public void taskComplete(String summary) {
        baritone.getTaskLifecycleTracker().succeed(
                "TASK_COMPLETE", nonBlank(summary, taskName + "已完成"),
                Map.of());
        long elapsed = taskStartTick < 0L ? 0L : now() - taskStartTick;
        int minimum = Math.max(0,
                Baritone.settings().fakePlayerCompletionMessageMinTicks.value);
        if (elapsed >= minimum) {
            alert(Event.TASK_COMPLETE, values("task", taskName,
                    "summary", nonBlank(summary, taskName + "已完成")));
        }
        taskStartTick = -1L;
    }

    public void missingTool(boolean missing, String tool, String fallback) {
        condition(Event.MISSING_TOOL, missing, values(
                "tool", nonBlank(tool, "合适工具"),
                "fallback", nonBlank(fallback, "空手")));
    }

    public void builderMissingMaterials(boolean missing, String items) {
        if (missing) {
            baritone.getTaskLifecycleTracker().pause(
                    "MISSING_MATERIALS", nonBlank(items, "未识别的建材"));
        } else {
            baritone.getTaskLifecycleTracker().resume();
        }
        condition(Event.MISSING_MATERIALS, missing,
                values("items", nonBlank(items, "未识别的建材")));
    }

    public void noBridgeBlocks(boolean missing) {
        condition(Event.NO_BRIDGE_BLOCKS, missing, Map.of());
    }

    public void inventoryBlocked(String task, String reason) {
        condition(Event.INVENTORY_BLOCKED, true, values(
                "task", nonBlank(task, taskName),
                "reason", nonBlank(reason, "没有可用空位")));
    }

    public void inventoryUnblocked() {
        condition(Event.INVENTORY_BLOCKED, false, Map.of());
    }

    public void collectIncomplete(String summary) {
        baritone.getTaskLifecycleTracker().fail(
                "COLLECT_INCOMPLETE", nonBlank(summary, "未找到全部物品"),
                Map.of("summary", nonBlank(summary, "未找到全部物品")));
        alert(Event.COLLECT_INCOMPLETE,
                values("summary", nonBlank(summary, "未找到全部物品")));
        taskStartTick = -1L;
    }

    public void targetUnavailable(String target, String reason) {
        baritone.getTaskLifecycleTracker().fail(
                "TARGET_UNAVAILABLE", nonBlank(reason, "目标不可用"),
                values("target", nonBlank(target, "未知目标")));
        alert(Event.TARGET_UNAVAILABLE, values(
                "target", nonBlank(target, "未知目标"),
                "reason", nonBlank(reason, "离线、跨维度或不可达")));
        taskStartTick = -1L;
    }

    private void condition(Event event, boolean present,
                           Map<String, String> values) {
        if (!Baritone.settings().fakePlayerPublicMessages.value) {
            active.put(event, false);
            return;
        }
        boolean previous = active.getOrDefault(event, false);
        active.put(event, present);
        if (present && !previous) announce(event, values);
    }

    private void alert(Event event, Map<String, String> values) {
        announce(event, values);
    }

    private void announce(Event event, Map<String, String> values) {
        if (!Baritone.settings().fakePlayerPublicMessages.value) return;
        ServerPlayer player = baritone.getPlayerContext().player();
        long now = player.level().getGameTime();
        long cooldown = Math.max(0,
                Baritone.settings().fakePlayerMessageCooldownTicks.value);
        long previous = lastSent.getOrDefault(event, Long.MIN_VALUE / 2);
        if (now - previous < cooldown) return;
        String template = templateFor(event);
        if (template == null || template.isBlank()) return;
        String message = renderTemplate(template, player, values);
        if (message.isBlank()) return;
        lastSent.put(event, now);
        MinecraftServer server = baritone.getPlayerContext().server();
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(
                    "<" + player.getScoreboardName() + "> " + message), false);
        }
    }

    static String renderTemplate(String template, ServerPlayer player,
                                 Map<String, String> supplied) {
        BlockPos pos = player.blockPosition();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", player.getScoreboardName());
        values.put("food", Integer.toString(
                player.getFoodData().getFoodLevel()));
        values.put("health", oneDecimal(player.getHealth()));
        values.put("max_health", oneDecimal(player.getMaxHealth()));
        values.put("x", Integer.toString(pos.getX()));
        values.put("y", Integer.toString(pos.getY()));
        values.put("z", Integer.toString(pos.getZ()));
        values.putAll(supplied);
        return renderTemplate(template, values);
    }

    static String renderTemplate(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            rendered = rendered.replace("{" + value.getKey() + "}",
                    value.getValue());
        }
        rendered = rendered.replace('\r', ' ').replace('\n', ' ').trim();
        return rendered.length() <= MAX_MESSAGE_LENGTH ? rendered
                : rendered.substring(0, MAX_MESSAGE_LENGTH);
    }

    private static Map<String, String> values(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            result.put(pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private long now() {
        return baritone.getPlayerContext().world().getGameTime();
    }

    private static String oneDecimal(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String templateFor(Event event) {
        return switch (event) {
            case NO_FOOD -> Baritone.settings().fakePlayerNoFoodMessage.value;
            case TASK_FAILURE -> Baritone.settings()
                    .fakePlayerTaskFailureMessage.value;
            case MISSING_TOOL -> Baritone.settings()
                    .fakePlayerMissingToolMessage.value;
            case MISSING_MATERIALS -> Baritone.settings()
                    .fakePlayerBuilderMissingMaterialsMessage.value;
            case NO_BRIDGE_BLOCKS -> Baritone.settings()
                    .fakePlayerNoBridgeBlocksMessage.value;
            case INVENTORY_BLOCKED -> Baritone.settings()
                    .fakePlayerInventoryBlockedMessage.value;
            case COLLECT_INCOMPLETE -> Baritone.settings()
                    .fakePlayerCollectIncompleteMessage.value;
            case TARGET_UNAVAILABLE -> Baritone.settings()
                    .fakePlayerTargetUnavailableMessage.value;
            case STUCK -> Baritone.settings().fakePlayerStuckMessage.value;
            case TASK_COMPLETE -> Baritone.settings()
                    .fakePlayerTaskCompleteMessage.value;
        };
    }

    enum Event {
        NO_FOOD,
        TASK_FAILURE,
        MISSING_TOOL,
        MISSING_MATERIALS,
        NO_BRIDGE_BLOCKS,
        INVENTORY_BLOCKED,
        COLLECT_INCOMPLETE,
        TARGET_UNAVAILABLE,
        STUCK,
        TASK_COMPLETE
    }
}
