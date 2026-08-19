package baritone.api.schematic;

import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;

public class CompositeSchematic extends AbstractSchematic {
    private final List<CompositeSchematicEntry> entries = new ArrayList<>();
    private Lookup lastLookup;

    public CompositeSchematic(int x, int y, int z) { super(x, y, z); }
    public void put(ISchematic schematic, int x, int y, int z) {
        entries.add(new CompositeSchematicEntry(schematic, x, y, z));
        lastLookup = null;
        this.x = Math.max(this.x, x + schematic.widthX());
        this.y = Math.max(this.y, y + schematic.heightY());
        this.z = Math.max(this.z, z + schematic.lengthZ());
    }
    private CompositeSchematicEntry entryAt(int x, int y, int z, BlockState state) {
        Lookup cached = lastLookup;
        if (cached != null && cached.x == x && cached.y == y
                && cached.z == z && cached.state == state) {
            return cached.entry;
        }
        CompositeSchematicEntry found = null;
        for (CompositeSchematicEntry entry : entries) {
            int localX = x - entry.x;
            int localY = y - entry.y;
            int localZ = z - entry.z;
            if (localX < 0 || localY < 0 || localZ < 0
                    || localX >= entry.schematic.widthX()
                    || localY >= entry.schematic.heightY()
                    || localZ >= entry.schematic.lengthZ()) {
                continue;
            }
            if (entry.schematic.inSchematic(
                    localX, localY, localZ, state)) {
                found = entry;
                break;
            }
        }
        lastLookup = new Lookup(x, y, z, state, found);
        return found;
    }
    @Override public boolean inSchematic(int x, int y, int z, BlockState state) {
        return entryAt(x, y, z, state) != null;
    }
    @Override public BlockState desiredState(
            int x, int y, int z, BlockState current, List<BlockState> placeable) {
        CompositeSchematicEntry entry = entryAt(x, y, z, current);
        if (entry == null) throw new IllegalStateException("No schematic at position");
        return entry.schematic.desiredState(
                x - entry.x, y - entry.y, z - entry.z, current, placeable);
    }
    @Override public void reset() {
        lastLookup = null;
        entries.forEach(entry -> entry.schematic.reset());
    }

    private record Lookup(
            int x, int y, int z, BlockState state,
            CompositeSchematicEntry entry) { }
}
