package baritone.server.llm;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Performs complete semantic validation before a plan can mutate the world. */
public final class LlmPlanValidator {
    private LlmPlanValidator() { }

    public static ValidatedPlan validate(
            LlmPlan plan, LlmCapabilityRegistry registry, int maximumTasks) {
        if (plan.tasks().isEmpty()) {
            throw new IllegalArgumentException("plan must contain at least one task");
        }
        if (plan.tasks().size() > Math.max(1, maximumTasks)) {
            throw new IllegalArgumentException("plan contains too many tasks");
        }
        Map<String, LlmPlanTask> tasks = new LinkedHashMap<>();
        Map<String, LlmCapabilityRegistry.Capability> capabilities =
                new LinkedHashMap<>();
        for (LlmPlanTask task : plan.tasks()) {
            if (tasks.putIfAbsent(task.id(), task) != null) {
                throw new IllegalArgumentException(
                        "duplicate task id: " + task.id());
            }
            capabilities.put(task.id(),
                    registry.require(task.action(), task.arguments()));
        }
        Map<String, Set<String>> outgoing = new HashMap<>();
        for (LlmPlanTask task : plan.tasks()) {
            Set<String> seenDependencies = new HashSet<>();
            for (LlmDependency dependency : task.dependencies()) {
                if (!tasks.containsKey(dependency.taskId())) {
                    throw new IllegalArgumentException("task " + task.id()
                            + " depends on missing task " + dependency.taskId());
                }
                if (dependency.taskId().equals(task.id())) {
                    throw new IllegalArgumentException(
                            "task cannot depend on itself: " + task.id());
                }
                if (!seenDependencies.add(dependency.taskId())) {
                    throw new IllegalArgumentException("duplicate dependency "
                            + dependency.taskId() + " on " + task.id());
                }
                outgoing.computeIfAbsent(dependency.taskId(), ignored ->
                        new HashSet<>()).add(task.id());
            }
        }
        rejectCycles(tasks);
        for (Map.Entry<String, Set<String>> entry : outgoing.entrySet()) {
            LlmCapabilityRegistry.Capability capability =
                    capabilities.get(entry.getKey());
            LlmPlanTask task = tasks.get(entry.getKey());
            if (capability.completionMode()
                    == LlmCapabilityRegistry.CompletionMode.CONTINUOUS
                    && task.timeoutSeconds() == null) {
                throw new IllegalArgumentException("continuous task "
                        + task.id() + " needs timeout_seconds before dependents");
            }
        }
        boolean confirmation = capabilities.values().stream()
                .anyMatch(LlmCapabilityRegistry.Capability::highImpact);
        return new ValidatedPlan(plan, Map.copyOf(capabilities), confirmation);
    }

    private static void rejectCycles(Map<String, LlmPlanTask> tasks) {
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        tasks.keySet().forEach(id -> incoming.put(id, 0));
        tasks.values().forEach(task -> task.dependencies().forEach(dependency -> {
            incoming.compute(task.id(), (ignored, count) -> count + 1);
            outgoing.computeIfAbsent(dependency.taskId(), ignored ->
                    new java.util.ArrayList<>()).add(task.id());
        }));
        ArrayDeque<String> ready = new ArrayDeque<>();
        incoming.forEach((id, count) -> {
            if (count == 0) ready.add(id);
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited++;
            for (String next : outgoing.getOrDefault(id, List.of())) {
                int remaining = incoming.compute(next,
                        (ignored, count) -> count - 1);
                if (remaining == 0) ready.addLast(next);
            }
        }
        if (visited != tasks.size()) {
            throw new IllegalArgumentException("plan dependency graph contains a cycle");
        }
    }

    public record ValidatedPlan(
            LlmPlan plan,
            Map<String, LlmCapabilityRegistry.Capability> capabilities,
            boolean requiresConfirmation) { }
}
