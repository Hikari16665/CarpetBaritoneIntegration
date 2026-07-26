package baritone.behavior;

import baritone.Baritone;
import baritone.api.cache.IWaypoint;
import baritone.api.cache.Waypoint;
import baritone.api.event.events.BlockInteractEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

public final class WaypointBehavior extends Behavior implements AbstractGameEventListener {
    public WaypointBehavior(Baritone baritone) { super(baritone); }
    @Override public void onBlockInteract(BlockInteractEvent event) {
        if (!Baritone.settings().doBedWaypoints.value
                || event.getType() != BlockInteractEvent.Type.USE) return;
        BetterBlockPos position = BetterBlockPos.from(event.getPos());
        BlockState state = ctx.world().getBlockState(position);
        if (!(state.getBlock() instanceof BedBlock)) return;
        if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
            position = position.relative(state.getValue(BedBlock.FACING));
        }
        BetterBlockPos finalPosition = position;
        boolean exists = baritone.getWorldProvider().getCurrentWorld().getWaypoints()
                .getByTag(IWaypoint.Tag.BED).stream()
                .anyMatch(waypoint -> waypoint.getLocation().equals(finalPosition));
        if (!exists) {
            baritone.getWorldProvider().getCurrentWorld().getWaypoints()
                    .addWaypoint(new Waypoint("bed", IWaypoint.Tag.BED, position));
        }
    }
    @Override public void onPlayerDeath() {
        if (Baritone.settings().doDeathWaypoints.value) {
            baritone.getWorldProvider().getCurrentWorld().getWaypoints()
                    .addWaypoint(new Waypoint("death", IWaypoint.Tag.DEATH, ctx.playerFeet()));
        }
    }
}
