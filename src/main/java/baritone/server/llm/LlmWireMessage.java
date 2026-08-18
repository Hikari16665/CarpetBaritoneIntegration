package baritone.server.llm;

import java.util.List;

/** Provider-neutral conversation item, including function calls/results. */
public record LlmWireMessage(
        Kind kind, String role, String content,
        List<LlmToolCall> toolCalls, String toolCallId) {
    public LlmWireMessage {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolCallId = toolCallId == null ? "" : toolCallId;
    }

    public static LlmWireMessage message(String role, String content) {
        return new LlmWireMessage(Kind.MESSAGE, role, content, List.of(), "");
    }

    public static LlmWireMessage assistantCalls(List<LlmToolCall> calls) {
        return new LlmWireMessage(
                Kind.ASSISTANT_TOOL_CALLS, "assistant", "", calls, "");
    }

    public static LlmWireMessage toolResult(String callId, String content) {
        return new LlmWireMessage(
                Kind.TOOL_RESULT, "tool", content, List.of(), callId);
    }

    public enum Kind {
        MESSAGE,
        ASSISTANT_TOOL_CALLS,
        TOOL_RESULT
    }
}
