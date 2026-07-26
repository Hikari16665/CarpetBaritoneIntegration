package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class GiveAllProcessTest {
    @Test
    public void givesInventoryOffhandAndEquipmentWithoutBlockChanges()
            throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "GiveAllProcess.java"));
        assertTrue(source.contains("getNonEquipmentItems"));
        assertTrue(source.contains("EquipmentSlot.OFFHAND"));
        assertTrue(source.contains("EquipmentSlot.CHEST"));
        assertTrue(source.contains("isProtectedStack"));
        assertTrue(source.contains("new GoalNear"));
    }
}
