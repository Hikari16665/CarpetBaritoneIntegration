package baritone.selection;
import baritone.Baritone;
import baritone.api.selection.ISelection;
import baritone.api.selection.ISelectionManager;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.Direction;
import java.util.LinkedList;
import java.util.ListIterator;
public final class SelectionManager implements ISelectionManager {
    private final LinkedList<ISelection> selections=new LinkedList<>();
    private volatile ISelection[] snapshot=new ISelection[0];
    public SelectionManager(Baritone ignored){}
    private void refresh(){snapshot=selections.toArray(ISelection[]::new);}
    public synchronized ISelection addSelection(ISelection s){selections.add(s);refresh();return s;}
    public ISelection addSelection(BetterBlockPos a,BetterBlockPos b){return addSelection(new Selection(a,b));}
    public synchronized ISelection removeSelection(ISelection s){selections.remove(s);refresh();return s;}
    public synchronized ISelection[] removeAllSelections(){ISelection[] old=getSelections();selections.clear();refresh();return old;}
    public ISelection[] getSelections(){return snapshot.clone();}
    public synchronized ISelection getOnlySelection(){return selections.size()==1?selections.peek():null;}
    public synchronized ISelection getLastSelection(){return selections.peekLast();}
    private synchronized ISelection replace(ISelection target,java.util.function.Function<ISelection,ISelection> op){
        for(ListIterator<ISelection> it=selections.listIterator();it.hasNext();){
            ISelection current=it.next();if(current==target){ISelection next=op.apply(current);it.set(next);refresh();return next;}
        }return null;
    }
    public ISelection expand(ISelection s,Direction d,int n){return replace(s,v->v.expand(d,n));}
    public ISelection contract(ISelection s,Direction d,int n){return replace(s,v->v.contract(d,n));}
    public ISelection shift(ISelection s,Direction d,int n){return replace(s,v->v.shift(d,n));}
}
