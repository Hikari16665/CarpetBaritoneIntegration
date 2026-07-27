package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class BuilderExactPlacementTest {
    @Test
    public void previewsVanillaStateBeforeFakePlacement()
            throws Exception {
        String fake = Files.readString(Path.of(
                "src", "main", "java", "baritone", "server",
                "ServerFakeInteractionController.java"));
        String builder = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "BuilderProcess.java"));

        assertTrue(fake.contains("placeSelectedBlockMatching"));
        assertTrue(fake.contains("getStateForPlacement"));
        assertTrue(fake.contains("acceptableState.test(preview)"));
        assertTrue(fake.contains("quarterTurn < 4"));
        assertTrue(builder.contains("placeSelectedBlockMatching"));
        assertTrue(builder.contains(
                "preview -> sameEnough(preview, desired)"));
    }
}
