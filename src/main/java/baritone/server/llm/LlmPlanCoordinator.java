package baritone.server.llm;

import baritone.Baritone;
import baritone.server.BasicGoalCommandHandler;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** One-plan-per-fake-player deterministic AI task scheduler. */
public final class LlmPlanCoordinator {
    public static final LlmPlanCoordinator INSTANCE = new LlmPlanCoordinator();
    private static final Logger LOGGER = LoggerFactory.getLogger("CBI-LLM");
    private static final int MINIMUM_FINITE_TIMEOUT_SECONDS = 300;

    private final Map<UUID, ActivePlan> activePlans = new LinkedHashMap<>();
    private final Map<UUID, PendingReservation> pendingPlans = new LinkedHashMap<>();

    private LlmPlanCoordinator() { }

    public synchronized void start(
            ServerPlayer sender, ServerPlayer fake,
            LlmPlanValidator.ValidatedPlan plan) {
        UUID fakeId = fake.getUUID();
        if (activePlans.containsKey(fakeId)) {
            throw new IllegalStateException("该假人已有正在执行的 AI 计划");
        }
        PendingReservation reservation = pendingPlans.get(fakeId);
        if (reservation != null
                && !reservation.planId.equals(plan.plan().planId())) {
            throw new IllegalStateException("该假人已有等待确认的 AI 计划");
        }
        pendingPlans.remove(fakeId);
        activePlans.put(fakeId, new ActivePlan(
                sender.getUUID(), fakeId, new LlmPlanExecution(plan)));
        LOGGER.info("LLM plan started sender={} fake={} planId={} tasks={} summary={}",
                sender.getScoreboardName(), fake.getScoreboardName(),
                plan.plan().planId(), plan.plan().tasks().size(),
                compact(plan.plan().summary()));
        tell(fake, sender, "计划已开始，共 " + plan.plan().tasks().size()
                + " 个任务：" + nonBlank(plan.plan().summary(), "未命名计划"));
    }

    public synchronized void reservePending(
            ServerPlayer sender, ServerPlayer fake,
            LlmPlanValidator.ValidatedPlan plan, String digest) {
        UUID fakeId = fake.getUUID();
        if (activePlans.containsKey(fakeId)) {
            throw new IllegalStateException("该假人已有正在执行的 AI 计划");
        }
        if (pendingPlans.containsKey(fakeId)) {
            throw new IllegalStateException("该假人已有等待确认的 AI 计划");
        }
        pendingPlans.put(fakeId, new PendingReservation(
                sender.getUUID(), plan.plan().planId(), digest));
        LOGGER.info("LLM plan awaiting confirmation sender={} fake={} planId={} "
                        + "digest={} tasks={}", sender.getScoreboardName(),
                fake.getScoreboardName(), plan.plan().planId(), digest,
                plan.plan().tasks().size());
    }

    public synchronized void releasePending(UUID fakeId, String planId) {
        PendingReservation reservation = pendingPlans.get(fakeId);
        if (reservation != null && (planId == null
                || reservation.planId.equals(planId))) {
            pendingPlans.remove(fakeId);
        }
    }

    public synchronized boolean cancel(ServerPlayer requester,
                                       ServerPlayer fake, String reason) {
        ActivePlan active = activePlans.remove(fake.getUUID());
        if (active == null) return false;
        fake.getServer();
        Baritone baritone = me.nuoyuan.carpetbaritoneintegration
                .Carpetbaritoneintegration.BARITONES.get(fake);
        if (baritone != null) {
            baritone.getTaskLifecycleTracker().cancel(reason);
            baritone.cancelAll();
        }
        tell(fake, requester, "已取消 AI 计划：" + reason);
        LOGGER.info("LLM plan cancelled requester={} fake={} reason={}",
                requester.getScoreboardName(), fake.getScoreboardName(),
                compact(reason));
        return true;
    }

    public synchronized Snapshot snapshot(UUID fakeId) {
        ActivePlan active = activePlans.get(fakeId);
        if (active == null) return null;
        return new Snapshot(
                active.execution.validated().plan().planId(),
                active.execution.validated().plan().summary(),
                active.execution.results());
    }

    /** Called on the server thread from Baritone.tick. */
    public synchronized void tick(Baritone baritone, long tick) {
        ServerPlayer fake = baritone.getPlayerContext().player();
        ActivePlan active = activePlans.get(fake.getUUID());
        if (active == null) return;
        MinecraftServer server = baritone.getPlayerContext().server();
        ServerPlayer sender = server.getPlayerList().getPlayer(active.senderId);

        TaskLifecycleTracker.TerminalEvent terminal =
                baritone.getTaskLifecycleTracker().pollTerminal();
        if (terminal != null) {
            active.execution.finish(terminal.taskId(), terminal.status(),
                    terminal.code(), terminal.summary(), tick,
                    terminal.details());
            LOGGER.info("LLM task terminal fake={} planId={} taskId={} status={} "
                            + "code={} summary={}", fake.getScoreboardName(),
                    active.execution.validated().plan().planId(), terminal.taskId(),
                    terminal.status(), terminal.code(), compact(terminal.summary()));
            if (terminal.status() == LlmTaskStatus.TIMED_OUT) {
                baritone.cancelAll();
            }
        } else {
            TaskLifecycleTracker.Snapshot lifecycle =
                    baritone.getTaskLifecycleTracker().snapshot();
            if (lifecycle != null) {
                if (lifecycle.status() == LlmTaskStatus.PAUSED) {
                    active.execution.pause(lifecycle.taskId(),
                            "PROCESS_PAUSED", "任务等待可恢复条件");
                } else {
                    active.execution.resume(lifecycle.taskId());
                }
            }
        }

        if (active.execution.complete()) {
            activePlans.remove(fake.getUUID());
            LOGGER.info("LLM plan complete fake={} planId={} resultCount={}",
                    fake.getScoreboardName(),
                    active.execution.validated().plan().planId(),
                    active.execution.results().size());
            if (sender != null) tell(fake, sender, summary(active.execution));
            return;
        }
        LlmPlanTask task = active.execution.nextReady().orElse(null);
        if (task == null) return;
        if (sender == null) {
            active.execution.started(task.id(), tick);
            active.execution.finish(task.id(), LlmTaskStatus.FAILED,
                    "SENDER_OFFLINE", "计划发送者已离线", tick, Map.of());
            return;
        }
        LlmCapabilityRegistry.Capability capability = active.execution
                .validated().capabilities().get(task.id());
        BasicGoalCommandHandler.ExecutionResult submission =
                BasicGoalCommandHandler.executeDirect(sender, fake,
                        task.command());
        active.execution.started(task.id(), tick);
        Integer effectiveTimeout = effectiveTimeoutSeconds(task, capability);
        LOGGER.info("LLM task submitted sender={} fake={} planId={} taskId={} "
                        + "action={} success={} proposedTimeoutSeconds={} "
                        + "effectiveTimeoutSeconds={}", sender.getScoreboardName(),
                fake.getScoreboardName(),
                active.execution.validated().plan().planId(), task.id(),
                task.action(), submission.success(), task.timeoutSeconds(),
                effectiveTimeout);
        if (!submission.success()) {
            active.execution.finish(task.id(), LlmTaskStatus.FAILED,
                    "COMMAND_REJECTED", submission.message(), tick, Map.of());
            return;
        }
        baritone.getTaskLifecycleTracker().start(
                active.execution.validated().plan().planId(), task.id(),
                capability.completionMode(),
                effectiveTimeout, tick,
                baritone.hasActiveTask());
    }

    private static String summary(LlmPlanExecution execution) {
        long succeeded = execution.results().values().stream()
                .filter(result -> result.status() == LlmTaskStatus.SUCCEEDED)
                .count();
        long failed = execution.results().values().stream()
                .filter(result -> result.status() == LlmTaskStatus.FAILED
                        || result.status() == LlmTaskStatus.TIMED_OUT)
                .count();
        long skipped = execution.results().values().stream()
                .filter(result -> result.status() == LlmTaskStatus.SKIPPED)
                .count();
        String failures = execution.results().values().stream()
                .filter(result -> result.status() != LlmTaskStatus.SUCCEEDED
                        && result.status() != LlmTaskStatus.CANCELLED)
                .map(result -> result.taskId() + "="
                        + result.status().name().toLowerCase(Locale.ROOT)
                        + "(" + result.summary() + ")")
                .limit(6)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return "计划结束：成功 " + succeeded + "，失败 " + failed
                + "，跳过 " + skipped
                + (failures.isEmpty() ? "" : "；" + failures);
    }

    private static void tell(ServerPlayer fake, ServerPlayer recipient,
                             String message) {
        MinecraftServer server = fake.getServer();
        if (server == null) return;
        LOGGER.info("LLM plan reply fake={} recipient={} message={}",
                fake.getScoreboardName(), recipient.getScoreboardName(),
                compact(message));
        String command = "tell " + StringArgumentType.escapeIfRequired(
                recipient.getScoreboardName()) + " "
                + StringArgumentType.escapeIfRequired("[CBI-AI] " + message);
        server.getCommands().performPrefixedCommand(
                fake.createCommandSourceStack(), command);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String compact(String value) {
        String result = value == null ? "" : value
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(llmApiKey\\s+)(\\\"[^\\\"]*\\\"|'[^']*'|\\S+)",
                        "$1<redacted>")
                .replaceAll("(?i)\\bsk-[A-Za-z0-9_-]{8,}\\b",
                        "<redacted-key>").trim();
        return result.length() <= 500 ? result
                : result.substring(0, 500) + "...";
    }

    /** A model-proposed timeout is a safety bound, not permission to make a
     * legitimate pathing task impossible. Initial snapshot warmup, queueing,
     * retries and block interaction can easily exceed a conversational
     * 30-second guess, so finite Baritone processes get a conservative floor.
     * Continuous tasks retain the exact timeout because it defines their
     * intended run duration. */
    static Integer effectiveTimeoutSeconds(
            LlmPlanTask task,
            LlmCapabilityRegistry.Capability capability) {
        Integer proposed = task.timeoutSeconds();
        if (proposed == null) return null;
        if (capability.completionMode()
                != LlmCapabilityRegistry.CompletionMode.FINITE) {
            return proposed;
        }
        return Math.max(MINIMUM_FINITE_TIMEOUT_SECONDS, proposed);
    }

    private record ActivePlan(
            UUID senderId, UUID fakeId, LlmPlanExecution execution) { }
    private record PendingReservation(
            UUID senderId, String planId, String digest) { }

    public record Snapshot(
            String planId, String summary,
            Map<String, LlmPlanExecution.LlmTaskResult> results) { }
}
