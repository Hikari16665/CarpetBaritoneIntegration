package baritone.api.utils;

public final class Pair<A, B> {
    private final A first;
    private final B second;
    public Pair(A first, B second) { this.first = first; this.second = second; }
    public A first() { return first; }
    public B second() { return second; }
    public A getFirst() { return first; }
    public B getSecond() { return second; }
}
