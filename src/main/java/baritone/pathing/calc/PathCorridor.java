package baritone.pathing.calc;

/** Horizontal admission filter used by the block-level refinement search. */
@FunctionalInterface
public interface PathCorridor {
    PathCorridor UNBOUNDED = (x, z) -> true;

    boolean contains(int x, int z);
}
