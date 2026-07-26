/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.behavior.look;

import baritone.api.utils.Rotation;

public interface IAimProcessor {
    Rotation peekRotation(Rotation desired);

    ITickableAimProcessor fork();
}
