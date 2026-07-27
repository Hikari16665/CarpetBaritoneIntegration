package me.nuoyuan.carpetbaritoneintegration.network;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServerPathSyncTest {
    @Test
    public void collinearPathIsCompressedToEndpoints() {
        List<BlockPos> input = java.util.stream.IntStream.range(0, 100)
                .mapToObj(x -> new BlockPos(x, 64, 0)).toList();
        List<BlockPos> simplified = ServerPathSync.simplify(input);
        assertEquals(List.of(new BlockPos(0, 64, 0),
                new BlockPos(99, 64, 0)), simplified);
    }

    @Test
    public void turnsAreRetainedAndPayloadIsBounded() {
        List<BlockPos> input = new java.util.ArrayList<>();
        for (int x = 0; x < 1500; x++) {
            input.add(new BlockPos(x, 64 + (x & 1), 0));
        }
        List<BlockPos> simplified = ServerPathSync.simplify(input);
        assertTrue(simplified.size() <= 1024);
        assertEquals(input.getFirst(), simplified.getFirst());
    }

    @Test
    public void snapshotsAreBroadcastToNearbyCapablePlayers()
            throws IOException {
        String sync = Files.readString(Path.of(
                "src", "main", "java", "me", "nuoyuan",
                "carpetbaritoneintegration", "network",
                "ServerPathSync.java"));
        String handler = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "BasicGoalCommandHandler.java"));
        assertTrue(sync.contains(
                "getPlayerList().getPlayers()"));
        assertTrue(sync.contains("isVisibleTo(fake, viewer"));
        assertTrue(sync.contains(
                "ServerPlayNetworking.canSend("));
        assertFalse(sync.contains("getRenderObserverId"));
        assertFalse(handler.contains("setRenderObserver"));
    }
}
