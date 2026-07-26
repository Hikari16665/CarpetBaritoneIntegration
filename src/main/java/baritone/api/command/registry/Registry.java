package baritone.api.command.registry;

import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.Spliterators;
import java.util.Spliterator;

public class Registry<V> implements Iterable<V> {
    private final Deque<V> mutable = new LinkedList<>();
    private final Set<V> registered = new HashSet<>();
    public final Collection<V> entries = Collections.unmodifiableCollection(mutable);
    public boolean registered(V entry) { return registered.contains(entry); }
    public boolean register(V entry) {
        if (!registered.add(entry)) return false;
        mutable.addFirst(entry);
        return true;
    }
    public void unregister(V entry) {
        if (registered.remove(entry)) mutable.remove(entry);
    }
    @Override public Iterator<V> iterator() { return mutable.iterator(); }
    public Iterator<V> descendingIterator() { return mutable.descendingIterator(); }
    public Stream<V> stream() { return mutable.stream(); }
    public Stream<V> descendingStream() {
        return StreamSupport.stream(Spliterators.spliterator(
                descendingIterator(), mutable.size(), Spliterator.SIZED), false);
    }
}
