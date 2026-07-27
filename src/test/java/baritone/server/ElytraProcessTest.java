package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class ElytraProcessTest {
    @Test
    public void waveFlightOnlyBoostsInitialVerticalLaunch()
            throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "ElytraProcess.java"));
        assertTrue(source.contains("INITIAL_CLIMB"));
        assertTrue(source.contains("GLIDE_DOWN"));
        assertTrue(source.contains("CLIMB_BACK"));
        assertTrue(source.contains(
                "return state == State.INITIAL_CLIMB;"));
        assertTrue(source.contains("else pitch = -8.0F"));
    }
}
