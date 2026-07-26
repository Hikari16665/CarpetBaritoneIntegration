/*
 * This file is part of Baritone and is licensed under LGPL-3.0.
 */
package baritone.api.utils.interfaces;

import net.minecraft.core.BlockPos;

/**
 * Optional client rendering hook retained by the server-side API.
 */
public interface IGoalRenderPos {

    BlockPos getGoalPos();
}
