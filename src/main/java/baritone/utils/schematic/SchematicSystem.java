package baritone.utils.schematic;

import baritone.api.command.registry.Registry;
import baritone.api.schematic.ISchematicSystem;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.utils.schematic.format.DefaultSchematicFormats;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum SchematicSystem implements ISchematicSystem {
    INSTANCE;
    private final Registry<ISchematicFormat> registry = new Registry<>();
    SchematicSystem() {
        Arrays.stream(DefaultSchematicFormats.values()).forEach(registry::register);
    }
    @Override public Registry<ISchematicFormat> getRegistry() { return registry; }
    @Override public Optional<ISchematicFormat> getByFile(File file) {
        return registry.stream().filter(format -> format.isFileType(file)).findFirst();
    }
    @Override public List<String> getFileExtensions() {
        return registry.stream().flatMap(format -> format.getFileExtensions().stream())
                .distinct().toList();
    }
}
