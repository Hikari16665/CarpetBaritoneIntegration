package baritone.selection;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
public final class Selection implements ISelection {
    private final BetterBlockPos pos1,pos2,min,max; private final Vec3i size; private final AABB aabb;
    public Selection(BetterBlockPos pos1,BetterBlockPos pos2){
        this.pos1=pos1;this.pos2=pos2;
        min=new BetterBlockPos(Math.min(pos1.x,pos2.x),Math.min(pos1.y,pos2.y),Math.min(pos1.z,pos2.z));
        max=new BetterBlockPos(Math.max(pos1.x,pos2.x),Math.max(pos1.y,pos2.y),Math.max(pos1.z,pos2.z));
        size=new Vec3i(max.x-min.x+1,max.y-min.y+1,max.z-min.z+1);
        aabb=new AABB(min.x,min.y,min.z,max.x+1,max.y+1,max.z+1);
    }
    public BetterBlockPos pos1(){return pos1;} public BetterBlockPos pos2(){return pos2;}
    public BetterBlockPos min(){return min;} public BetterBlockPos max(){return max;}
    public Vec3i size(){return size;} public AABB aabb(){return aabb;}
    private boolean isPos2(Direction direction){
        boolean negative=direction.getAxisDirection().getStep()<0;
        return switch(direction.getAxis()){
            case X -> (pos2.x>pos1.x)^negative;
            case Y -> (pos2.y>pos1.y)^negative;
            case Z -> (pos2.z>pos1.z)^negative;
        };
    }
    public ISelection expand(Direction d,int n){return isPos2(d)?new Selection(pos1,pos2.relative(d,n)):new Selection(pos1.relative(d,n),pos2);}
    public ISelection contract(Direction d,int n){return isPos2(d)?new Selection(pos1.relative(d,n),pos2):new Selection(pos1,pos2.relative(d,n));}
    public ISelection shift(Direction d,int n){return new Selection(pos1.relative(d,n),pos2.relative(d,n));}
    @Override public int hashCode(){return pos1.hashCode()^pos2.hashCode();}
    @Override public String toString(){return "Selection{pos1="+pos1+",pos2="+pos2+"}";}
}
