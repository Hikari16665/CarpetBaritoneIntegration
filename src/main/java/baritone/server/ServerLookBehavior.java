/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.server;

import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.utils.Rotation;

import java.util.Objects;

/** Applies Baritone look targets through Carpet's fake-player action pack. */
public final class ServerLookBehavior implements ILookBehavior {
    private final CarpetInputController inputController;
    private final IAimProcessor aimProcessor = new DirectAimProcessor();

    public ServerLookBehavior(CarpetInputController inputController) {
        this.inputController = Objects.requireNonNull(inputController, "inputController");
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract) {
        inputController.setTargetRotation(aimProcessor.peekRotation(rotation));
    }

    @Override
    public IAimProcessor getAimProcessor() {
        return aimProcessor;
    }

    private static final class DirectAimProcessor implements ITickableAimProcessor {
        @Override
        public Rotation peekRotation(Rotation desired) {
            return desired.normalizeAndClamp();
        }

        @Override
        public ITickableAimProcessor fork() {
            return new DirectAimProcessor();
        }

        @Override
        public void tick() {
        }

        @Override
        public void advance(int ticks) {
        }

        @Override
        public Rotation nextRotation(Rotation rotation) {
            return peekRotation(rotation);
        }
    }
}
