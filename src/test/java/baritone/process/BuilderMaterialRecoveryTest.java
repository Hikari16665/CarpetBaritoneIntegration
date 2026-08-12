package baritone.process;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderMaterialRecoveryTest {

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
}
