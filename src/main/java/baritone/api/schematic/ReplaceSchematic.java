package baritone.api.schematic;
import baritone.api.utils.BlockOptionalMetaLookup;
import net.minecraft.world.level.block.state.BlockState;
public final class ReplaceSchematic extends MaskSchematic {
    private final BlockOptionalMetaLookup filter; private final Boolean[][][] cache;
    public ReplaceSchematic(ISchematic source,BlockOptionalMetaLookup filter){
        super(source);this.filter=filter;cache=new Boolean[widthX()][heightY()][lengthZ()];
    }
    @Override public void reset(){for(int x=0;x<widthX();x++)for(int y=0;y<heightY();y++)for(int z=0;z<lengthZ();z++)cache[x][y][z]=null;}
    protected boolean partOfMask(int x,int y,int z,BlockState current){
        if(cache[x][y][z]==null)cache[x][y][z]=filter.has(current);return cache[x][y][z];
    }
}
