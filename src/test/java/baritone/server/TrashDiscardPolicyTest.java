package baritone.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TrashDiscardPolicyTest {
    @Test
    public void keepsTwoStackBaseline() {
        assertEquals(128, TrashDiscardPolicy.requiredReserve(
                128, 256, 4, 16));
        assertEquals(0, TrashDiscardPolicy.discardableFromStack(
                64, true, 128, 128));
    }

    @Test
    public void growsWithKnownPlacementDemandAndCapsReserve() {
        assertEquals(196, TrashDiscardPolicy.requiredReserve(
                128, 256, 180, 16));
        assertEquals(256, TrashDiscardPolicy.requiredReserve(
                128, 256, 400, 16));
    }

    @Test
    public void discardsOnlySurplusPathingBlocks() {
        assertEquals(32, TrashDiscardPolicy.discardableFromStack(
                64, true, 160, 128));
        assertEquals(64, TrashDiscardPolicy.discardableFromStack(
                64, false, 0, 128));
    }
}
