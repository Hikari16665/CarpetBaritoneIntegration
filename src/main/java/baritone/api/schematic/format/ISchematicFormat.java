package baritone.api.schematic.format;

import baritone.api.schematic.IStaticSchematic;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface ISchematicFormat {
    IStaticSchematic parse(InputStream input) throws IOException;
    boolean isFileType(File file);
    List<String> getFileExtensions();
}
