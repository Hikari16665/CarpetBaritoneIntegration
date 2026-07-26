/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.behavior;

import baritone.api.behavior.look.IAimProcessor;
import baritone.api.utils.Rotation;

public interface ILookBehavior extends IBehavior {
    void updateTarget(Rotation rotation, boolean blockInteract);

    IAimProcessor getAimProcessor();
}
