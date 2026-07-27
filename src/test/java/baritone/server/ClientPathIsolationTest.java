package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientPathIsolationTest {
    @Test
    public void commonInitializerNeverLoadsClientRenderer() throws IOException {
        String common = Files.readString(Path.of(
                "src", "main", "java", "me", "nuoyuan",
                "carpetbaritoneintegration",
                "Carpetbaritoneintegration.java"));
        assertFalse(common.contains(".client."));
        String metadata = Files.readString(Path.of(
                "src", "main", "resources", "fabric.mod.json"));
        assertTrue(metadata.contains("\"client\""));
        assertTrue(metadata.contains(
                "CarpetBaritoneIntegrationClient"));
    }
}
