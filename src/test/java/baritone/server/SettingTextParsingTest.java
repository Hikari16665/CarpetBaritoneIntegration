package baritone.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SettingTextParsingTest {
    @Test
    public void joinsAllWordsForStringSettings() {
        assertEquals("有 TNT 飞过来了", BasicGoalCommandHandler.settingText(
                "", new String[]{"settings", "message", "有", "TNT",
                        "飞过来了"}, 2));
    }

    @Test
    public void rejectsExtraTokensForNonStringSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> BasicGoalCommandHandler.settingText(1,
                        new String[]{"settings", "count", "1", "2"}, 2));
    }
}
