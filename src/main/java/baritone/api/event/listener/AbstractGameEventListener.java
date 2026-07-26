package baritone.api.event.listener;
import baritone.api.event.events.*;
public interface AbstractGameEventListener extends IGameEventListener {
    default void onTick(TickEvent e){} default void onPostTick(TickEvent e){}
    default void onPlayerUpdate(PlayerUpdateEvent e){} default void onSendChatMessage(ChatEvent e){}
    default void onPreTabComplete(TabCompleteEvent e){} default void onChunkEvent(ChunkEvent e){}
    default void onBlockChange(BlockChangeEvent e){} default void onRenderPass(RenderEvent e){}
    default void onWorldEvent(WorldEvent e){} default void onSendPacket(PacketEvent e){}
    default void onReceivePacket(PacketEvent e){} default void onPlayerRotationMove(RotationMoveEvent e){}
    default void onPlayerSprintState(SprintStateEvent e){} default void onBlockInteract(BlockInteractEvent e){}
    default void onPlayerDeath(){} default void onPathEvent(PathEvent e){}
}
