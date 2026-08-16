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

/** Chat Completions transport backed by OpenAI's official Java SDK. */
final class OpenAiSdkChatGateway {

    CompletableFuture<LlmAction> request(
            OpenAiResponsesGateway.Configuration configuration,
            java.util.List<OpenAiResponsesGateway.Message> messages) {
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
        ChatCompletionCreateParams.Builder params =
                ChatCompletionCreateParams.builder()
                        .model(configuration.model())
                        .maxTokens(512)
                        .responseFormat(ResponseFormatJsonObject
                                .builder().build());
        for (OpenAiResponsesGateway.Message message : messages) {
            switch (message.role()) {
                case "system" -> params.addSystemMessage(message.content());
                case "assistant" -> params.addAssistantMessage(message.content());
                default -> params.addUserMessage(message.content());
            }
        }
        if (configuration.thinkingEnabled()) {
            params.putAdditionalBodyProperty("thinking",
                    JsonValue.from(Map.of("type", "enabled")));
        }
        if (!configuration.reasoningEffort().isBlank()) {
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
        String content = completion.choices().get(0).message()
                .content().orElse("");
        if (content.isBlank()) {
            throw new IllegalStateException(
                    "LLM response contains no assistant output");
        }
        return LlmAction.parse(content);
    }

    private static IllegalStateException safeException(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof OpenAIServiceException service) {
            return new IllegalStateException(
                    OpenAiResponsesGateway.httpError(
                            service.statusCode(), service.body().toString()));
        }
        return new IllegalStateException(
                "LLM SDK request failed: "
                        + cause.getClass().getSimpleName());
    }
}
