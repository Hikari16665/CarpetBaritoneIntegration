package baritone.server.llm;

import java.util.Locale;

public record LlmDependency(String taskId, Mode mode) {
    public LlmDependency {
        taskId = taskId == null ? "" : taskId.trim();
        if (taskId.isEmpty()) {
            throw new IllegalArgumentException("dependency task_id is empty");
        }
        if (mode == null) {
            throw new IllegalArgumentException("dependency mode is missing");
        }
    }

    public enum Mode {
        REQUIRED,
        OPTIONAL;

        public static Mode parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (Exception exception) {
                throw new IllegalArgumentException(
                        "dependency mode must be required or optional");
            }
        }
    }
}
