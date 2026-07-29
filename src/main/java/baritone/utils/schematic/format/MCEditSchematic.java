package baritone.utils.schematic.format;

import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.fixes.ItemIdFix;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Legacy MCEdit Alpha schematic parser, adapted from upstream Baritone. */
public final class MCEditSchematic extends StaticSchematic {
    public MCEditSchematic(CompoundTag schematic) {
        if (!"Alpha".equals(schematic.getString("Materials"))) {
            throw new IllegalArgumentException("Unsupported MCEdit material format");
        }
        x = schematic.getInt("Width");
        y = schematic.getInt("Height");
        z = schematic.getInt("Length");
        byte[] blocks = schematic.getByteArray("Blocks");
        byte[] additional = null;
        if (schematic.contains("AddBlocks")) {
            byte[] packed = schematic.getByteArray("AddBlocks");
            additional = new byte[packed.length * 2];
            for (int i = 0; i < packed.length; i++) {
                additional[i * 2] = (byte) ((packed[i] >> 4) & 0xF);
                additional[i * 2 + 1] = (byte) (packed[i] & 0xF);
            }
        }
        states = new BlockState[x][z][y];
        for (int yy = 0; yy < y; yy++) {
            for (int zz = 0; zz < z; zz++) {
                for (int xx = 0; xx < x; xx++) {
                    int index = (yy * z + zz) * x + xx;
                    int legacyId = blocks[index] & 0xFF;
                    if (additional != null && index < additional.length) {
                        legacyId |= additional[index] << 8;
                    }
                    ResourceLocation key =
                            ResourceLocation.tryParse(ItemIdFix.getItem(legacyId));
                    Block block = key == null ? Blocks.AIR
                            : BuiltInRegistries.BLOCK.get(key);
                    if (block == null) block = Blocks.AIR;
                    states[xx][zz][yy] = block.defaultBlockState();
                }
            }
        }
    }
}
