package baritone.server.llm;

import java.util.List;

public record LlmPlanTask(
        String id,
        String action,
        List<String> arguments,
        List<LlmDependency> dependencies,
        Integer timeoutSeconds) {
    public LlmPlanTask {
        id = id == null ? "" : id.trim();
        action = action == null ? "" : action.trim().toLowerCase(
                java.util.Locale.ROOT);
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        dependencies = dependencies == null
                ? List.of() : List.copyOf(dependencies);
        if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid task id: " + id);
        }
        if (action.isEmpty()) {
            throw new IllegalArgumentException("task action is empty: " + id);
        }
        if (arguments.size() > 64) {
            throw new IllegalArgumentException("too many arguments: " + id);
        }
        for (String argument : arguments) {
            if (argument == null || argument.length() > 256
                    || argument.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "invalid argument in task: " + id);
            }
        }
        if (timeoutSeconds != null
                && (timeoutSeconds < 1 || timeoutSeconds > 86_400)) {
            throw new IllegalArgumentException(
                    "timeout_seconds must be between 1 and 86400");
        }
    }

    public String command() {
        if (arguments.isEmpty()) return action;
        return action + " " + String.join(" ", arguments);
    }
}
