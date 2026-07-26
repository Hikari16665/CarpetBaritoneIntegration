package baritone.api.command.manager;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.registry.Registry;

import java.util.stream.Stream;
import java.util.List;
import net.minecraft.util.Tuple;
import baritone.api.command.argument.ICommandArgument;

public interface ICommandManager {
    IBaritone getBaritone();
    Registry<ICommand> getRegistry();
    ICommand getCommand(String name);
    boolean execute(String command);
    boolean execute(Tuple<String, List<ICommandArgument>> expanded);
    Stream<String> tabComplete(Tuple<String, List<ICommandArgument>> expanded);
    Stream<String> tabComplete(String prefix);
}
