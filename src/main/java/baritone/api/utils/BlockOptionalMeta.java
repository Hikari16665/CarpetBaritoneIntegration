package baritone.api.utils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Server-safe BlockOptionalMeta without the upstream client loot-table stub. */
public final class BlockOptionalMeta {
    private final Block block;
    private final Set<BlockState> states;

    public BlockOptionalMeta(Block block) {
        this.block = block;
        this.states = Set.copyOf(block.getStateDefinition().getPossibleStates());
    }

    public BlockOptionalMeta(String selector) {
        int bracket = selector.indexOf('[');
        String idText = bracket < 0 ? selector : selector.substring(0, bracket);
        ResourceLocation id = ResourceLocation.tryParse(
                idText.contains(":") ? idText : "minecraft:" + idText);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("Invalid block name " + idText);
        }
        this.block = BuiltInRegistries.BLOCK.getValue(id);
        Map<String, String> requested = bracket < 0
                ? Collections.emptyMap()
                : java.util.Arrays.stream(selector.substring(bracket + 1, selector.length() - 1)
                        .split(","))
                        .filter(part -> part.contains("="))
                        .map(part -> part.split("=", 2))
                        .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
        this.states = block.getStateDefinition().getPossibleStates().stream()
                .filter(matchesProperties(requested))
                .collect(Collectors.toUnmodifiableSet());
    }

    private Predicate<BlockState> matchesProperties(Map<String, String> requested) {
        return state -> requested.entrySet().stream().allMatch(entry -> {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            return property != null && state.getValue(property).toString().equals(entry.getValue());
        });
    }

    public Block getBlock() { return block; }
    public boolean matches(Block other) { return block == other; }
    public boolean matches(BlockState state) { return state.getBlock() == block && states.contains(state); }
    public boolean matches(ItemStack stack) { return stack.is(block.asItem()); }
    public BlockState getAnyBlockState() { return states.stream().findFirst().orElse(null); }
    public Set<BlockState> getAllBlockStates() { return states; }
    @Override public String toString() { return "BlockOptionalMeta{" + block + "}"; }
}
