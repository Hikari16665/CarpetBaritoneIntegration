package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import java.util.stream.Stream;

public enum ItemById implements IDatatypeFor<Item> {
    INSTANCE;
    @Override public Item get(IDatatypeContext context) throws CommandException {
        String value = context.getConsumer().getString();
        ResourceLocation id = ResourceLocation.tryParse(
                value.contains(":") ? value : "minecraft:" + value);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown item " + value);
        }
        return BuiltInRegistries.ITEM.getValue(id);
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) throws CommandException {
        String prefix = context.getConsumer().getString().toLowerCase();
        return BuiltInRegistries.ITEM.keySet().stream().map(ResourceLocation::toString)
                .filter(id -> id.startsWith(prefix)
                        || id.startsWith("minecraft:" + prefix)).sorted();
    }
}
