/*
 * This file is part of Baritone, licensed under LGPL-3.0.
 */
package baritone.api.pathing.calc;

import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;

import java.util.Optional;

public interface IPathingControlManager {
    void registerProcess(IBaritoneProcess process);

    Optional<IBaritoneProcess> mostRecentInControl();

    Optional<PathingCommand> mostRecentCommand();
}
