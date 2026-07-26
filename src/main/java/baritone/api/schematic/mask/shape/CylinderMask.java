package baritone.api.schematic.mask.shape;

import baritone.api.schematic.mask.AbstractMask;
import baritone.api.schematic.mask.StaticMask;
import net.minecraft.core.Direction;

public final class CylinderMask extends AbstractMask implements StaticMask {
    private final double centerA, centerB, radiusSqA, radiusSqB;
    private final boolean filled;
    private final Direction.Axis alignment;
    public CylinderMask(int widthX, int heightY, int lengthZ, boolean filled, Direction.Axis alignment) {
        super(widthX, heightY, lengthZ);
        centerA = getA(widthX, heightY, alignment) / 2.0;
        centerB = getB(heightY, lengthZ, alignment) / 2.0;
        radiusSqA = (centerA - 1) * (centerA - 1);
        radiusSqB = (centerB - 1) * (centerB - 1);
        this.filled = filled;
        this.alignment = alignment;
    }
    @Override public boolean partOfMask(int x, int y, int z) {
        double da = Math.abs(getA(x, y, alignment) + 0.5 - centerA);
        double db = Math.abs(getB(y, z, alignment) + 0.5 - centerB);
        if (outside(da, db)) return false;
        return filled || outside(da + 1, db) || outside(da, db + 1);
    }
    private boolean outside(double da, double db) {
        return da * da / radiusSqA + db * db / radiusSqB > 1;
    }
    private static int getA(int x, int y, Direction.Axis axis) {
        return axis == Direction.Axis.X ? y : x;
    }
    private static int getB(int y, int z, Direction.Axis axis) {
        return axis == Direction.Axis.Z ? y : z;
    }
}
