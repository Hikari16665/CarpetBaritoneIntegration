package baritone.process;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BuilderMaterialRecoveryTest {

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void containerMustBeInsideAllThreeSelectionAxes() {
        BlockPos min = new BlockPos(-10, 20, -30);
        BlockPos max = new BlockPos(10, 40, 30);

        assertTrue(BuilderMaterialRecovery.insideSelection(
                new BlockPos(0, 30, 0), min, max));
        assertTrue(BuilderMaterialRecovery.insideSelection(min, min, max));
        assertTrue(BuilderMaterialRecovery.insideSelection(max, min, max));
        assertFalse(BuilderMaterialRecovery.insideSelection(
                new BlockPos(11, 30, 0), min, max));
        assertFalse(BuilderMaterialRecovery.insideSelection(
                new BlockPos(0, 41, 0), min, max));
        assertFalse(BuilderMaterialRecovery.insideSelection(
                new BlockPos(0, 30, -31), min, max));
    }

    @Test
    public void forecastIsClampedByConfiguredMaximum() {
        assertEquals(1, BuilderMaterialRecovery.targetInventoryCount(0, 64));
        assertEquals(48, BuilderMaterialRecovery.targetInventoryCount(48, 64));
        assertEquals(64, BuilderMaterialRecovery.targetInventoryCount(500, 64));
        assertEquals(500, BuilderMaterialRecovery.targetInventoryCount(500, 2304));
    }

    @Test
    public void sampledDemandIsProjectedAndBounded() {
        assertEquals(40, BuilderProcess.estimateDemandFromSample(
                10, 250, 1000, 2304));
        assertEquals(2304, BuilderProcess.estimateDemandFromSample(
                500, 1000, 10000, 2304));
        assertEquals(7, BuilderProcess.estimateDemandFromSample(
                7, 1000, 1000, 2304));
    }

    @Test
    public void unloadedTargetUsesSafePlacementStage() {
        assertEquals(Blocks.STONE.defaultBlockState(),
                BuilderProcess.placementStageState(
                        null, Blocks.STONE.defaultBlockState()));
        assertNull(BuilderProcess.placementStageState(
                null, null));
    }
}
