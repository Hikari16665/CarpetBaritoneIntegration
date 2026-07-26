/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.api;

import baritone.api.behavior.ILookBehavior;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.IPlayerContext;
import baritone.server.ServerInventoryController;
import baritone.api.cache.IWorldScanner;
import baritone.api.cache.IWorldProvider;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.event.listener.IEventBus;
import baritone.api.selection.ISelectionManager;
import baritone.cache.ServerWorldCache;
import baritone.api.command.manager.ICommandManager;
import baritone.api.behavior.IPathingBehavior;

/** Core instance contract, expanded as each upstream subsystem is rebuilt. */
public interface IBaritone {

    IPlayerContext getPlayerContext();

    IInputOverrideHandler getInputOverrideHandler();

    ILookBehavior getLookBehavior();

    ServerInventoryController getInventoryController();

    IWorldScanner getWorldScanner();

    ServerWorldCache getWorldCache();

    IWorldProvider getWorldProvider();

    IPathingControlManager getPathingControlManager();

    IEventBus getGameEventHandler();

    ISelectionManager getSelectionManager();

    ICommandManager getCommandManager();

    IPathingBehavior getPathingBehavior();
}
