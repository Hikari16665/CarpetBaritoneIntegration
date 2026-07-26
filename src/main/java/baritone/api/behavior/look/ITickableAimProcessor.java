/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.behavior.look;

import baritone.api.utils.Rotation;

public interface ITickableAimProcessor extends IAimProcessor {
    void tick();

    void advance(int ticks);

    Rotation nextRotation(Rotation rotation);
}
