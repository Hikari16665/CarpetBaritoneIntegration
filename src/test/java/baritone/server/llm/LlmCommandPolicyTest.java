package baritone.server.llm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LlmCommandPolicyTest {
    @Test
    public void allowsOneCbiTaskWithoutPrefix() {
        assertEquals("mine diamond_ore 4",
                LlmCommandPolicy.validate("  mine   diamond_ore 4  "));
    }

    @Test
    public void rejectsServerAndMultiCommands() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmCommandPolicy.validate("/op Steve"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmCommandPolicy.validate("clean; stop"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmCommandPolicy.validate("pos1\npos2"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmCommandPolicy.validate("settings set allowBreak false"));
    }

    @Test
    public void destructiveActionsRequireConfirmation() {
        assertTrue(LlmCommandPolicy.requiresConfirmation("clean"));
        assertTrue(LlmCommandPolicy.requiresConfirmation("break 1 2 3"));
        assertTrue(LlmCommandPolicy.requiresConfirmation("sel fill stone"));
        assertFalse(LlmCommandPolicy.requiresConfirmation("pos1 1 2 3"));
        assertFalse(LlmCommandPolicy.requiresConfirmation("mine diamond_ore 3"));
    }

    @Test
    public void recognizesUnambiguousConfirmation() {
        assertTrue(LlmCommandPolicy.isAffirmative("执行吧！"));
        assertTrue(LlmCommandPolicy.isAffirmative("confirm"));
        assertFalse(LlmCommandPolicy.isAffirmative("不要执行"));
        assertTrue(LlmCommandPolicy.isNegative("算了"));
    }
}
