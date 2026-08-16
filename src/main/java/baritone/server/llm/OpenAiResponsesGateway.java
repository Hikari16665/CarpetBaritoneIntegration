package baritone.server.llm;

import baritone.api.LlmApiMode;
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

/** OpenAI-compatible Responses and Chat Completions client. */
public final class OpenAiResponsesGateway {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public CompletableFuture<LlmAction> request(
            Configuration configuration, List<Message> messages) {
        Protocol protocol = protocol(
                configuration.apiMode(), configuration.baseUrl());
        ObjectNode body = protocol == Protocol.RESPONSES
                ? requestBody(configuration.model(), messages)
                : chatCompletionsRequestBody(
                        configuration.model(), messages);
        URI endpoint = protocol == Protocol.RESPONSES
                ? responsesEndpoint(configuration.baseUrl())
                : chatCompletionsEndpoint(configuration.baseUrl());
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        endpoint)
                .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "CarpetBaritoneIntegration/1.21.7")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body.toString(), StandardCharsets.UTF_8));
        String apiKey = normalizeApiKey(configuration.apiKey());
        if (!apiKey.isBlank()) {
            request.header("Authorization",
                    "Bearer " + apiKey);
        }
        return client.sendAsync(request.build(),
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8))
                .thenApply(response -> decode(response, protocol));
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

    static ObjectNode chatCompletionsRequestBody(
            String model, List<Message> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        root.put("max_tokens", 512);
        ArrayNode serialized = root.putArray("messages");
        for (Message message : messages) {
            ObjectNode item = serialized.addObject();
            item.put("role", message.role());
            item.put("content", message.content());
        }
        root.putObject("response_format").put("type", "json_object");
        return root;
    }

    static LlmAction decode(HttpResponse<String> response) {
        return decode(response, Protocol.RESPONSES);
    }

    private static LlmAction decode(
            HttpResponse<String> response, Protocol protocol) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(httpError(
                    response.statusCode(), response.body()));
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            String output = protocol == Protocol.RESPONSES
                    ? extractOutputText(root)
                    : extractChatCompletionText(root);
            if (output == null || output.isBlank()) {
                throw new IllegalStateException(
                        "LLM response contains no assistant output");
            }
            return LlmAction.parse(output);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to decode LLM response", exception);
        }
    }

    static String extractChatCompletionText(JsonNode root) {
        JsonNode content = root.path("choices").path(0)
                .path("message").path("content");
        return content.isTextual() ? content.asText() : null;
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

    static URI responsesEndpoint(String baseUrl) {
        URI uri = baseUri(baseUrl);
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith("/responses")) {
            return URI.create(normalized);
        }
        if (path.isEmpty() || path.equals("/")) {
            return URI.create(normalized + "/v1/responses");
        }
        return URI.create(normalized + "/responses");
    }

    static URI chatCompletionsEndpoint(String baseUrl) {
        URI uri = baseUri(baseUrl);
        String normalized = trimTrailingSlashes(uri.toString());
        String path = trimTrailingSlashes(
                uri.getPath() == null ? "" : uri.getPath());
        if (path.endsWith("/chat/completions")) {
            return URI.create(normalized);
        }
        if (path.isEmpty() || path.equals("/")) {
            if (isDeepSeek(uri)) {
                return URI.create(normalized + "/chat/completions");
            }
            return URI.create(normalized + "/v1/chat/completions");
        }
        return URI.create(normalized + "/chat/completions");
    }

    static Protocol protocol(LlmApiMode mode, String baseUrl) {
        if (mode == LlmApiMode.RESPONSES) return Protocol.RESPONSES;
        if (mode == LlmApiMode.CHAT_COMPLETIONS) {
            return Protocol.CHAT_COMPLETIONS;
        }
        URI uri = baseUri(baseUrl);
        String path = trimTrailingSlashes(
                uri.getPath() == null ? "" : uri.getPath());
        if (path.endsWith("/chat/completions") || isDeepSeek(uri)) {
            return Protocol.CHAT_COMPLETIONS;
        }
        return Protocol.RESPONSES;
    }

    private static URI baseUri(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("LLM base URL is invalid", exception);
        }
        if (!("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException(
                    "LLM base URL must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException("LLM base URL has no host");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "LLM base URL cannot contain query or fragment");
        }
        return uri;
    }

    private static boolean isDeepSeek(URI uri) {
        String host = uri.getHost();
        return host != null && (host.equalsIgnoreCase("api.deepseek.com")
                || host.toLowerCase(java.util.Locale.ROOT)
                .endsWith(".deepseek.com"));
    }

    private static String trimTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    static String normalizeApiKey(String configured) {
        String value = configured == null ? "" : configured.trim();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value;
    }

    static String httpError(int statusCode, String responseBody) {
        if (statusCode == 401) {
            return "LLM HTTP 401: 鉴权失败；请检查 llmApiKey 是否有效且属于当前 llmBaseUrl";
        }
        if (statusCode == 403) {
            return "LLM HTTP 403: 接口拒绝访问；请检查密钥权限、模型权限和账户状态";
        }
        return "LLM HTTP " + statusCode + ": "
                + compact(responseBody, 240);
    }

    private static String compact(String value, int maximum) {
        String compact = value == null ? "" : value
                .replaceAll("\\s+", " ").trim();
        return compact.length() <= maximum ? compact
                : compact.substring(0, maximum) + "...";
    }

    public record Configuration(
            String baseUrl, LlmApiMode apiMode,
            String model, String apiKey,
            int timeoutSeconds) { }

    public record Message(String role, String content) { }

    enum Protocol {
        RESPONSES,
        CHAT_COMPLETIONS
    }
}
