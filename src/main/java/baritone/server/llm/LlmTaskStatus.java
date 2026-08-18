package baritone.server.llm;

/** Authoritative lifecycle state of one task in an AI plan. */
public enum LlmTaskStatus {
    PLANNED,
    READY,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED,
    TIMED_OUT;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED
                || this == CANCELLED || this == TIMED_OUT;
    }
}
