package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import net.minecraft.world.entity.player.Player;
import java.util.stream.Stream;

public enum NearbyPlayer implements IDatatypeFor<Player> {
    INSTANCE;
    @Override public Player get(IDatatypeContext context) throws CommandException {
        String name = context.getConsumer().getString();
        return context.getBaritone().getPlayerContext().world().players().stream()
                .filter(player -> player.getScoreboardName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }
    @Override public Stream<String> tabComplete(IDatatypeContext context) throws CommandException {
        String prefix = context.getConsumer().getString().toLowerCase();
        return context.getBaritone().getPlayerContext().world().players().stream()
                .map(Player::getScoreboardName)
                .filter(name -> name.toLowerCase().startsWith(prefix)).sorted();
    }
}
