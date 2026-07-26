package baritone.api.schematic.mask.operator;

import baritone.api.schematic.mask.AbstractMask;
import baritone.api.schematic.mask.Mask;
import baritone.api.schematic.mask.StaticMask;
import net.minecraft.world.level.block.state.BlockState;

public final class NotMask extends AbstractMask {
    private final Mask source;
    public NotMask(Mask source) {
        super(source.widthX(), source.heightY(), source.lengthZ());
        this.source = source;
    }
    @Override public boolean partOfMask(int x, int y, int z, BlockState current) {
        return !source.partOfMask(x, y, z, current);
    }
    public static final class Static extends AbstractMask implements StaticMask {
        private final StaticMask source;
        public Static(StaticMask source) {
            super(source.widthX(), source.heightY(), source.lengthZ());
            this.source = source;
        }
        @Override public boolean partOfMask(int x, int y, int z) {
            return !source.partOfMask(x, y, z);
        }
    }
}
