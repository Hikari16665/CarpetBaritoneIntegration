package baritone.api.schematic;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;
public final class RotatedSchematic implements ISchematic {
    private final ISchematic source; private final Rotation rotation,inverse;
    public RotatedSchematic(ISchematic source,Rotation rotation){
        this.source=source;this.rotation=rotation;
        this.inverse=rotation.getRotated(rotation).getRotated(rotation);
    }
    private static boolean flips(Rotation r){return r==Rotation.CLOCKWISE_90||r==Rotation.COUNTERCLOCKWISE_90;}
    private static int rx(int x,int z,int sx,int sz,Rotation r){return switch(r){case NONE->x;case CLOCKWISE_90->sz-z-1;case CLOCKWISE_180->sx-x-1;case COUNTERCLOCKWISE_90->z;};}
    private static int rz(int x,int z,int sx,int sz,Rotation r){return switch(r){case NONE->z;case CLOCKWISE_90->x;case CLOCKWISE_180->sz-z-1;case COUNTERCLOCKWISE_90->sx-x-1;};}
    private static BlockState state(BlockState s,Rotation r){return s==null?null:s.rotate(r);}
    private static List<BlockState> states(List<BlockState>s,Rotation r){return s==null?null:s.stream().map(v->state(v,r)).toList();}
    public boolean inSchematic(int x,int y,int z,BlockState current){return source.inSchematic(rx(x,z,widthX(),lengthZ(),inverse),y,rz(x,z,widthX(),lengthZ(),inverse),state(current,inverse));}
    public BlockState desiredState(int x,int y,int z,BlockState current,List<BlockState> placeable){return state(source.desiredState(rx(x,z,widthX(),lengthZ(),inverse),y,rz(x,z,widthX(),lengthZ(),inverse),state(current,inverse),states(placeable,inverse)),rotation);}
    public void reset(){source.reset();} public int widthX(){return flips(rotation)?source.lengthZ():source.widthX();}
    public int heightY(){return source.heightY();} public int lengthZ(){return flips(rotation)?source.widthX():source.lengthZ();}
}
