package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CollectItemProcessTest {
    @Test
    public void collectionPathDisablesAllBlockModification() throws IOException {
        String context = Files.readString(Path.of(
                "src", "main", "java", "baritone", "pathing",
                "movement", "CalculationContext.java"));
        assertTrue(context.contains(
                "this.allowBreak = !collectOnly"));
        assertTrue(context.contains(
                "this.hasThrowaway = !collectOnly"));
        assertTrue(context.contains(
                "this.hasWaterBucket = !collectOnly"));
        assertTrue(context.contains(
                "this.allowParkourPlace = !collectOnly"));
    }

    @Test
    public void collectorNeverInvokesBlockInteractionTask() throws IOException {
        String process = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "CollectItemProcess.java"));
        assertFalse(process.contains("BlockInteractionTask"));
        assertFalse(process.contains("destroyBlock("));
        assertFalse(process.contains("setBlock("));
        assertTrue(process.contains("isFullTargetShulker"));
        assertTrue(process.contains("CHUNKS_PER_TICK"));
        assertTrue(process.contains("deliveredAmount"));
        assertTrue(process.contains("背包已满，先投递当前批次"));
        assertTrue(process.contains("没有找到目标物品"));
        assertTrue(process.contains("目标物品没有找全"));
    }
}
