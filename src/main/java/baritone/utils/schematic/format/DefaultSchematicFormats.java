package baritone.utils.schematic.format;

import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Server-safe schematic formats. Client-only Litematica integration is deliberately
 * kept out of this registry; files are parsed directly from their serialized NBT.
 */
public enum DefaultSchematicFormats implements ISchematicFormat {
    SPONGE(List.of("schem")) {
        @Override
        protected IStaticSchematic parseTag(CompoundTag tag) throws IOException {
            CompoundTag schematic = tag.getCompound("Schematic").orElse(tag);
            int version = schematic.getInt("Version").orElse(0);
            if (version != 1 && version != 2) {
                throw new IOException("Unsupported Sponge schematic version " + version);
            }
            return new SpongeSchematic(schematic);
        }
    },
    MCEDIT(List.of("schematic")) {
        @Override
        protected IStaticSchematic parseTag(CompoundTag tag) {
            return new MCEditSchematic(tag);
        }
    },
    LITEMATICA(List.of("litematic")) {
        @Override
        protected IStaticSchematic parseTag(CompoundTag tag) throws IOException {
            return new LitematicaSchematic(tag);
        }
    };

    private final List<String> extensions;

    DefaultSchematicFormats(List<String> extensions) {
        this.extensions = extensions;
    }

    @Override
    public final IStaticSchematic parse(InputStream input) throws IOException {
        return parseTag(NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()));
    }

    protected abstract IStaticSchematic parseTag(CompoundTag tag) throws IOException;

    @Override
    public final boolean isFileType(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return extensions.stream().anyMatch(extension -> name.endsWith("." + extension));
    }

    @Override
    public final List<String> getFileExtensions() {
        return extensions;
    }

    public static DefaultSchematicFormats detect(File file) {
        for (DefaultSchematicFormats format : values()) {
            if (format.isFileType(file)) return format;
        }
        return null;
    }
}
