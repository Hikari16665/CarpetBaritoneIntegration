package baritone.server;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoEatControllerTest {
    @Test
    public void startsWhenHungerIsBelowHalf() {
        assertTrue(AutoEatController.shouldStart(
                9, 20.0F, 20.0F, 10, true));
        assertFalse(AutoEatController.shouldStart(
                10, 20.0F, 20.0F, 10, true));
    }

    @Test
    public void startsWhenHurtAndNotFull() {
        assertTrue(AutoEatController.shouldStart(
                19, 18.0F, 20.0F, 10, true));
        assertFalse(AutoEatController.shouldStart(
                20, 18.0F, 20.0F, 10, true));
        assertFalse(AutoEatController.shouldStart(
                19, 18.0F, 20.0F, 10, false));
    }
}
