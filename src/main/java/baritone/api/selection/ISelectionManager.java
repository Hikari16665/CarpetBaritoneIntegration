package baritone.api.selection;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.Direction;
public interface ISelectionManager {
    ISelection addSelection(ISelection selection);
    ISelection addSelection(BetterBlockPos pos1,BetterBlockPos pos2);
    ISelection removeSelection(ISelection selection);
    ISelection[] removeAllSelections(); ISelection[] getSelections();
    ISelection getOnlySelection(); ISelection getLastSelection();
    ISelection expand(ISelection selection,Direction direction,int blocks);
    ISelection contract(ISelection selection,Direction direction,int blocks);
    ISelection shift(ISelection selection,Direction direction,int blocks);
}
