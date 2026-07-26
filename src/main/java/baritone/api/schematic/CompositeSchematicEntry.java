package baritone.api.schematic;

public final class CompositeSchematicEntry {
    public final ISchematic schematic;
    public final int x, y, z;
    public CompositeSchematicEntry(ISchematic schematic, int x, int y, int z) {
        this.schematic = schematic; this.x = x; this.y = y; this.z = z;
    }
}
