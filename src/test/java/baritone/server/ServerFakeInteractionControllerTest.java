package baritone.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ServerFakeInteractionControllerTest {

    @Test
    public void blockFaceRemainsReachableFromEdgeOfBuilderStance() {
        BlockPos target = new BlockPos(-27, -60, 87);
        // A short traverse may stop at this edge of feet cell (-30,-59,85).
        // Its distance to the target center exceeds 4.5, while the nearest
        // legal interaction surface is still within normal block reach.
        Vec3 eye = new Vec3(-30.0D, -57.38D, 85.0D);
        double reachSquared = 4.5D * 4.5D;

        assertTrue(eye.distanceToSqr(Vec3.atCenterOf(target))
                > reachSquared);
        assertTrue(ServerFakeInteractionController
                .distanceToBlockAabbSqr(eye, target) <= reachSquared);
    }
}
