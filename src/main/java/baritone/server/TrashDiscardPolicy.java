package baritone.server;

/** Pure arithmetic used by {@link TrashDiscardController}. */
final class TrashDiscardPolicy {
    private TrashDiscardPolicy() { }

    static int requiredReserve(
            int configuredBase, int configuredMaximum,
            int plannedPlacements, int safetyMargin) {
        int base = Math.max(0, configuredBase);
        int maximum = Math.max(base, configuredMaximum);
        long planned = (long) Math.max(0, plannedPlacements)
                + Math.max(0, safetyMargin);
        return (int) Math.min(maximum, Math.max(base, planned));
    }

    static int discardableFromStack(
            int stackCount, boolean usableForPathing,
            int totalUsableTrashBlocks, int requiredReserve) {
        int available = Math.max(0, stackCount);
        if (!usableForPathing) return available;
        int surplus = Math.max(0,
                totalUsableTrashBlocks - Math.max(0, requiredReserve));
        return Math.min(available, surplus);
    }
}
