package baritone.api.schematic;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;
public final class MirroredSchematic implements ISchematic {
    private final ISchematic source; private final Mirror mirror;
    public MirroredSchematic(ISchematic source,Mirror mirror){this.source=source;this.mirror=mirror;}
    private int x(int x){return mirror==Mirror.FRONT_BACK?widthX()-x-1:x;}
    private int z(int z){return mirror==Mirror.LEFT_RIGHT?lengthZ()-z-1:z;}
    private BlockState state(BlockState state){return state==null?null:state.mirror(mirror);}
    private List<BlockState> states(List<BlockState> states){return states==null?null:states.stream().map(this::state).toList();}
    public boolean inSchematic(int x,int y,int z,BlockState current){return source.inSchematic(x(x),y,z(z),state(current));}
    public BlockState desiredState(int x,int y,int z,BlockState current,List<BlockState> placeable){return state(source.desiredState(x(x),y,z(z),state(current),states(placeable)));}
    public void reset(){source.reset();} public int widthX(){return source.widthX();}
    public int heightY(){return source.heightY();} public int lengthZ(){return source.lengthZ();}
}
