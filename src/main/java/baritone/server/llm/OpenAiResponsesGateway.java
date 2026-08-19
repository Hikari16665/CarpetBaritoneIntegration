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
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OpenAI-compatible Responses and Chat Completions client. */
public final class OpenAiResponsesGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger("CBI-LLM");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OpenAiSdkChatGateway chatGateway =
            new OpenAiSdkChatGateway();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public CompletableFuture<LlmAction> request(
            Configuration configuration, List<Message> messages) {
        Protocol protocol = protocol(
                configuration.apiMode(), configuration.baseUrl());
        if (protocol == Protocol.CHAT_COMPLETIONS) {
            return chatGateway.request(configuration, messages);
        }
        ObjectNode body = requestBody(configuration.model(), messages,
                configuration.maxOutputTokens());
        URI endpoint = responsesEndpoint(configuration.baseUrl());
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
        long started = System.nanoTime();
        LOGGER.info("LLM HTTP request protocol=responses endpoint={} model={} "
                        + "messages={} maxOutputTokens={} timeoutSeconds={}",
                endpoint, configuration.model(), messages.size(),
                configuration.maxOutputTokens(), configuration.timeoutSeconds());
        return client.sendAsync(request.build(),
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8))
                .thenApply(response -> {
                    logResponse("responses", response, started);
                    return decode(response);
                });
    }

    /** Native function-calling transport shared by Responses and Chat. */
    public CompletableFuture<LlmModelTurn> requestTools(
            Configuration configuration,
            List<LlmWireMessage> messages,
            List<LlmToolDefinition> tools) {
        Protocol protocol = protocol(
                configuration.apiMode(), configuration.baseUrl());
        ObjectNode body = protocol == Protocol.RESPONSES
                ? responsesToolBody(configuration, messages, tools)
                : chatToolBody(configuration, messages, tools);
        URI endpoint = protocol == Protocol.RESPONSES
                ? responsesEndpoint(configuration.baseUrl())
                : chatEndpoint(configuration.baseUrl());
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(configuration.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "CarpetBaritoneIntegration/1.21.7")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body.toString(), StandardCharsets.UTF_8));
        String apiKey = normalizeApiKey(configuration.apiKey());
        if (!apiKey.isBlank()) request.header(
                "Authorization", "Bearer " + apiKey);
        long started = System.nanoTime();
        LOGGER.info("LLM HTTP request protocol={} endpoint={} model={} "
                        + "messages={} tools={} maxOutputTokens={} timeoutSeconds={}",
                protocolName(protocol), endpoint, configuration.model(),
                messages.size(), tools.size(), configuration.maxOutputTokens(),
                configuration.timeoutSeconds());
        return client.sendAsync(request.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    logResponse(protocolName(protocol), response, started);
                    return decodeToolTurn(protocol, response,
                            configuration.maxOutputTokens());
                });
    }

    private static ObjectNode responsesToolBody(
            Configuration configuration,
            List<LlmWireMessage> messages,
            List<LlmToolDefinition> tools) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", configuration.model());
        root.put("store", false);
        root.put("max_output_tokens", configuration.maxOutputTokens());
        ArrayNode input = root.putArray("input");
        for (LlmWireMessage message : messages) {
            switch (message.kind()) {
                case MESSAGE -> input.addObject()
                        .put("role", message.role())
                        .put("content", message.content());
                case ASSISTANT_TOOL_CALLS -> message.toolCalls().forEach(call ->
                        input.addObject().put("type", "function_call")
                                .put("call_id", call.id())
                                .put("name", call.name())
                                .put("arguments", call.arguments()));
                case TOOL_RESULT -> input.addObject()
                        .put("type", "function_call_output")
                        .put("call_id", message.toolCallId())
                        .put("output", message.content());
            }
        }
        ArrayNode outputTools = root.putArray("tools");
        tools.forEach(tool -> {
            ObjectNode output = outputTools.addObject();
            output.put("type", "function");
            output.put("name", tool.name());
            output.put("description", tool.description());
            output.set("parameters", tool.parameters());
            output.put("strict", true);
        });
        root.put("tool_choice", "auto");
        root.put("parallel_tool_calls", true);
        return root;
    }

    private static ObjectNode chatToolBody(
            Configuration configuration,
            List<LlmWireMessage> messages,
            List<LlmToolDefinition> tools) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", configuration.model());
        root.put("max_tokens", configuration.maxOutputTokens());
        root.put("stream", false);
        if (configuration.thinkingEnabled()) {
            root.putObject("thinking").put("type", "enabled");
        }
        if (!configuration.reasoningEffort().isBlank()) {
            root.put("reasoning_effort", configuration.reasoningEffort());
        }
        ArrayNode outputMessages = root.putArray("messages");
        for (LlmWireMessage message : messages) {
            switch (message.kind()) {
                case MESSAGE -> outputMessages.addObject()
                        .put("role", message.role())
                        .put("content", message.content());
                case ASSISTANT_TOOL_CALLS -> {
                    ObjectNode assistant = outputMessages.addObject();
                    assistant.put("role", "assistant");
                    assistant.putNull("content");
                    ArrayNode calls = assistant.putArray("tool_calls");
                    message.toolCalls().forEach(call -> {
                        ObjectNode output = calls.addObject();
                        output.put("id", call.id());
                        output.put("type", "function");
                        output.putObject("function")
                                .put("name", call.name())
                                .put("arguments", call.arguments());
                    });
                }
                case TOOL_RESULT -> outputMessages.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", message.toolCallId())
                        .put("content", message.content());
            }
        }
        ArrayNode outputTools = root.putArray("tools");
        tools.forEach(tool -> {
            ObjectNode output = outputTools.addObject();
            output.put("type", "function");
            ObjectNode function = output.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.parameters());
            function.put("strict", true);
        });
        root.put("tool_choice", "auto");
        root.put("parallel_tool_calls", true);
        return root;
    }

    private static LlmModelTurn decodeToolTurn(
            Protocol protocol, HttpResponse<String> response,
            int maxOutputTokens) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(httpError(
                    response.statusCode(), response.body()));
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            List<LlmToolCall> calls = new java.util.ArrayList<>();
            String text = "";
            if (protocol == Protocol.RESPONSES) {
                JsonNode output = root.path("output");
                if (output.isArray()) for (JsonNode item : output) {
                    if ("function_call".equals(item.path("type").asText())) {
                        calls.add(new LlmToolCall(
                                item.path("call_id").asText(
                                        item.path("id").asText()),
                                item.path("name").asText(),
                                item.path("arguments").asText("{}")));
                    }
                }
                String extracted = extractOutputText(root);
                if (extracted != null) text = extracted;
            } else {
                JsonNode message = root.path("choices").path(0).path("message");
                JsonNode toolCalls = message.path("tool_calls");
                if (toolCalls.isArray()) for (JsonNode call : toolCalls) {
                    JsonNode function = call.path("function");
                    calls.add(new LlmToolCall(call.path("id").asText(),
                            function.path("name").asText(),
                            function.path("arguments").asText("{}")));
                }
                if (message.path("content").isTextual()) {
                    text = message.path("content").asText();
                }
            }
            if (calls.isEmpty() && text.isBlank()) {
                String finishReason = root.path("choices").path(0)
                        .path("finish_reason").asText();
                String incompleteReason = root.path("incomplete_details")
                        .path("reason").asText();
                if ("length".equalsIgnoreCase(finishReason)
                        || incompleteReason.toLowerCase(java.util.Locale.ROOT)
                        .contains("max_output")) {
                    throw new IllegalStateException(
                            "LLM exhausted its output/reasoning budget of "
                                    + maxOutputTokens + " tokens; increase "
                                    + "llmMaxOutputTokens or reduce context");
                }
                throw new IllegalStateException(
                        "LLM response contains no tool call or text");
            }
            return new LlmModelTurn(calls, text);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to decode LLM tool response", exception);
        }
    }

    private static URI chatEndpoint(String baseUrl) {
        URI uri = baseUri(baseUrl);
        String normalized = trimTrailingSlashes(uri.toString());
        String path = trimTrailingSlashes(
                uri.getPath() == null ? "" : uri.getPath());
        if (path.endsWith("/chat/completions")) return URI.create(normalized);
        if (path.isEmpty() || path.equals("/")) {
            return URI.create(normalized + "/chat/completions");
        }
        return URI.create(normalized + "/chat/completions");
    }

    static ObjectNode requestBody(String model, List<Message> messages) {
        return requestBody(model, messages, 512);
    }

    static ObjectNode requestBody(String model, List<Message> messages,
                                  int maxOutputTokens) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("store", false);
        root.put("max_output_tokens", maxOutputTokens);
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
            throw new IllegalStateException(httpError(
                    response.statusCode(), response.body()));
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            String output = extractOutputText(root);
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

    static boolean isDeepSeek(URI uri) {
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

    private static String protocolName(Protocol protocol) {
        return protocol.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void logResponse(String protocol,
                                    HttpResponse<String> response,
                                    long startedNanos) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedNanos);
        String requestId = response.headers().firstValue("x-request-id")
                .orElse(response.headers().firstValue("request-id")
                        .orElse("-"));
        int bodyCharacters = response.body() == null ? 0
                : response.body().length();
        LOGGER.info("LLM HTTP response protocol={} status={} elapsedMs={} "
                        + "bodyChars={} requestId={}", protocol,
                response.statusCode(), elapsedMillis, bodyCharacters, requestId);
        if (response.statusCode() >= 200 && response.statusCode() < 300
                && response.body() != null && !response.body().isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(response.body());
                JsonNode usage = root.path("usage");
                String finishReason = root.path("choices").path(0)
                        .path("finish_reason").asText(
                                root.path("status").asText("-"));
                int inputTokens = usage.path("prompt_tokens").asInt(
                        usage.path("input_tokens").asInt(-1));
                int outputTokens = usage.path("completion_tokens").asInt(
                        usage.path("output_tokens").asInt(-1));
                int totalTokens = usage.path("total_tokens").asInt(-1);
                int reasoningCharacters = root.path("choices").path(0)
                        .path("message").path("reasoning_content")
                        .asText("").length();
                LOGGER.info("LLM usage protocol={} finishReason={} inputTokens={} "
                                + "outputTokens={} totalTokens={} reasoningChars={}",
                        protocol, finishReason, inputTokens, outputTokens,
                        totalTokens, reasoningCharacters);
            } catch (Exception exception) {
                LOGGER.debug("Unable to decode optional LLM usage metadata", exception);
            }
        }
    }

    public record Configuration(
            String baseUrl, LlmApiMode apiMode,
            String model, String apiKey,
            int timeoutSeconds, boolean thinkingEnabled,
            String reasoningEffort, int maxOutputTokens) {
        /** Source-compatible constructor for integrations compiled against
         * the pre-budget configuration shape. */
        public Configuration(String baseUrl, LlmApiMode apiMode,
                             String model, String apiKey,
                             int timeoutSeconds, boolean thinkingEnabled,
                             String reasoningEffort) {
            this(baseUrl, apiMode, model, apiKey, timeoutSeconds,
                    thinkingEnabled, reasoningEffort, 4_096);
        }
    }

    public record Message(String role, String content) { }

    enum Protocol {
        RESPONSES,
        CHAT_COMPLETIONS
    }
}
