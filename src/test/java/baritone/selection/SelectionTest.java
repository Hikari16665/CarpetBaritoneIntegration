package baritone.selection;

import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.Direction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SelectionTest {
    @Test
    public void normalizesInclusiveBounds() {
        ISelection selection = new Selection(
                new BetterBlockPos(5, 9, -2), new BetterBlockPos(2, 7, 3));
        assertEquals(new BetterBlockPos(2, 7, -2), selection.min());
        assertEquals(new BetterBlockPos(5, 9, 3), selection.max());
        assertEquals(4, selection.size().getX());
        assertEquals(3, selection.size().getY());
        assertEquals(6, selection.size().getZ());
    }

    @Test
    public void expandContractAndShiftPreserveUserCornerOrientation() {
        ISelection selection = new Selection(
                new BetterBlockPos(5, 5, 5), new BetterBlockPos(1, 1, 1));
        ISelection expanded = selection.expand(Direction.EAST, 2);
        assertEquals(7, expanded.max().getX());
        ISelection contracted = expanded.contract(Direction.DOWN, 1);
        assertEquals(4, contracted.max().getY());
        ISelection shifted = contracted.shift(Direction.NORTH, 3);
        assertEquals(2, shifted.max().getZ());
    }
}
