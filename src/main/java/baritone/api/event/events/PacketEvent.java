package baritone.api.event.events;
import baritone.api.event.events.type.EventState;
/** Networking hooks are opaque on the dedicated-server API boundary. */
public final class PacketEvent {
    private final Object connection,packet; private final EventState state;
    public PacketEvent(Object connection,EventState state,Object packet){
        this.connection=connection;this.state=state;this.packet=packet;
    }
    public Object getNetworkManager(){return connection;}
    public EventState getState(){return state;} public Object getPacket(){return packet;}
    @SuppressWarnings("unchecked") public <T>T cast(){return (T)packet;}
}
