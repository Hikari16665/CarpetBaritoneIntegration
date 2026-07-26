package baritone.api.utils;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public final class BlockOptionalMetaLookup {
    private final BlockOptionalMeta[] selectors;
    public BlockOptionalMetaLookup(BlockOptionalMeta... selectors) { this.selectors = selectors; }
    public BlockOptionalMetaLookup(Block... blocks) {
        this(Stream.of(blocks).map(BlockOptionalMeta::new).toArray(BlockOptionalMeta[]::new));
    }
    public BlockOptionalMetaLookup(String... blocks) {
        this(Stream.of(blocks).map(BlockOptionalMeta::new).toArray(BlockOptionalMeta[]::new));
    }
    public boolean has(Block block) { return Arrays.stream(selectors).anyMatch(s -> s.matches(block)); }
    public boolean has(BlockState state) { return Arrays.stream(selectors).anyMatch(s -> s.matches(state)); }
    public boolean has(ItemStack stack) { return Arrays.stream(selectors).anyMatch(s -> s.matches(stack)); }
    public List<BlockOptionalMeta> blocks() { return Arrays.asList(selectors); }
}
