package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import java.util.stream.Stream;

public enum EntityClassById implements IDatatypeFor<EntityType<?>> {
    INSTANCE;
    @Override public EntityType<?> get(IDatatypeContext context) throws CommandException {
        String value = context.getConsumer().getString();
        ResourceLocation id = ResourceLocation.tryParse(
                value.contains(":") ? value : "minecraft:" + value);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            throw new IllegalArgumentException("unknown entity " + value);
        }
        return BuiltInRegistries.ENTITY_TYPE.get(id);
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) throws CommandException {
        String prefix = context.getConsumer().getString().toLowerCase();
        return BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(ResourceLocation::toString)
                .filter(id -> id.startsWith(prefix)
                        || id.startsWith("minecraft:" + prefix)).sorted();
    }
}
