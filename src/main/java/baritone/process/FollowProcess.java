package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IFollowProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Server adaptation of Baritone's original FollowProcess. */
public final class FollowProcess implements IFollowProcess {
    private final Baritone baritone;
    private Predicate<Entity> filter;
    private List<Entity> cache;
    private boolean into;
    private Goal lastGoal;
    private int ticks;

    public FollowProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    public void serverTick() {
        // PathingControlManager already consumes onTick and submits the
        // asynchronous calculation. A second calculation here would be both
        // redundant and a server-thread stall.
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        scanWorld();
        Goal goal = new GoalComposite(cache.stream().map(this::towards).toArray(Goal[]::new));
        lastGoal = goal;
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private Goal towards(Entity following) {
        BlockPos pos;
        if (Baritone.settings().followOffsetDistance.value == 0 || into) {
            pos = following.blockPosition();
        } else {
            GoalXZ offset = GoalXZ.fromDirection(
                    following.position(),
                    Baritone.settings().followOffsetDirection.value,
                    Baritone.settings().followOffsetDistance.value);
            pos = new BetterBlockPos(offset.getX(), following.position().y, offset.getZ());
        }
        return into ? new GoalBlock(pos) : new GoalNear(pos, Baritone.settings().followRadius.value);
    }

    private void scanWorld() {
        if (filter == null) {
            cache = Collections.emptyList();
            return;
        }
        int maxDistance = Baritone.settings().followTargetMaxDistance.value;
        cache = baritone.getPlayerContext().entitiesStream()
                .filter(entity -> entity.isAlive() && entity != baritone.getPlayerContext().player())
                .filter(entity -> maxDistance == 0
                        || entity.distanceToSqr(baritone.getPlayerContext().player())
                        <= maxDistance * maxDistance)
                .filter(filter)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override public boolean isActive() { scanWorld(); return filter != null && !cache.isEmpty(); }
    @Override public boolean isTemporary() { return true; }
    @Override public void onLostControl() { filter = null; cache = null; lastGoal = null; }
    @Override public String displayName0() { return "Following " + cache; }
    @Override public void follow(Predicate<Entity> filter) { this.filter = filter; this.into = false; }
    @Override public void pickup(Predicate<ItemStack> filter) {
        this.filter = entity -> entity instanceof ItemEntity item && filter.test(item.getItem());
        this.into = true;
    }
    @Override public List<Entity> following() { return cache; }
    @Override public Predicate<Entity> currentFilter() { return filter; }
}
