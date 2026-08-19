package baritone.schematic;

import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.CompositeSchematic;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CompositeSchematicTest {

    @Test
    public void desiredStateReusesImmediatelyPrecedingMembershipLookup() {
        CountingSchematic child = new CountingSchematic(4, 3, 2);
        CompositeSchematic composite = new CompositeSchematic(0, 0, 0);
        composite.put(child, 10, 20, 30);

        assertTrue(composite.inSchematic(11, 21, 31, null));
        assertNull(composite.desiredState(
                11, 21, 31, null, List.of()));
        assertEquals(1, child.membershipChecks);
    }

    @Test
    public void entryBoundsRejectWithoutCallingChildSchematic() {
        CountingSchematic child = new CountingSchematic(4, 3, 2);
        CompositeSchematic composite = new CompositeSchematic(0, 0, 0);
        composite.put(child, 10, 20, 30);

        assertFalse(composite.inSchematic(14, 21, 31, null));
        assertFalse(composite.inSchematic(11, 23, 31, null));
        assertFalse(composite.inSchematic(11, 21, 32, null));
        assertEquals(0, child.membershipChecks);
    }

    private static final class CountingSchematic extends AbstractSchematic {
        private int membershipChecks;

        private CountingSchematic(int x, int y, int z) {
            super(x, y, z);
        }

        @Override
        public boolean inSchematic(
                int x, int y, int z, BlockState currentState) {
            membershipChecks++;
            return ISchematicBounds.contains(this, x, y, z);
        }

        @Override
        public BlockState desiredState(
                int x, int y, int z, BlockState current,
                List<BlockState> approxPlaceable) {
            return null;
        }
    }

    private static final class ISchematicBounds {
        private static boolean contains(
                AbstractSchematic schematic, int x, int y, int z) {
            return x >= 0 && x < schematic.widthX()
                    && y >= 0 && y < schematic.heightY()
                    && z >= 0 && z < schematic.lengthZ();
        }
    }
}
