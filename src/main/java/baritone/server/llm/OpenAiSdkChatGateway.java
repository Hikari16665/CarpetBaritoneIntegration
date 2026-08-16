package baritone.server.llm;

import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/** Chat Completions transport backed by OpenAI's official Java SDK. */
final class OpenAiSdkChatGateway {
    private static final int THINKING_MAX_TOKENS = 4_096;
    private static final int FALLBACK_MAX_TOKENS = 1_024;

    CompletableFuture<LlmAction> request(
            OpenAiResponsesGateway.Configuration configuration,
            java.util.List<OpenAiResponsesGateway.Message> messages) {
        return requestAttempt(configuration, messages, true)
                .handle((action, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(action);
                    }
                    Throwable cause = unwrap(error);
                    if (configuration.thinkingEnabled()
                            && cause instanceof ThinkingBudgetExhaustedException) {
                        return requestAttempt(configuration, messages, false);
                    }
                    return CompletableFuture.<LlmAction>failedFuture(cause);
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<LlmAction> requestAttempt(
            OpenAiResponsesGateway.Configuration configuration,
            java.util.List<OpenAiResponsesGateway.Message> messages,
            boolean includeReasoningExtensions) {
        OpenAIOkHttpClientAsync.Builder clientBuilder =
                OpenAIOkHttpClientAsync.builder()
                        .baseUrl(sdkBaseUrl(configuration.baseUrl()))
                        .timeout(Duration.ofSeconds(
                                configuration.timeoutSeconds()))
                        .maxRetries(0);
        String apiKey = OpenAiResponsesGateway.normalizeApiKey(
                configuration.apiKey());
        if (!apiKey.isBlank()) clientBuilder.apiKey(apiKey);
        OpenAIClientAsync client = clientBuilder.build();
        boolean includeThinking = includeReasoningExtensions
                && configuration.thinkingEnabled();
        boolean includeReasoningEffort = includeReasoningExtensions
                && !configuration.reasoningEffort().isBlank();
        ChatCompletionCreateParams.Builder params =
                ChatCompletionCreateParams.builder()
                        .model(configuration.model())
                        .maxTokens(includeThinking || includeReasoningEffort
                                ? THINKING_MAX_TOKENS
                                : FALLBACK_MAX_TOKENS)
                        .responseFormat(ResponseFormatJsonObject
                                .builder().build());
        for (OpenAiResponsesGateway.Message message : messages) {
            switch (message.role()) {
                case "system" -> params.addSystemMessage(message.content());
                case "assistant" -> params.addAssistantMessage(message.content());
                default -> params.addUserMessage(message.content());
            }
        }
        if (includeThinking) {
            params.putAdditionalBodyProperty("thinking",
                    JsonValue.from(Map.of("type", "enabled")));
        }
        if (includeReasoningEffort) {
            params.putAdditionalBodyProperty("reasoning_effort",
                    JsonValue.from(configuration.reasoningEffort()));
        }
        CompletableFuture<ChatCompletion> request = client.chat()
                .completions().create(params.build());
        return request.handle((completion, error) -> {
            client.close();
            if (error != null) throw new CompletionException(
                    safeException(error));
            return decode(completion);
        });
    }

    static String sdkBaseUrl(String configured) {
        URI uri = URI.create(configured.trim());
        String result = uri.toString();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        boolean completeEndpoint = result.endsWith("/chat/completions");
        if (completeEndpoint) {
            result = result.substring(0,
                    result.length() - "/chat/completions".length());
        }
        URI normalized = URI.create(result);
        String path = normalized.getPath() == null
                ? "" : normalized.getPath();
        if ((path.isEmpty() || path.equals("/"))
                && !completeEndpoint
                && !OpenAiResponsesGateway.isDeepSeek(normalized)) {
            result += "/v1";
        }
        return result;
    }

    private static LlmAction decode(ChatCompletion completion) {
        if (completion.choices().isEmpty()) {
            throw new IllegalStateException(
                    "LLM response contains no choices");
        }
        ChatCompletion.Choice choice = completion.choices().get(0);
        String content = choice.message().content().orElse("");
        if (content.isBlank()) {
            if ("length".equalsIgnoreCase(
                    choice.finishReason().asString())) {
                throw new ThinkingBudgetExhaustedException();
            }
            throw new IllegalStateException(
                    "LLM response contains no assistant output");
        }
        return LlmAction.parse(content);
    }

    private static IllegalStateException safeException(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof OpenAIServiceException service) {
            return new IllegalStateException(
                    OpenAiResponsesGateway.httpError(
                            service.statusCode(), service.body().toString()));
        }
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String detail = cause.getMessage() == null ? "" : cause.getMessage();
        if (root != cause) {
            detail += "; caused by " + root.getClass().getSimpleName()
                    + (root.getMessage() == null
                    ? "" : ": " + root.getMessage());
        }
        detail = detail.replaceAll("\\s+", " ").trim();
        if (detail.length() > 240) {
            detail = detail.substring(0, 240) + "...";
        }
        return new IllegalStateException(
                "LLM SDK request failed: "
                        + cause.getClass().getSimpleName()
                        + (detail.isEmpty() ? "" : ": " + detail));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static final class ThinkingBudgetExhaustedException
            extends IllegalStateException {
        private ThinkingBudgetExhaustedException() {
            super("LLM thinking exhausted its output token budget");
        }
    }
}
