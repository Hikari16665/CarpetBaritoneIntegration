package baritone.server.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Minimal OpenAI-compatible Responses API client with strict JSON output. */
public final class OpenAiResponsesGateway {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public CompletableFuture<LlmAction> request(
            Configuration configuration, List<Message> messages) {
        ObjectNode body = requestBody(configuration.model(), messages);
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        endpoint(configuration.endpoint()))
                .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "CarpetBaritoneIntegration/1.21.7")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body.toString(), StandardCharsets.UTF_8));
        if (!configuration.apiKey().isBlank()) {
            request.header("Authorization",
                    "Bearer " + configuration.apiKey());
        }
        return client.sendAsync(request.build(),
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8))
                .thenApply(OpenAiResponsesGateway::decode);
    }

    static ObjectNode requestBody(String model, List<Message> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("store", false);
        root.put("max_output_tokens", 512);
        ArrayNode input = root.putArray("input");
        for (Message message : messages) {
            ObjectNode item = input.addObject();
            item.put("role", message.role());
            item.put("content", message.content());
        }
        ObjectNode format = root.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", "cbi_single_action");
        format.put("strict", true);
        ObjectNode schema = format.putObject("schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("operation")
                .put("type", "string")
                .putArray("enum")
                .add("reply").add("propose").add("execute").add("cancel");
        properties.putObject("command").put("type", "string");
        properties.putObject("message").put("type", "string");
        schema.putArray("required")
                .add("operation").add("command").add("message");
        schema.put("additionalProperties", false);
        return root;
    }

    static LlmAction decode(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("LLM HTTP "
                    + response.statusCode() + ": "
                    + compact(response.body(), 240));
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            String output = extractOutputText(root);
            if (output == null || output.isBlank()) {
                throw new IllegalStateException(
                        "LLM response contains no output_text");
            }
            return LlmAction.parse(output);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to decode LLM response", exception);
        }
    }

    static String extractOutputText(JsonNode root) {
        JsonNode convenience = root.get("output_text");
        if (convenience != null && convenience.isTextual()) {
            return convenience.asText();
        }
        JsonNode output = root.get("output");
        if (output == null || !output.isArray()) return null;
        for (JsonNode item : output) {
            JsonNode content = item.get("content");
            if (content == null || !content.isArray()) continue;
            for (JsonNode part : content) {
                if ("output_text".equals(part.path("type").asText())
                        && part.path("text").isTextual()) {
                    return part.path("text").asText();
                }
                if ("refusal".equals(part.path("type").asText())) {
                    throw new IllegalStateException("LLM refused the request: "
                            + compact(part.path("refusal").asText(), 240));
                }
            }
        }
        return null;
    }

    private static URI endpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("LLM endpoint is invalid", exception);
        }
        if (!("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException(
                    "LLM endpoint must use http or https");
        }
        return uri;
    }

    private static String compact(String value, int maximum) {
        String compact = value == null ? "" : value
                .replaceAll("\\s+", " ").trim();
        return compact.length() <= maximum ? compact
                : compact.substring(0, maximum) + "...";
    }

    public record Configuration(
            String endpoint, String model, String apiKey,
            int timeoutSeconds) { }

    public record Message(String role, String content) { }
}
