package baritone.api.event.events;
import net.minecraft.core.BlockPos;
public final class BlockInteractEvent {
    private final BlockPos pos; private final Type type;
    public BlockInteractEvent(BlockPos pos,Type type){this.pos=pos;this.type=type;}
    public BlockPos getPos(){return pos;} public Type getType(){return type;}
    public enum Type { START_BREAK, USE }
}
