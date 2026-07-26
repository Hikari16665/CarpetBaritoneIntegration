package baritone.api.schematic;

import baritone.api.command.registry.Registry;
import baritone.api.schematic.format.ISchematicFormat;
import java.io.File;
import java.util.List;
import java.util.Optional;

public interface ISchematicSystem {
    Registry<ISchematicFormat> getRegistry();
    Optional<ISchematicFormat> getByFile(File file);
    List<String> getFileExtensions();
}
