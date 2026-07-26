package baritone.api.process;

import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import net.minecraft.world.level.block.Block;

import java.util.stream.Stream;

public interface IMineProcess extends IBaritoneProcess {
    void mineByName(int quantity, String... blocks);
    void mine(int quantity, BlockOptionalMetaLookup filter);
    default void mine(BlockOptionalMetaLookup filter) { mine(0, filter); }
    default void mineByName(String... blocks) { mineByName(0, blocks); }
    default void mine(int quantity, BlockOptionalMeta... selectors) {
        mine(quantity, new BlockOptionalMetaLookup(selectors));
    }
    default void mine(int quantity, Block... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(Stream.of(blocks)
                .map(BlockOptionalMeta::new).toArray(BlockOptionalMeta[]::new)));
    }
    default void mine(Block... blocks) { mine(0, blocks); }
    default void cancel() { onLostControl(); }
}
