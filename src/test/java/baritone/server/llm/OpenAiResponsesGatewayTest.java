package baritone.server.llm;

import baritone.api.LlmApiMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenAiResponsesGatewayTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void requestUsesStrictJsonSchemaAndNoRemoteStorage() {
        ObjectNode body = OpenAiResponsesGateway.requestBody(
                "test-model", List.of(
                        new OpenAiResponsesGateway.Message(
                                "system", "rules"),
                        new OpenAiResponsesGateway.Message(
                                "user", "request")));

        assertEquals("test-model", body.path("model").asText());
        assertFalse(body.path("store").asBoolean());
        JsonNode format = body.path("text").path("format");
        assertEquals("json_schema", format.path("type").asText());
        assertTrue(format.path("strict").asBoolean());
        assertFalse(format.path("schema")
                .path("additionalProperties").asBoolean());
        assertEquals(2, body.path("input").size());
    }

    @Test
    public void extractsNestedResponsesApiOutputText() throws Exception {
        JsonNode response = MAPPER.readTree("""
                {"id":"resp_1","output":[{"type":"message","content":[
                  {"type":"output_text","text":"{\\"operation\\":\\"reply\\",\
                  \\"command\\":\\"\\",\\"message\\":\\"好的\\"}"}
                ]}]}
                """);

        LlmAction action = LlmAction.parse(
                OpenAiResponsesGateway.extractOutputText(response));
        assertEquals(LlmAction.Operation.REPLY, action.operation());
        assertEquals("", action.command());
        assertEquals("好的", action.message());
    }

    @Test
    public void resolvesBaseUrlAndAcceptsCompleteEndpoint() {
        assertEquals("https://api.openai.com/v1/responses",
                OpenAiResponsesGateway.responsesEndpoint(
                        "https://api.openai.com/v1").toString());
        assertEquals("http://127.0.0.1:8000/v1/responses",
                OpenAiResponsesGateway.responsesEndpoint(
                        "http://127.0.0.1:8000/v1/").toString());
        assertEquals("https://proxy.example/openai/v1/responses",
                OpenAiResponsesGateway.responsesEndpoint(
                        "https://proxy.example/openai/v1/responses/")
                        .toString());
        assertEquals("https://proxy.example/v1/responses",
                OpenAiResponsesGateway.responsesEndpoint(
                        "https://proxy.example").toString());
        assertEquals("https://api.deepseek.com",
                OpenAiSdkChatGateway.sdkBaseUrl(
                        "https://api.deepseek.com"));
        assertEquals("http://127.0.0.1:8000/v1",
                OpenAiSdkChatGateway.sdkBaseUrl(
                        "http://127.0.0.1:8000/v1/chat/completions"));
    }

    @Test
    public void autoProtocolRecognizesDeepSeekAndCompleteEndpoints() {
        assertEquals(OpenAiResponsesGateway.Protocol.CHAT_COMPLETIONS,
                OpenAiResponsesGateway.protocol(LlmApiMode.AUTO,
                        "https://api.deepseek.com"));
        assertEquals(OpenAiResponsesGateway.Protocol.CHAT_COMPLETIONS,
                OpenAiResponsesGateway.protocol(LlmApiMode.AUTO,
                        "https://proxy.example/v1/chat/completions"));
        assertEquals(OpenAiResponsesGateway.Protocol.RESPONSES,
                OpenAiResponsesGateway.protocol(LlmApiMode.AUTO,
                        "https://api.openai.com/v1"));
        assertEquals(OpenAiResponsesGateway.Protocol.RESPONSES,
                OpenAiResponsesGateway.protocol(LlmApiMode.RESPONSES,
                        "https://api.deepseek.com"));
    }

    @Test
    public void normalizesCommonApiKeyPasteFormats() {
        assertEquals("sk-example",
                OpenAiResponsesGateway.normalizeApiKey("sk-example"));
        assertEquals("sk-example",
                OpenAiResponsesGateway.normalizeApiKey(
                        "  Bearer sk-example  "));
        assertEquals("sk-example",
                OpenAiResponsesGateway.normalizeApiKey(
                        "\"Bearer sk-example\""));
        assertEquals("",
                OpenAiResponsesGateway.normalizeApiKey("  "));
    }

    @Test
    public void authenticationErrorsNeverEchoProviderBody() {
        String providerBody = "api key ****7ca4 is invalid";

        String unauthorized = OpenAiResponsesGateway.httpError(
                401, providerBody);
        String forbidden = OpenAiResponsesGateway.httpError(
                403, providerBody);

        assertFalse(unauthorized.contains("7ca4"));
        assertFalse(forbidden.contains("7ca4"));
        assertTrue(unauthorized.contains("llmApiKey"));
    }

    @Test
    public void sendsExactChatEndpointAndSingleBearerHeader()
            throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/chat/completions", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders()
                    .getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody()
                    .readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"id\":\"chatcmpl_test\","
                    + "\"created\":0,\"model\":\"test-model\","
                    + "\"object\":\"chat.completion\","
                    + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{"
                    + "\"role\":\"assistant\","
                    + "\"content\":\"{\\\"operation\\\":\\\"reply\\\","
                    + "\\\"command\\\":\\\"\\\",\\\"message\\\":\\\"ok\\\"}\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/chat/completions";
            LlmAction action = new OpenAiResponsesGateway().request(
                    new OpenAiResponsesGateway.Configuration(
                            endpoint, LlmApiMode.AUTO, "test-model",
                            "Bearer sk-local-test", 5,
                            true, "high"),
                    List.of(new OpenAiResponsesGateway.Message(
                            "user", "return json")))
                    .get(5, TimeUnit.SECONDS);

            assertEquals("/chat/completions", path.get());
            assertEquals("Bearer sk-local-test", authorization.get());
            assertEquals(LlmAction.Operation.REPLY, action.operation());
            JsonNode body = MAPPER.readTree(requestBody.get());
            assertEquals("json_object", body.path("response_format")
                    .path("type").asText());
            assertEquals("enabled", body.path("thinking")
                    .path("type").asText());
            assertEquals("high", body.path("reasoning_effort").asText());
        } finally {
            server.stop(0);
        }
    }
}
