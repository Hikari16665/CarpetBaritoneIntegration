package baritone.command.argparser;

import baritone.api.command.argparser.IArgParser;
import baritone.command.argument.CommandArgument;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ArgParserManagerTest {
    @Test
    public void parsesDefaultNumbersAndBooleanAliases() throws Exception {
        assertEquals(Integer.valueOf(42), new CommandArgument(0, "42", "42").getAs(Integer.class));
        assertEquals(Boolean.TRUE, new CommandArgument(0, "on", "on").getAs(Boolean.class));
    }

    @Test
    public void newlyRegisteredParserOverridesDefault() throws Exception {
        IArgParser.Stateless<Integer> parser = new IArgParser.Stateless<>() {
            @Override public Class<Integer> getTarget() { return Integer.class; }
            @Override public Integer parseArg(
                    baritone.api.command.argument.ICommandArgument argument) {
                return 99;
            }
        };
        ArgParserManager.INSTANCE.getRegistry().register(parser);
        try {
            assertEquals(Integer.valueOf(99),
                    new CommandArgument(0, "1", "1").getAs(Integer.class));
        } finally {
            ArgParserManager.INSTANCE.getRegistry().unregister(parser);
        }
        assertTrue(new CommandArgument(0, "1", "1").is(Integer.class));
    }
}
