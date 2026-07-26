package baritone.api.schematic.mask;

public abstract class AbstractMask implements Mask {
    private final int widthX;
    private final int heightY;
    private final int lengthZ;
    protected AbstractMask(int widthX, int heightY, int lengthZ) {
        this.widthX = widthX;
        this.heightY = heightY;
        this.lengthZ = lengthZ;
    }
    @Override public int widthX() { return widthX; }
    @Override public int heightY() { return heightY; }
    @Override public int lengthZ() { return lengthZ; }
}
