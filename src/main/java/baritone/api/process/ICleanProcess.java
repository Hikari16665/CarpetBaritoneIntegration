package baritone.api.process;

import baritone.api.selection.ISelection;

import java.util.function.Consumer;

public interface ICleanProcess extends IBaritoneProcess {
    void clean(ISelection selection, Consumer<String> feedback);
}
