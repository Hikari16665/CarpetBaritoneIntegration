package baritone.api.schematic.mask;

import baritone.api.schematic.mask.operator.BinaryOperatorMask;
import baritone.api.schematic.mask.operator.NotMask;
import baritone.api.utils.BooleanBinaryOperators;
import net.minecraft.world.level.block.state.BlockState;

public interface StaticMask extends Mask {
    boolean partOfMask(int x, int y, int z);
    @Override default boolean partOfMask(int x, int y, int z, BlockState current) {
        return partOfMask(x, y, z);
    }
    @Override default StaticMask not() { return new NotMask.Static(this); }
    default StaticMask union(StaticMask other) {
        return new BinaryOperatorMask.Static(this, other, BooleanBinaryOperators.OR);
    }
    default StaticMask intersection(StaticMask other) {
        return new BinaryOperatorMask.Static(this, other, BooleanBinaryOperators.AND);
    }
    default StaticMask xor(StaticMask other) {
        return new BinaryOperatorMask.Static(this, other, BooleanBinaryOperators.XOR);
    }
    default StaticMask compute() { return new PreComputedMask(this); }
}
