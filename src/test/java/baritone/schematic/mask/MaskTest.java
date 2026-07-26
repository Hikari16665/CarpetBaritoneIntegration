package baritone.schematic.mask;

import baritone.api.schematic.mask.StaticMask;
import baritone.api.schematic.mask.shape.CylinderMask;
import baritone.api.schematic.mask.shape.SphereMask;
import net.minecraft.core.Direction;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MaskTest {
    @Test
    public void combinesDifferentSizedMasksWithoutReadingOutOfBounds() {
        StaticMask small = mask(1, 1, 1, true);
        StaticMask wide = mask(3, 1, 1, true);
        assertTrue(small.union(wide).partOfMask(2, 0, 0));
        assertFalse(small.intersection(wide).partOfMask(2, 0, 0));
        assertTrue(small.xor(wide).partOfMask(2, 0, 0));
    }

    @Test
    public void sphereAndCylinderRespectBoundingVolume() {
        StaticMask sphere = new SphereMask(5, 5, 5, true).compute();
        assertTrue(sphere.partOfMask(2, 2, 2));
        assertFalse(sphere.partOfMask(0, 0, 0));
        StaticMask cylinder = new CylinderMask(7, 5, 7, true, Direction.Axis.Y);
        assertTrue(cylinder.partOfMask(3, 0, 3));
        assertFalse(cylinder.partOfMask(0, 0, 0));
    }

    private static StaticMask mask(int x, int y, int z, boolean value) {
        return new StaticMask() {
            @Override public boolean partOfMask(int px, int py, int pz) { return value; }
            @Override public int widthX() { return x; }
            @Override public int heightY() { return y; }
            @Override public int lengthZ() { return z; }
        };
    }
}
