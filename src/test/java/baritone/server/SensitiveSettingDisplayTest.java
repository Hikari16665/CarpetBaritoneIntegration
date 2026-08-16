package baritone.server;

import baritone.api.Settings;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SensitiveSettingDisplayTest {
    @Test
    public void apiKeyIsMaskedInChatFeedback() throws Exception {
        Field field = Settings.class.getField("llmApiKey");
        String secret = "sk-test-secret-must-not-be-echoed";

        String display = BasicGoalCommandHandler.displaySettingValue(
                field, secret);

        assertEquals("<已配置>", display);
        assertFalse(display.contains(secret));
        assertEquals("<未配置>",
                BasicGoalCommandHandler.displaySettingValue(field, ""));
    }
}
