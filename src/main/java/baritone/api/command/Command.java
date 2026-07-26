package baritone.api.command;

import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public abstract class Command implements ICommand {
    protected final IBaritone baritone;
    protected final IPlayerContext ctx;
    private final List<String> names;
    protected Command(IBaritone baritone, String... names) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.names = Arrays.stream(names).map(name -> name.toLowerCase(Locale.ROOT)).toList();
    }
    @Override public final List<String> getNames() { return names; }
}
