package baritone.server.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Immutable, server-validated task graph proposed by the model. */
public record LlmPlan(String planId, String summary, List<LlmPlanTask> tasks) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public LlmPlan {
        planId = planId == null || planId.isBlank()
                ? UUID.randomUUID().toString() : planId.trim();
        summary = summary == null ? "" : summary.trim();
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    public static LlmPlan parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("plan must be an object");
        }
        List<LlmPlanTask> tasks = new ArrayList<>();
        JsonNode taskNodes = node.get("tasks");
        if (taskNodes == null || !taskNodes.isArray()) {
            throw new IllegalArgumentException("plan.tasks must be an array");
        }
        for (JsonNode task : taskNodes) {
            if (!task.isObject()) {
                throw new IllegalArgumentException("plan task must be an object");
            }
            List<String> arguments = new ArrayList<>();
            JsonNode argumentNodes = task.path("arguments");
            if (!argumentNodes.isArray()) {
                throw new IllegalArgumentException("task.arguments must be an array");
            }
            argumentNodes.forEach(argument -> {
                if (!argument.isTextual()) {
                    throw new IllegalArgumentException(
                            "task arguments must be strings");
                }
                arguments.add(argument.asText());
            });
            List<LlmDependency> dependencies = new ArrayList<>();
            JsonNode dependencyNodes = task.path("depends_on");
            if (!dependencyNodes.isMissingNode()
                    && !dependencyNodes.isArray()) {
                throw new IllegalArgumentException("task.depends_on must be an array");
            }
            dependencyNodes.forEach(dependency -> dependencies.add(
                    new LlmDependency(
                            requiredText(dependency, "task_id"),
                            LlmDependency.Mode.parse(
                                    requiredText(dependency, "mode")))));
            JsonNode timeout = task.get("timeout_seconds");
            tasks.add(new LlmPlanTask(
                    requiredText(task, "id"),
                    requiredText(task, "action"),
                    arguments, dependencies,
                    timeout == null || timeout.isNull()
                            ? null : timeout.asInt()));
        }
        return new LlmPlan("", node.path("summary").asText(""), tasks);
    }

    public ObjectNode toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("plan_id", planId);
        root.put("summary", summary);
        ArrayNode outputTasks = root.putArray("tasks");
        for (LlmPlanTask task : tasks) {
            ObjectNode output = outputTasks.addObject();
            output.put("id", task.id());
            output.put("action", task.action());
            ArrayNode arguments = output.putArray("arguments");
            task.arguments().forEach(arguments::add);
            ArrayNode dependencies = output.putArray("depends_on");
            task.dependencies().forEach(dependency -> dependencies.addObject()
                    .put("task_id", dependency.taskId())
                    .put("mode", dependency.mode().name().toLowerCase(
                            java.util.Locale.ROOT)));
            if (task.timeoutSeconds() == null) output.putNull("timeout_seconds");
            else output.put("timeout_seconds", task.timeoutSeconds());
        }
        return root;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.asText();
    }
}
