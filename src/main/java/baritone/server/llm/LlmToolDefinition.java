package baritone.server.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record LlmToolDefinition(
        String name, String description, ObjectNode parameters) { }
