/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.server;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.pathing.path.IPathExecutor;
import baritone.behavior.PathingBehavior;
import baritone.pathing.path.PathExecutor;
import baritone.pathing.movement.CalculationContext;

import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Server tick adapter around Baritone's original PathExecutor.
 *
 * The original executor owns all movement validation, block-change detection,
 * tool selection, movement timeouts, path skipping and off-path handling. This
 * class only supplies the server tick and keeps the small API used by commands.
 */
public final class ServerPathExecutor implements IPathExecutor {
    private final Baritone baritone;
    private final PathExecutor delegate;
    private MovementStatus lastStatus = MovementStatus.PREPPING;
    private boolean canceled;

    public ServerPathExecutor(Baritone baritone, PathingBehavior behavior, IPath path) {
        this(baritone, behavior, path, null);
    }

    public ServerPathExecutor(
            Baritone baritone, PathingBehavior behavior, IPath path,
            CalculationContext calculationContext) {
        this.baritone = Objects.requireNonNull(baritone, "baritone");
        Objects.requireNonNull(behavior, "behavior")
                .refreshCalculationContext(calculationContext);
        this.delegate = new PathExecutor(
                behavior, Objects.requireNonNull(path, "path"));
    }

    private ServerPathExecutor(Baritone baritone, PathExecutor delegate) {
        this.baritone = Objects.requireNonNull(baritone, "baritone");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public MovementStatus tick() {
        if (isFinished()) {
            clearInputs();
            return lastStatus;
        }

        boolean movementFinished = delegate.onTick();
        baritone.getInputController().tick();

        if (delegate.failed()) {
            lastStatus = MovementStatus.FAILED;
        } else if (delegate.finished()) {
            lastStatus = MovementStatus.SUCCESS;
        } else if (movementFinished) {
            lastStatus = MovementStatus.SUCCESS;
        } else {
            lastStatus = MovementStatus.RUNNING;
        }
        return lastStatus;
    }

    public void cancel() {
        canceled = true;
        lastStatus = MovementStatus.CANCELED;
        clearInputs();
    }

    private void clearInputs() {
        baritone.getInputController().setBlockBreakTarget(null);
        baritone.getInputController().clearAllKeys();
        baritone.getInputController().tick();
    }

    public boolean isFinished() {
        return canceled || delegate.failed() || delegate.finished();
    }

    public boolean failed() {
        return delegate.failed();
    }

    public boolean isSafeToCancel() {
        return delegate.isSafeToCancel();
    }

    public boolean snipsnapIfPossible() {
        return delegate.snipsnapifpossible();
    }

    public ServerPathExecutor trySplice(ServerPathExecutor next) {
        if (next == null) return this;
        PathExecutor spliced = delegate.trySplice(next.delegate);
        return spliced == delegate
                ? this
                : new ServerPathExecutor(baritone, spliced);
    }

    public Set<BlockPos> toBreak() {
        return delegate.toBreak();
    }

    public Set<BlockPos> toPlace() {
        return delegate.toPlace();
    }

    public Set<BlockPos> toWalkInto() {
        return delegate.toWalkInto();
    }

    public MovementStatus getLastStatus() {
        return lastStatus;
    }

    @Override
    public IPath getPath() {
        return delegate.getPath();
    }

    @Override
    public int getPosition() {
        return delegate.getPosition();
    }
}
