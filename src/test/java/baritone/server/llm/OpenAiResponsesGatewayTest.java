package baritone.server.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.util.List;

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
    }
}
