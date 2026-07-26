package baritone.api.cache;

import java.util.function.Consumer;

public interface IWorldProvider {
    IWorldData getCurrentWorld();

    default void ifWorldLoaded(Consumer<IWorldData> callback) {
        IWorldData data = getCurrentWorld();
        if (data != null) callback.accept(data);
    }
}
