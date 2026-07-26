package baritone.utils.schematic.format;

import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;

/** Direct, dedicated-server-safe reader for the Litematica NBT format. */
public final class LitematicaSchematic extends CompositeSchematic implements IStaticSchematic {
    public LitematicaSchematic(CompoundTag root) throws IOException {
        super(0, 0, 0);
        CompoundTag regions = root.getCompound("Regions").orElseThrow(
                () -> new IOException("Litematic has no Regions compound"));
        CompoundTag[] values = regions.values().stream()
                .filter(CompoundTag.class::isInstance)
                .map(CompoundTag.class::cast).toArray(CompoundTag[]::new);
        if (values.length == 0) throw new IOException("Litematic has no regions");
        Vec3i minimum = new Vec3i(minimum(values, "x"), minimum(values, "y"), minimum(values, "z"));
        for (CompoundTag region : values) readRegion(region, minimum);
    }

    private static int minimum(CompoundTag[] regions, String axis) {
        int minimum = Integer.MAX_VALUE;
        for (CompoundTag region : regions) minimum = Math.min(minimum, regionMinimum(region, axis));
        return minimum;
    }

    private static int regionMinimum(CompoundTag region, String axis) {
        int position = region.getCompound("Position").flatMap(tag -> tag.getInt(axis)).orElse(0);
        int size = region.getCompound("Size").flatMap(tag -> tag.getInt(axis)).orElse(0);
        return Math.min(position, position + size + 1);
    }

    private void readRegion(CompoundTag region, Vec3i minimum) throws IOException {
        ListTag paletteTag = region.getListOrEmpty("BlockStatePalette");
        if (paletteTag.isEmpty()) throw new IOException("Litematic region has an empty palette");
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = readState((CompoundTag) paletteTag.get(i));
        }
        CompoundTag sizeTag = region.getCompound("Size").orElse(new CompoundTag());
        int sizeX = Math.abs(sizeTag.getInt("x").orElse(0));
        int sizeY = Math.abs(sizeTag.getInt("y").orElse(0));
        int sizeZ = Math.abs(sizeTag.getInt("z").orElse(0));
        long volume = (long) sizeX * sizeY * sizeZ;
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
        PackedStates packed = new PackedStates(bits, volume,
                region.getLongArray("BlockStates").orElse(new long[0]));
        BlockState[][][] states = new BlockState[sizeX][sizeZ][sizeY];
        long index = 0;
        for (int y = 0; y < sizeY; y++) for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                int paletteIndex = packed.get(index++);
                if (paletteIndex >= palette.length) throw new IOException("Invalid palette index");
                states[x][z][y] = palette[paletteIndex];
            }
        }
        put(new StaticSchematic(states),
                regionMinimum(region, "x") - minimum.getX(),
                regionMinimum(region, "y") - minimum.getY(),
                regionMinimum(region, "z") - minimum.getZ());
    }

    private static BlockState readState(CompoundTag tag) {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("Name").orElse(""));
        Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id)
                .map(Holder.Reference::value).orElse(Blocks.AIR);
        BlockState state = block.defaultBlockState();
        CompoundTag properties = tag.getCompound("Properties").orElse(new CompoundTag());
        for (String name : properties.keySet()) {
            Property<?> property = block.getStateDefinition().getProperty(name);
            if (property != null) state = setProperty(state, property, properties.getString(name).orElse(""));
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(
            BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.map(entry -> state.setValue(property, entry)).orElse(state);
    }

    @Override public BlockState getDirect(int x, int y, int z) {
        return desiredState(x, y, z, null, Collections.emptyList());
    }

    private static final class PackedStates {
        private final int bits;
        private final long mask;
        private final long size;
        private final long[] data;
        private PackedStates(int bits, long size, long[] data) throws IOException {
            this.bits = bits;
            this.mask = (1L << bits) - 1L;
            this.size = size;
            this.data = data;
            long needed = (size * bits + 63L) / 64L;
            if (data.length < needed) throw new IOException("Truncated litematic BlockStates array");
        }
        private int get(long index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
            long offset = index * bits;
            int first = (int) (offset >>> 6);
            int shift = (int) (offset & 63);
            long value = data[first] >>> shift;
            if (shift + bits > 64) value |= data[first + 1] << (64 - shift);
            return (int) (value & mask);
        }
    }
}
