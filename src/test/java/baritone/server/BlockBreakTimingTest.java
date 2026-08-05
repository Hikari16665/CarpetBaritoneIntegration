package baritone.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlockBreakTimingTest {

    @Test
    public void vanillaOneTickProgressIsInstant() {
        assertTrue(BlockBreakTiming.isInstant(1.0D, 1.0F));
        assertTrue(BlockBreakTiming.isInstant(2.5D, 1.0F));
        assertFalse(BlockBreakTiming.isInstant(0.999D, 1.0F));
        assertFalse(BlockBreakTiming.isInstant(Double.NaN, 1.0F));
    }

    @Test
    public void zeroHardnessIsInstantDespiteZeroProgress() {
        assertTrue(BlockBreakTiming.isInstant(0.0D, 0.0F));
        assertFalse(BlockBreakTiming.isInstant(0.0D, -1.0F));
    }

    @Test
    public void instantBreakPreservesExistingOrdinaryCooldown() {
        assertEquals(120L, BlockBreakTiming.afterSuccessfulBreak(
                120L, 100L, 6, true));
        assertEquals(106L, BlockBreakTiming.afterSuccessfulBreak(
                80L, 100L, 6, false));
        assertEquals(101L, BlockBreakTiming.afterSuccessfulBreak(
                80L, 100L, 0, false));
    }
}
