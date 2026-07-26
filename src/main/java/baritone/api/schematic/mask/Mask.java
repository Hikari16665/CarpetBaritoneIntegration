package baritone.api.schematic.mask;

import baritone.api.schematic.mask.operator.BinaryOperatorMask;
import baritone.api.schematic.mask.operator.NotMask;
import baritone.api.utils.BooleanBinaryOperators;
import net.minecraft.world.level.block.state.BlockState;

public interface Mask {
    boolean partOfMask(int x, int y, int z, BlockState current);
    int widthX();
    int heightY();
    int lengthZ();
    default Mask not() { return new NotMask(this); }
    default Mask union(Mask other) {
        return new BinaryOperatorMask(this, other, BooleanBinaryOperators.OR);
    }
    default Mask intersection(Mask other) {
        return new BinaryOperatorMask(this, other, BooleanBinaryOperators.AND);
    }
    default Mask xor(Mask other) {
        return new BinaryOperatorMask(this, other, BooleanBinaryOperators.XOR);
    }
}
