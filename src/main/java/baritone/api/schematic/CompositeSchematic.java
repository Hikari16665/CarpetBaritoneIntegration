package baritone.api.schematic;

import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;

public class CompositeSchematic extends AbstractSchematic {
    private final List<CompositeSchematicEntry> entries = new ArrayList<>();
    public CompositeSchematic(int x, int y, int z) { super(x, y, z); }
    public void put(ISchematic schematic, int x, int y, int z) {
        entries.add(new CompositeSchematicEntry(schematic, x, y, z));
        this.x = Math.max(this.x, x + schematic.widthX());
        this.y = Math.max(this.y, y + schematic.heightY());
        this.z = Math.max(this.z, z + schematic.lengthZ());
    }
    private CompositeSchematicEntry entryAt(int x, int y, int z, BlockState state) {
        return entries.stream().filter(entry ->
                x >= entry.x && y >= entry.y && z >= entry.z
                        && entry.schematic.inSchematic(
                                x - entry.x, y - entry.y, z - entry.z, state))
                .findFirst().orElse(null);
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
    @Override public void reset() { entries.forEach(entry -> entry.schematic.reset()); }
}
