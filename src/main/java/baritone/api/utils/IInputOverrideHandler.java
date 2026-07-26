/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.utils;

import baritone.api.utils.input.Input;
import baritone.api.behavior.IBehavior;

public interface IInputOverrideHandler extends IBehavior {
    boolean isInputForcedDown(Input input);

    void setInputForceState(Input input, boolean forced);

    void clearAllKeys();
}
