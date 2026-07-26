/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.process;

public interface IBaritoneProcess {
    double DEFAULT_PRIORITY = -1;

    boolean isActive();

    PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel);

    boolean isTemporary();

    void onLostControl();

    default double priority() {
        return DEFAULT_PRIORITY;
    }

    default String displayName() {
        return isActive() ? displayName0() : "INACTIVE";
    }

    String displayName0();
}
