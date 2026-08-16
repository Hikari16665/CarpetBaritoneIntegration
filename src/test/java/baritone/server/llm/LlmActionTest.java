package baritone.server.llm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class LlmActionTest {
    @Test
    public void parsesStrictSingleAction() {
        LlmAction action = LlmAction.parse("""
                {"operation":"execute","command":"pos1 1 2 3",\
                "message":""}
                """);

        assertEquals(LlmAction.Operation.EXECUTE, action.operation());
        assertEquals("pos1 1 2 3", action.command());
        assertEquals("", action.message());
    }

    @Test
    public void acceptsCompatibilityMarkdownFence() {
        LlmAction action = LlmAction.parse("""
                ```json
                {"operation":"reply","command":"","message":"请告诉我点1"}
                ```
                """);

        assertEquals(LlmAction.Operation.REPLY, action.operation());
        assertEquals("请告诉我点1", action.message());
    }

    @Test
    public void rejectsCommandOnReply() {
        assertThrows(IllegalArgumentException.class, () ->
                LlmAction.parse("""
                        {"operation":"reply","command":"clean",\
                        "message":"执行"}
                        """));
    }
}
