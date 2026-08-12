package baritone.server;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FakePlayerStatusMessengerTest {
    @Test
    public void threatMessagesAreEdgeTriggeredAndPrioritized() {
        assertEquals(FakePlayerStatusMessenger.Event.TNT, transition(
                EmergencyAvoidanceController.Threat.NONE,
                EmergencyAvoidanceController.Threat.TNT,
                false, true, false, false, false, false, false, false));
        assertNull(transition(
                EmergencyAvoidanceController.Threat.TNT,
                EmergencyAvoidanceController.Threat.TNT,
                true, true, false, false, false, false, false, false));
    }

    @Test
    public void missingFoodWinsOverGenericLowFoodMessage() {
        assertEquals(FakePlayerStatusMessenger.Event.NO_FOOD, transition(
                EmergencyAvoidanceController.Threat.NONE,
                EmergencyAvoidanceController.Threat.NONE,
                false, true, false, false, false, true, false, false));
    }

    @Test
    public void templatesReplaceValuesAndRemoveLineBreaks() throws Exception {
        Method render = FakePlayerStatusMessenger.class.getDeclaredMethod(
                "renderTemplate", String.class, String.class, int.class,
                float.class, float.class,
                EmergencyAvoidanceController.Threat.class);
        render.setAccessible(true);
        assertEquals("Steve 7 16.5/20.0 tnt ok", render.invoke(null,
                "{player} {food} {health}/{max_health} {threat}\nok",
                "Steve", 7, 16.5F, 20.0F,
                EmergencyAvoidanceController.Threat.TNT));
    }

    private static FakePlayerStatusMessenger.Event transition(
            EmergencyAvoidanceController.Threat before,
            EmergencyAvoidanceController.Threat now,
            boolean wasLow, boolean low,
            boolean wasHurt, boolean hurt,
            boolean wasOut, boolean out,
            boolean wasFailure, boolean failure) {
        return FakePlayerStatusMessenger.transition(before, now,
                wasLow, low, wasHurt, hurt, wasOut, out,
                wasFailure, failure);
    }
}
