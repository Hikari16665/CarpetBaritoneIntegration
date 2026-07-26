package baritone.api.event.events;
import baritone.api.event.events.type.EventState;
import net.minecraft.server.level.ServerLevel;
public final class WorldEvent {
    private final ServerLevel world; private final EventState state;
    public WorldEvent(ServerLevel world,EventState state){this.world=world;this.state=state;}
    public ServerLevel getWorld(){return world;} public EventState getState(){return state;}
}
