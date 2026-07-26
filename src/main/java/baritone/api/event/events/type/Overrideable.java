package baritone.api.event.events.type;
public class Overrideable<T> {
    private final T original;
    private T value;
    public Overrideable(T current) { original = current; value = current; }
    public T get() { return value; }
    public void set(T value) { this.value = value; }
    public boolean wasModified() { return value != original; }
    @Override public String toString() { return String.valueOf(value); }
}
