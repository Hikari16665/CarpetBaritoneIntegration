package baritone.event;

import baritone.Baritone;
import baritone.api.event.events.*;
import baritone.api.event.listener.IEventBus;
import baritone.api.event.listener.IGameEventListener;
import baritone.cache.ServerWorldCache;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Per-Baritone event bus with the original listener surface. */
public final class GameEventHandler implements IEventBus {
    private final Baritone baritone;
    private final List<IGameEventListener> listeners = new CopyOnWriteArrayList<>();
    public GameEventHandler(Baritone baritone){this.baritone=baritone;}
    @Override public void registerEventListener(IGameEventListener listener){listeners.add(listener);}
    private void dispatch(Consumer<IGameEventListener> action){listeners.forEach(action);}
    @Override public void onTick(TickEvent e){dispatch(l->l.onTick(e));}
    @Override public void onPostTick(TickEvent e){dispatch(l->l.onPostTick(e));}
    @Override public void onPlayerUpdate(PlayerUpdateEvent e){dispatch(l->l.onPlayerUpdate(e));}
    @Override public void onSendChatMessage(ChatEvent e){dispatch(l->l.onSendChatMessage(e));}
    @Override public void onPreTabComplete(TabCompleteEvent e){dispatch(l->l.onPreTabComplete(e));}
    @Override public void onChunkEvent(ChunkEvent e){
        if(e.getType()==ChunkEvent.Type.UNLOAD){
            var chunk=baritone.getPlayerContext().world().getChunkSource().getChunkNow(e.getX(),e.getZ());
            if(chunk!=null) baritone.getWorldCache().queueForPacking(chunk);
        }
        dispatch(l->l.onChunkEvent(e));
    }
    @Override public void onBlockChange(BlockChangeEvent e){
        ServerWorldCache.get(baritone.getPlayerContext().world()).invalidateChunk(
                e.getChunkPos().x,e.getChunkPos().z);
        dispatch(l->l.onBlockChange(e));
    }
    @Override public void onRenderPass(RenderEvent e){dispatch(l->l.onRenderPass(e));}
    @Override public void onWorldEvent(WorldEvent e){dispatch(l->l.onWorldEvent(e));}
    @Override public void onSendPacket(PacketEvent e){dispatch(l->l.onSendPacket(e));}
    @Override public void onReceivePacket(PacketEvent e){dispatch(l->l.onReceivePacket(e));}
    @Override public void onPlayerRotationMove(RotationMoveEvent e){dispatch(l->l.onPlayerRotationMove(e));}
    @Override public void onPlayerSprintState(SprintStateEvent e){dispatch(l->l.onPlayerSprintState(e));}
    @Override public void onBlockInteract(BlockInteractEvent e){dispatch(l->l.onBlockInteract(e));}
    @Override public void onPlayerDeath(){dispatch(IGameEventListener::onPlayerDeath);}
    @Override public void onPathEvent(PathEvent e){dispatch(l->l.onPathEvent(e));}
}
