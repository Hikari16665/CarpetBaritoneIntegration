package baritone.server;

import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EmergencyAvoidanceControllerTest {
    @Test
    public void predictsClosestPointOfFlyingTnt() {
        Vec3 closest = EmergencyAvoidanceController.closestApproach(
                Vec3.ZERO, new Vec3(-10.0D, 2.0D, 2.0D),
                new Vec3(1.0D, 0.0D, 0.0D), 20);
        assertEquals(0.0D, closest.x, 0.0001D);
        assertEquals(2.0D, closest.z, 0.0001D);
    }

    @Test
    public void clampsPredictionToFuseHorizon() {
        Vec3 closest = EmergencyAvoidanceController.closestApproach(
                Vec3.ZERO, new Vec3(-10.0D, 0.0D, 0.0D),
                new Vec3(1.0D, 0.0D, 0.0D), 4);
        assertEquals(-6.0D, closest.x, 0.0001D);
    }
}
