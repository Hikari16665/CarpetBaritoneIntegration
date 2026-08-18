package baritone.server.llm;

import java.util.List;

public record LlmModelTurn(List<LlmToolCall> toolCalls, String text) {
    public LlmModelTurn {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        text = text == null ? "" : text;
    }
}
