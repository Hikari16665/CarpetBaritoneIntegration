package baritone.utils.pathing;

/** Two-bit block classification used by Baritone's compact chunk cache. */
public enum PathingBlockType {
    AIR(0b00), WATER(0b01), AVOID(0b10), SOLID(0b11);

    private final boolean[] bits;

    PathingBlockType(int value) {
        bits = new boolean[]{(value & 0b10) != 0, (value & 0b01) != 0};
    }

    public boolean[] getBits() {
        return bits;
    }

    public static PathingBlockType fromBits(boolean first, boolean second) {
        return first ? (second ? SOLID : AVOID) : (second ? WATER : AIR);
    }
}
