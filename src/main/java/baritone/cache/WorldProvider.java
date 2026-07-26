package baritone.cache;

import baritone.Baritone;
import baritone.api.cache.IWorldData;
import baritone.api.cache.IWorldProvider;
import baritone.api.utils.IPlayerContext;

/** Dedicated-server replacement for the client connection world provider. */
public final class WorldProvider implements IWorldProvider {
    private final IPlayerContext context;
    private WorldData data;
    private Object worldIdentity;

    public WorldProvider(IPlayerContext context) {
        this.context = context;
    }

    public WorldProvider(Baritone baritone) {
        this(baritone.getPlayerContext());
    }

    @Override
    public WorldData getCurrentWorld() {
        Object current = context.world();
        if (current != worldIdentity) {
            worldIdentity = current;
            data = current == null ? null : new WorldData(context.world());
        }
        return data;
    }

    public void initWorld(net.minecraft.world.level.Level world) {
        if (!(world instanceof net.minecraft.server.level.ServerLevel)) {
            throw new IllegalArgumentException("ServerLevel required");
        }
        worldIdentity = null;
        getCurrentWorld();
    }

    public void closeWorld() {
        if (data != null) data.onClose();
        data = null;
        worldIdentity = null;
    }
}
