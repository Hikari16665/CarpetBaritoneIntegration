package baritone.command.datatypes;

import baritone.api.command.datatypes.RelativeCoordinate;
import baritone.command.argument.ArgConsumer;
import baritone.command.argument.CommandArguments;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RelativeCoordinateTest {
    @Test
    public void supportsRelativeAndScaleSuffixes() throws Exception {
        ArgConsumer relative = new ArgConsumer(CommandArguments.from("~2.5", false));
        assertEquals(12.5D,
                relative.getDatatypePost(RelativeCoordinate.INSTANCE, 10D), 0D);
        ArgConsumer scaled = new ArgConsumer(CommandArguments.from("3k", false));
        assertEquals(3000D,
                scaled.getDatatypePost(RelativeCoordinate.INSTANCE, 10D), 0D);
    }
}
