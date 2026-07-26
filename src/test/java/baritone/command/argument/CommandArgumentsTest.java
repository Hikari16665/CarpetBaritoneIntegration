package baritone.command.argument;

import baritone.api.command.argument.ICommandArgument;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class CommandArgumentsTest {
    @Test
    public void parsesQuotedArgumentsAndRawRest() {
        List<ICommandArgument> arguments = CommandArguments.from(
                "  mine \"minecraft:diamond_ore\" 12", false);
        assertEquals(3, arguments.size());
        assertEquals("mine", arguments.get(0).getValue());
        assertEquals("minecraft:diamond_ore", arguments.get(1).getValue());
        assertEquals("12", arguments.get(2).getValue());
        assertEquals("\"minecraft:diamond_ore\" 12", arguments.get(1).getRawRest());
    }

    @Test
    public void preservesTrailingEmptyArgumentForCompletion() {
        List<ICommandArgument> arguments = CommandArguments.from("goto ", true);
        assertEquals(2, arguments.size());
        assertEquals("", arguments.get(1).getValue());
    }
}
