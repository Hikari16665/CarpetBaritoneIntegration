package baritone.api.process;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

public interface IGiveAllProcess extends IBaritoneProcess {
    void giveAll(ServerPlayer recipient, Consumer<String> feedback);
}
