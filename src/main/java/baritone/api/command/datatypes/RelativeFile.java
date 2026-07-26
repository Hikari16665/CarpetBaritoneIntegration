package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import java.io.File;
import java.io.IOException;
import java.util.stream.Stream;

public enum RelativeFile implements IDatatypePost<File, File> {
    INSTANCE;
    @Override public File apply(IDatatypeContext context, File original) throws CommandException {
        File base = original == null ? new File(".") : original;
        try { return new File(base, context.getConsumer().getString()).getCanonicalFile(); }
        catch (IOException exception) {
            throw new IllegalArgumentException("invalid path", exception);
        }
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) { return Stream.empty(); }
}
