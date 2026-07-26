package baritone.api.schematic.mask;

final class PreComputedMask extends AbstractMask implements StaticMask {
    private final boolean[][][] values;
    PreComputedMask(StaticMask source) {
        super(source.widthX(), source.heightY(), source.lengthZ());
        values = new boolean[heightY()][lengthZ()][widthX()];
        for (int y = 0; y < heightY(); y++) {
            for (int z = 0; z < lengthZ(); z++) {
                for (int x = 0; x < widthX(); x++) {
                    values[y][z][x] = source.partOfMask(x, y, z);
                }
            }
        }
    }
    @Override public boolean partOfMask(int x, int y, int z) { return values[y][z][x]; }
}
