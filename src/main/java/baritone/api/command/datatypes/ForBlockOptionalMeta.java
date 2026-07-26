package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import baritone.api.utils.BlockOptionalMeta;
import java.util.stream.Stream;

public enum ForBlockOptionalMeta implements IDatatypeFor<BlockOptionalMeta> {
    INSTANCE;
    @Override public BlockOptionalMeta get(IDatatypeContext context) throws CommandException {
        return new BlockOptionalMeta(context.getConsumer().getString());
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) throws CommandException {
        return context.getConsumer().tabCompleteDatatype(BlockById.INSTANCE);
    }
}
