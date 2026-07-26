/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.behavior;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.calc.IPathFinder;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.path.IPathExecutor;

import java.util.Optional;

public interface IPathingBehavior extends IBehavior {
    default Optional<Double> ticksRemainingInSegment() {
        return ticksRemainingInSegment(true);
    }

    default Optional<Double> ticksRemainingInSegment(boolean includeCurrentMovement) {
        IPathExecutor current = getCurrent();
        if (current == null) {
            return Optional.empty();
        }
        int start = includeCurrentMovement ? current.getPosition() : current.getPosition() + 1;
        return Optional.of(current.getPath().ticksRemainingFrom(start));
    }

    Optional<Double> estimatedTicksToGoal();

    Goal getGoal();

    boolean isPathing();

    default boolean hasPath() {
        return getCurrent() != null;
    }

    boolean cancelEverything();

    void forceCancel();

    default Optional<IPath> getPath() {
        return Optional.ofNullable(getCurrent()).map(IPathExecutor::getPath);
    }

    Optional<? extends IPathFinder> getInProgress();

    IPathExecutor getCurrent();

    IPathExecutor getNext();
}
