package baritone.api.event.events;
import baritone.api.utils.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;
public final class BlockChangeEvent {
    private final ChunkPos pos; private final List<Pair<BlockPos,BlockState>> blocks;
    public BlockChangeEvent(ChunkPos pos,List<Pair<BlockPos,BlockState>> blocks){
        this.pos=pos;this.blocks=List.copyOf(blocks);
    }
    public ChunkPos getChunkPos(){return pos;}
    public List<Pair<BlockPos,BlockState>> getBlocks(){return blocks;}
}
