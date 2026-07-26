package baritone.api.event.listener;

import baritone.api.event.events.*;

public interface IGameEventListener {
    void onTick(TickEvent event);
    void onPostTick(TickEvent event);
    void onPlayerUpdate(PlayerUpdateEvent event);
    void onSendChatMessage(ChatEvent event);
    void onPreTabComplete(TabCompleteEvent event);
    void onChunkEvent(ChunkEvent event);
    void onBlockChange(BlockChangeEvent event);
    void onRenderPass(RenderEvent event);
    void onWorldEvent(WorldEvent event);
    void onSendPacket(PacketEvent event);
    void onReceivePacket(PacketEvent event);
    void onPlayerRotationMove(RotationMoveEvent event);
    void onPlayerSprintState(SprintStateEvent event);
    void onBlockInteract(BlockInteractEvent event);
    void onPlayerDeath();
    void onPathEvent(PathEvent event);
}
