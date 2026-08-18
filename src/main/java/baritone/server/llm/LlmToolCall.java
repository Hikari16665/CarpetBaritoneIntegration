package baritone.server.llm;

public record LlmToolCall(String id, String name, String arguments) {
    public LlmToolCall {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        arguments = arguments == null || arguments.isBlank()
                ? "{}" : arguments;
    }
}
