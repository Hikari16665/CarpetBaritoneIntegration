package baritone.server.llm;

import java.util.Map;

/** Per-Baritone authoritative lifecycle bridge used by the AI scheduler. */
public final class TaskLifecycleTracker {
    private ActiveTask active;
    private TerminalEvent terminal;

    public synchronized void start(
            String planId, String taskId,
            LlmCapabilityRegistry.CompletionMode completionMode,
            Integer timeoutSeconds, long tick, boolean processActive) {
        if (active != null) {
            throw new IllegalStateException("an AI task is already active");
        }
        active = new ActiveTask(planId, taskId, completionMode,
                timeoutSeconds == null ? -1L
                        : tick + timeoutSeconds * 20L,
                tick, processActive, false);
        terminal = null;
        if (completionMode == LlmCapabilityRegistry.CompletionMode.INSTANT) {
            succeed("COMMAND_APPLIED", "命令已应用", Map.of());
        }
    }

    public synchronized void tick(long tick, boolean processActive) {
        if (active == null || terminal != null) return;
        if (active.deadlineTick >= 0L && tick >= active.deadlineTick) {
            finish(LlmTaskStatus.TIMED_OUT, "TASK_TIMEOUT",
                    "任务超过计划时限", Map.of());
            return;
        }
        // A recoverable process (notably Builder waiting for materials) can
        // legitimately have no active path. It remains paused until its
        // authoritative process event resumes it or the explicit deadline
        // expires; absence of pathing is not a failure in this state.
        if (active.paused) return;
        boolean seen = active.seenActive || processActive;
        active = active.withObservation(seen, processActive);
        if (active.completionMode
                == LlmCapabilityRegistry.CompletionMode.FINITE
                && !processActive && (seen || tick - active.startedTick >= 5L)) {
            finish(LlmTaskStatus.FAILED, "PROCESS_ENDED_WITHOUT_RESULT",
                    "任务进程结束但没有提供完成结果", Map.of());
        }
    }

    public synchronized void succeed(String code, String summary,
                                     Map<String, String> details) {
        finish(LlmTaskStatus.SUCCEEDED, code, summary, details);
    }

    public synchronized void fail(String code, String summary,
                                  Map<String, String> details) {
        finish(LlmTaskStatus.FAILED, code, summary, details);
    }

    public synchronized void pause(String code, String summary) {
        if (active == null || terminal != null) return;
        active = active.withPaused(true);
    }

    public synchronized void resume() {
        if (active != null) active = active.withPaused(false);
    }

    public synchronized void cancel(String summary) {
        finish(LlmTaskStatus.CANCELLED, "TASK_CANCELLED", summary, Map.of());
    }

    public synchronized TerminalEvent pollTerminal() {
        TerminalEvent result = terminal;
        if (result != null) {
            terminal = null;
            active = null;
        }
        return result;
    }

    public synchronized Snapshot snapshot() {
        if (active == null) return null;
        return new Snapshot(active.planId, active.taskId,
                active.paused ? LlmTaskStatus.PAUSED : LlmTaskStatus.RUNNING,
                active.startedTick, active.deadlineTick);
    }

    private void finish(LlmTaskStatus status, String code, String summary,
                        Map<String, String> details) {
        if (active == null || terminal != null) return;
        terminal = new TerminalEvent(active.planId, active.taskId,
                status, code, summary, details == null ? Map.of() : details);
    }

    private record ActiveTask(
            String planId,
            String taskId,
            LlmCapabilityRegistry.CompletionMode completionMode,
            long deadlineTick,
            long startedTick,
            boolean seenActive,
            boolean paused) {
        private ActiveTask withObservation(boolean seen, boolean ignoredActive) {
            return new ActiveTask(planId, taskId, completionMode,
                    deadlineTick, startedTick, seen, paused);
        }
        private ActiveTask withPaused(boolean value) {
            return new ActiveTask(planId, taskId, completionMode,
                    deadlineTick, startedTick, seenActive, value);
        }
    }

    public record Snapshot(
            String planId, String taskId, LlmTaskStatus status,
            long startedTick, long deadlineTick) { }

    public record TerminalEvent(
            String planId, String taskId, LlmTaskStatus status,
            String code, String summary, Map<String, String> details) {
        public TerminalEvent {
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }
}
