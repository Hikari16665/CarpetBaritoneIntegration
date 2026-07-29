package net.minecraft.util;

/**
 * Compatibility pair for code migrated from pre-26.2 Minecraft, where this
 * small utility was part of the game API.
 */
public final class Tuple<A, B> {
    private final A a;
    private final B b;

    public Tuple(A a, B b) {
        this.a = a;
        this.b = b;
    }

    public A getA() {
        return a;
    }

    public B getB() {
        return b;
    }
}
