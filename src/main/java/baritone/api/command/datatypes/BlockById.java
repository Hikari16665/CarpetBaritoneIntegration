package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import java.util.stream.Stream;

public enum BlockById implements IDatatypeFor<Block> {
    INSTANCE;
    @Override public Block get(IDatatypeContext context) throws CommandException {
        String value = context.getConsumer().getString();
        Identifier id = Identifier.tryParse(
                value.contains(":") ? value : "minecraft:" + value);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("unknown block " + value);
        }
        return BuiltInRegistries.BLOCK.getValue(id);
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) throws CommandException {
        String prefix = context.getConsumer().getString().toLowerCase();
        return BuiltInRegistries.BLOCK.keySet().stream().map(Identifier::toString)
                .filter(id -> id.startsWith(prefix)
                        || id.startsWith("minecraft:" + prefix)).sorted();
    }
}
