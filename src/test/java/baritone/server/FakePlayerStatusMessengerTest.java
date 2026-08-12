package baritone.server;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class FakePlayerStatusMessengerTest {
    @Test
    public void templatesReplaceEventValuesAndRemoveLineBreaks() {
        assertEquals("Steve 7 建造 minecraft:stone ok",
                FakePlayerStatusMessenger.renderTemplate(
                        "{player} {food} {task} {items}\nok",
                        Map.of("player", "Steve", "food", "7",
                                "task", "建造", "items",
                                "minecraft:stone")));
    }

    @Test
    public void templatesAreBoundedForPublicChat() {
        String rendered = FakePlayerStatusMessenger.renderTemplate(
                "x".repeat(400), Map.of());
        assertEquals(256, rendered.length());
    }
}
