package baritone.utils.schematic.format;

import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Sponge schematic v1/v2 parser, adapted directly from upstream Baritone. */
public final class SpongeSchematic extends StaticSchematic {
    public SpongeSchematic(CompoundTag nbt) {
        x = nbt.getInt("Width").orElse(0);
        y = nbt.getInt("Height").orElse(0);
        z = nbt.getInt("Length").orElse(0);
        states = new BlockState[x][z][y];
        Map<Integer, BlockState> palette = new HashMap<>();
        CompoundTag paletteTag = nbt.getCompound("Palette").orElse(new CompoundTag());
        for (String serialized : paletteTag.keySet()) {
            palette.put(paletteTag.getInt(serialized).orElse(0), parseState(serialized));
        }
        byte[] raw = nbt.getByteArray("BlockData").orElseThrow();
        int offset = 0;
        for (int yy = 0; yy < y; yy++) {
            for (int zz = 0; zz < z; zz++) {
                for (int xx = 0; xx < x; xx++) {
                    int value = 0;
                    int shift = 0;
                    byte next;
                    do {
                        if (offset >= raw.length || shift >= 35)
                            throw new IllegalArgumentException("Invalid BlockData varint");
                        next = raw[offset++];
                        value |= (next & 0x7F) << shift;
                        shift += 7;
                    } while ((next & 0x80) != 0);
                    states[xx][zz][yy] = palette.getOrDefault(value, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static BlockState parseState(String text) {
        int bracket = text.indexOf('[');
        String idText = bracket < 0 ? text : text.substring(0, bracket);
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(id);
        if (block == null) block = Blocks.AIR;
        BlockState state = block.defaultBlockState();
        if (bracket >= 0 && text.endsWith("]")) {
            for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2) continue;
                Property<?> property = block.getStateDefinition().getProperty(parts[0]);
                if (property != null) state = setProperty(state, property, parts[1]);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(
            BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.map(v -> state.setValue(property, v)).orElse(state);
    }
}
