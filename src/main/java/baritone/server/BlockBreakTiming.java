package baritone.server;

/** Pure policy for vanilla-speed server-side block breaking. */
final class BlockBreakTiming {

    private BlockBreakTiming() {}

    /**
     * A destroy-progress contribution of one completes the block in the
     * current game tick. Zero-hardness blocks are also instant even where
     * vanilla reports zero progress for their state.
     */
    static boolean isInstant(double destroyProgress, float hardness) {
        return hardness == 0.0F
                || Double.isFinite(destroyProgress)
                && destroyProgress >= 1.0D;
    }

    /** Instamine must neither create nor erase an ordinary-break cooldown. */
    static long afterSuccessfulBreak(
            long currentDeadline, long gameTime,
            int configuredDelay, boolean instantBreak) {
        if (instantBreak) return currentDeadline;
        return gameTime + Math.max(1, configuredDelay);
    }
}
