package baritone.api.schematic.mask.shape;

import baritone.api.schematic.mask.AbstractMask;
import baritone.api.schematic.mask.StaticMask;

public final class SphereMask extends AbstractMask implements StaticMask {
    private final double centerX, centerY, centerZ;
    private final double radiusSqX, radiusSqY, radiusSqZ;
    private final boolean filled;
    public SphereMask(int widthX, int heightY, int lengthZ, boolean filled) {
        super(widthX, heightY, lengthZ);
        centerX = widthX / 2.0;
        centerY = heightY / 2.0;
        centerZ = lengthZ / 2.0;
        radiusSqX = centerX * centerX;
        radiusSqY = centerY * centerY;
        radiusSqZ = centerZ * centerZ;
        this.filled = filled;
    }
    @Override public boolean partOfMask(int x, int y, int z) {
        double dx = Math.abs(x + 0.5 - centerX);
        double dy = Math.abs(y + 0.5 - centerY);
        double dz = Math.abs(z + 0.5 - centerZ);
        if (outside(dx, dy, dz)) return false;
        return filled || outside(dx + 1, dy, dz) || outside(dx, dy + 1, dz)
                || outside(dx, dy, dz + 1);
    }
    private boolean outside(double dx, double dy, double dz) {
        return dx * dx / radiusSqX + dy * dy / radiusSqY + dz * dz / radiusSqZ > 1;
    }
}
