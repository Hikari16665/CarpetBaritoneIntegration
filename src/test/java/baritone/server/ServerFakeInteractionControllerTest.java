package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerFakeInteractionControllerTest {
    @Test
    public void fakeInteractionsKeepLookButDoNotEmitMouseInput()
            throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerFakeInteractionController.java"));
        assertTrue(source.contains("lookAt(BlockPos pos)"));
        assertTrue(source.contains("player.gameMode.destroyBlock(pos)"));
        assertTrue(source.contains("stack.useOn(new UseOnContext("));
        assertTrue(source.contains("entity.playerTouch(player)"));
        assertFalse(source.contains("Input.CLICK_LEFT"));
        assertFalse(source.contains("Input.CLICK_RIGHT"));
        assertFalse(source.contains("player.pick("));
    }

    @Test
    public void carpetControllerNeverForwardsLegacyAttackOrUse()
            throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "CarpetInputController.java"));
        assertTrue(source.contains(
                "Never forward attack/use to Carpet's real action"));
        assertFalse(source.contains(
                "applyAction(Input.CLICK_LEFT"));
        assertFalse(source.contains(
                "applyAction(Input.CLICK_RIGHT"));
        assertFalse(source.contains("player.pick("));
    }
}
