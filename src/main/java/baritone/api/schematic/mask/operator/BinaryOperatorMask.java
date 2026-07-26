package baritone.api.schematic.mask.operator;

import baritone.api.schematic.mask.AbstractMask;
import baritone.api.schematic.mask.Mask;
import baritone.api.schematic.mask.StaticMask;
import baritone.api.utils.BooleanBinaryOperator;
import net.minecraft.world.level.block.state.BlockState;

public final class BinaryOperatorMask extends AbstractMask {
    private final Mask first;
    private final Mask second;
    private final BooleanBinaryOperator operator;
    public BinaryOperatorMask(Mask first, Mask second, BooleanBinaryOperator operator) {
        super(Math.max(first.widthX(), second.widthX()),
                Math.max(first.heightY(), second.heightY()),
                Math.max(first.lengthZ(), second.lengthZ()));
        this.first = first;
        this.second = second;
        this.operator = operator;
    }
    @Override public boolean partOfMask(int x, int y, int z, BlockState current) {
        return operator.applyAsBoolean(test(first, x, y, z, current), test(second, x, y, z, current));
    }
    private static boolean test(Mask mask, int x, int y, int z, BlockState current) {
        return x >= 0 && y >= 0 && z >= 0
                && x < mask.widthX() && y < mask.heightY() && z < mask.lengthZ()
                && mask.partOfMask(x, y, z, current);
    }

    public static final class Static extends AbstractMask implements StaticMask {
        private final StaticMask first;
        private final StaticMask second;
        private final BooleanBinaryOperator operator;
        public Static(StaticMask first, StaticMask second, BooleanBinaryOperator operator) {
            super(Math.max(first.widthX(), second.widthX()),
                    Math.max(first.heightY(), second.heightY()),
                    Math.max(first.lengthZ(), second.lengthZ()));
            this.first = first;
            this.second = second;
            this.operator = operator;
        }
        @Override public boolean partOfMask(int x, int y, int z) {
            return operator.applyAsBoolean(test(first, x, y, z), test(second, x, y, z));
        }
        private static boolean test(StaticMask mask, int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0
                    && x < mask.widthX() && y < mask.heightY() && z < mask.lengthZ()
                    && mask.partOfMask(x, y, z);
        }
    }
}
