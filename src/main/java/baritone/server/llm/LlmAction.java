package baritone.server.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/** One model decision. It can contain at most one executable CBI command. */
public record LlmAction(Operation operation, String command, String message) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_COMMAND_LENGTH = 512;
    private static final int MAX_MESSAGE_LENGTH = 1_000;

    public LlmAction {
        if (operation == null) {
            throw new IllegalArgumentException("LLM response is missing operation");
        }
        command = command == null ? "" : command.trim();
        message = message == null ? "" : message.trim();
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException("LLM command is too long");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("LLM message is too long");
        }
        if ((operation == Operation.EXECUTE
                || operation == Operation.PROPOSE) && command.isEmpty()) {
            throw new IllegalArgumentException(
                    operation.name().toLowerCase(Locale.ROOT)
                            + " requires a command");
        }
        if ((operation == Operation.REPLY
                || operation == Operation.CANCEL) && !command.isEmpty()) {
            throw new IllegalArgumentException(
                    operation.name().toLowerCase(Locale.ROOT)
                            + " cannot contain a command");
        }
    }

    public static LlmAction parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(stripFence(json));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(
                        "LLM response is not a JSON object");
            }
            JsonNode operation = root.get("operation");
            JsonNode command = root.get("command");
            JsonNode message = root.get("message");
            if (operation == null || !operation.isTextual()
                    || command == null || !command.isTextual()
                    || message == null || !message.isTextual()) {
                throw new IllegalArgumentException(
                        "LLM response fields have invalid types");
            }
            Operation parsedOperation = Operation.valueOf(
                    operation.asText().trim().toUpperCase(Locale.ROOT));
            return new LlmAction(parsedOperation, command.asText(),
                    message.asText());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            if (exception instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException(
                    "Unable to parse structured LLM response", exception);
        }
    }

    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("operation", operation.name().toLowerCase(Locale.ROOT));
        root.put("command", command);
        root.put("message", message);
        return root.toString();
    }

    private static String stripFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstLine = trimmed.indexOf('\n');
        int closing = trimmed.lastIndexOf("```");
        if (firstLine < 0 || closing <= firstLine) return trimmed;
        return trimmed.substring(firstLine + 1, closing).trim();
    }

    public enum Operation {
        REPLY,
        PROPOSE,
        EXECUTE,
        CANCEL
    }
}
