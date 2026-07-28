package baritone.server;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PrinterPlacementRulesTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void stackedStatesConsumeTheirActualItemCount() {
        assertEquals(4,
                ServerFakeInteractionController
                        .printerPlacementItemCount(
                                Blocks.CANDLE.defaultBlockState()
                                        .setValue(CandleBlock.CANDLES, 4)));
        assertEquals(7,
                ServerFakeInteractionController
                        .printerPlacementItemCount(
                                Blocks.SNOW.defaultBlockState()
                                        .setValue(SnowLayerBlock.LAYERS, 7)));
    }

    @Test
    public void doubleSlabConsumesTwoItems() {
        assertEquals(2,
                ServerFakeInteractionController
                        .printerPlacementItemCount(
                                Blocks.STONE_SLAB.defaultBlockState()
                                        .setValue(SlabBlock.TYPE,
                                                SlabType.DOUBLE)));
        assertEquals(1,
                ServerFakeInteractionController
                        .printerPlacementItemCount(
                                Blocks.STONE_SLAB.defaultBlockState()));
    }
}
