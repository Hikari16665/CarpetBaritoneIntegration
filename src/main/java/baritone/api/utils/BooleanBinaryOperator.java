package baritone.api.utils;
@FunctionalInterface
public interface BooleanBinaryOperator {
    boolean applyAsBoolean(boolean first, boolean second);
}
