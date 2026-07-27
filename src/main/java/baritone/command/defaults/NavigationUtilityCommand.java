package baritone.command.defaults;

import baritone.api.IBaritone;

/** Server-safe ports of upstream navigation and diagnostic commands. */
public final class NavigationUtilityCommand extends ServerCommand {
    public NavigationUtilityCommand(IBaritone baritone) {
        super(baritone, "路径目标、诊断与导航工具",
                "goal", "path", "proc", "eta", "version",
                "repack", "surface", "top", "thisway", "forward",
                "axis", "highway", "tunnel",
                "sel", "selection", "s",
                "waypoints", "waypoint", "wp",
                "sethome", "home",
                "blacklist", "find", "pickup",
                "reloadall", "saveall", "gc");
    }
}
