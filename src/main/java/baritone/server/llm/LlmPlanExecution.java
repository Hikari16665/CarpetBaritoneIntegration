package baritone.server.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Deterministic single-worker execution state for a validated task DAG. */
public final class LlmPlanExecution {
    private final LlmPlanValidator.ValidatedPlan validated;
    private final Map<String, LlmTaskResult> results = new LinkedHashMap<>();

    public LlmPlanExecution(LlmPlanValidator.ValidatedPlan validated) {
        this.validated = validated;
        validated.plan().tasks().forEach(task -> results.put(task.id(),
                new LlmTaskResult(task.id(), LlmTaskStatus.PLANNED,
                        "PLANNED", "等待前置任务", -1L, -1L, Map.of())));
    }

    public Optional<LlmPlanTask> nextReady() {
        if (results.values().stream().anyMatch(result ->
                result.status() == LlmTaskStatus.RUNNING
                        || result.status() == LlmTaskStatus.PAUSED)) {
            return Optional.empty();
        }
        for (LlmPlanTask task : validated.plan().tasks()) {
            LlmTaskResult current = results.get(task.id());
            if (current.status().terminal()) continue;
            boolean waiting = false;
            boolean requiredFailed = false;
            for (LlmDependency dependency : task.dependencies()) {
                LlmTaskResult prerequisite = results.get(dependency.taskId());
                if (!prerequisite.status().terminal()) {
                    waiting = true;
                    break;
                }
                if (dependency.mode() == LlmDependency.Mode.REQUIRED
                        && prerequisite.status() != LlmTaskStatus.SUCCEEDED) {
                    requiredFailed = true;
                }
            }
            if (waiting) continue;
            if (requiredFailed) {
                results.put(task.id(), new LlmTaskResult(
                        task.id(), LlmTaskStatus.SKIPPED,
                        "REQUIRED_DEPENDENCY_FAILED",
                        "必选前置任务未成功", -1L, -1L, Map.of()));
                continue;
            }
            results.put(task.id(), current.withStatus(
                    LlmTaskStatus.READY, "READY", "可以执行", -1L));
            return Optional.of(task);
        }
        return Optional.empty();
    }

    public void started(String taskId, long tick) {
        replace(taskId, new LlmTaskResult(taskId, LlmTaskStatus.RUNNING,
                "RUNNING", "任务执行中", tick, -1L, Map.of()));
    }

    public void finish(String taskId, LlmTaskStatus status, String code,
                       String summary, long tick, Map<String, String> details) {
        if (!status.terminal() && status != LlmTaskStatus.PAUSED) {
            throw new IllegalArgumentException("invalid result status " + status);
        }
        LlmTaskResult previous = require(taskId);
        replace(taskId, new LlmTaskResult(taskId, status, code, summary,
                previous.startedTick(), tick, details));
    }

    public void resume(String taskId) {
        LlmTaskResult previous = require(taskId);
        if (previous.status() != LlmTaskStatus.PAUSED) return;
        replace(taskId, previous.withStatus(
                LlmTaskStatus.RUNNING, "RUNNING", "任务已恢复", -1L));
    }

    public void pause(String taskId, String code, String summary) {
        LlmTaskResult previous = require(taskId);
        if (previous.status() != LlmTaskStatus.RUNNING) return;
        replace(taskId, new LlmTaskResult(taskId, LlmTaskStatus.PAUSED,
                code, summary, previous.startedTick(), -1L,
                previous.details()));
    }

    public boolean complete() {
        nextReady();
        return results.values().stream().allMatch(
                result -> result.status().terminal());
    }

    public LlmPlanValidator.ValidatedPlan validated() { return validated; }
    public Map<String, LlmTaskResult> results() { return Map.copyOf(results); }

    private LlmTaskResult require(String taskId) {
        LlmTaskResult result = results.get(taskId);
        if (result == null) throw new IllegalArgumentException(
                "unknown task " + taskId);
        return result;
    }

    private void replace(String taskId, LlmTaskResult result) {
        require(taskId);
        results.put(taskId, result);
    }

    public record LlmTaskResult(
            String taskId,
            LlmTaskStatus status,
            String code,
            String summary,
            long startedTick,
            long finishedTick,
            Map<String, String> details) {
        public LlmTaskResult {
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        private LlmTaskResult withStatus(
                LlmTaskStatus next, String nextCode,
                String nextSummary, long finished) {
            return new LlmTaskResult(taskId, next, nextCode, nextSummary,
                    startedTick, finished, details);
        }
    }
}
