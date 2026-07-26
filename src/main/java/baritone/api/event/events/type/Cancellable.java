package baritone.api.event.events.type;
public class Cancellable implements ICancellable {
    private boolean cancelled;
    @Override public final void cancel() { cancelled = true; }
    @Override public final boolean isCancelled() { return cancelled; }
}
