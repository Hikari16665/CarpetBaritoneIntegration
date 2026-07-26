package baritone.api.event.events;
import baritone.api.event.events.type.EventState;
public final class ChunkEvent {
    private final EventState state; private final Type type; private final int x,z;
    public ChunkEvent(EventState state,Type type,int x,int z){
        this.state=state;this.type=type;this.x=x;this.z=z;
    }
    public EventState getState(){return state;} public Type getType(){return type;}
    public int getX(){return x;} public int getZ(){return z;}
    public boolean isPostPopulate(){return state==EventState.POST&&type.isPopulate();}
    public enum Type { LOAD,UNLOAD,POPULATE_FULL,POPULATE_PARTIAL;
        public boolean isPopulate(){return this==POPULATE_FULL||this==POPULATE_PARTIAL;}
    }
}
