package baritone.api.utils;
public final class BooleanBinaryOperators {
    public static final BooleanBinaryOperator AND=(a,b)->a&&b;
    public static final BooleanBinaryOperator OR=(a,b)->a||b;
    public static final BooleanBinaryOperator XOR=(a,b)->a^b;
    private BooleanBinaryOperators(){}
}
