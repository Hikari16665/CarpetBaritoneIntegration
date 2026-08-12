package baritone.server;

import baritone.Baritone;
import baritone.api.utils.Rotation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Immediate input-layer escape for explosive threats; it preserves tasks. */
public final class EmergencyAvoidanceController {
    private final Baritone baritone;
    private final CarpetInputController input;
    private Threat activeThreat = Threat.NONE;

    public EmergencyAvoidanceController(Baritone baritone) {
        this.baritone = baritone;
        this.input = baritone.getInputController();
    }

    public void tick() {
        if (!Baritone.settings().emergencyAvoidance.value) {
            clear();
            return;
        }
        ServerPlayer player = baritone.getPlayerContext().player();
        double tntRadius = Math.max(1.0D,
                Baritone.settings().tntAvoidanceRadius.value);
        double creeperRadius = Math.max(1.0D,
                Baritone.settings().creeperAvoidanceRadius.value);
        double radius = Math.max(tntRadius, creeperRadius);
        AABB scan = player.getBoundingBox().inflate(radius, radius, radius);
        Vec3 origin = player.position();
        Vec3 escape = Vec3.ZERO;
        Threat strongest = Threat.NONE;
        double strongestScore = 0.0D;

        List<PrimedTnt> tnts = player.level().getEntitiesOfClass(
                PrimedTnt.class, scan, entity -> entity.isAlive());
        int predictionLimit = Math.max(0,
                Baritone.settings().tntPredictionTicks.value);
        for (PrimedTnt tnt : tnts) {
            Vec3 predicted = closestApproach(
                    origin, tnt.position(), tnt.getDeltaMovement(),
                    Math.min(predictionLimit, Math.max(0, tnt.getFuse())));
            Vec3 away = horizontalAway(origin, predicted);
            double distance = Math.sqrt(Math.max(0.01D,
                    origin.distanceToSqr(predicted)));
            if (distance > tntRadius) continue;
            double approach = Math.max(0.0D,
                    tnt.getDeltaMovement().dot(origin.subtract(tnt.position())));
            double score = (tntRadius - distance + 1.0D)
                    * (2.0D + approach * 8.0D)
                    * (1.0D + Math.max(0, 20 - tnt.getFuse()) / 10.0D);
            escape = escape.add(away.scale(score));
            if (score > strongestScore) {
                strongestScore = score;
                strongest = Threat.TNT;
            }
        }

        List<Creeper> creepers = player.level().getEntitiesOfClass(
                Creeper.class, scan, entity -> entity.isAlive());
        double critical = Math.max(1.0D,
                Baritone.settings().creeperCriticalRadius.value);
        for (Creeper creeper : creepers) {
            double distance = Math.sqrt(player.distanceToSqr(creeper));
            if (distance > creeperRadius) continue;
            boolean swelling = creeper.getSwelling(0.0F) > 0.0F;
            if (!swelling && distance > critical) continue;
            double score = (creeperRadius - distance + 1.0D)
                    * (swelling ? 5.0D : 2.0D);
            escape = escape.add(horizontalAway(origin, creeper.position())
                    .scale(score));
            if (score > strongestScore) {
                strongestScore = score;
                strongest = Threat.CREEPER;
            }
        }

        if (strongest == Threat.NONE || escape.lengthSqr() < 0.0001D) {
            clear();
            return;
        }
        Vec3 direction = escape.normalize();
        float yaw = (float) Math.toDegrees(
                Math.atan2(-direction.x, direction.z));
        boolean jump = Baritone.settings().emergencyAvoidanceJump.value
                && (player.horizontalCollision || player.onGround());
        input.setEmergencyMovement(new Rotation(yaw, 0.0F), true, jump);
        activeThreat = strongest;
    }

    public Threat activeThreat() {
        return activeThreat;
    }

    private void clear() {
        input.clearEmergencyMovement();
        activeThreat = Threat.NONE;
    }

    private static Vec3 horizontalAway(Vec3 origin, Vec3 threat) {
        Vec3 result = new Vec3(origin.x - threat.x, 0.0D,
                origin.z - threat.z);
        if (result.lengthSqr() < 0.0001D) {
            return new Vec3(1.0D, 0.0D, 0.0D);
        }
        return result.normalize();
    }

    static Vec3 closestApproach(
            Vec3 observer, Vec3 position, Vec3 velocity, int horizon) {
        double horizontalSpeed = velocity.x * velocity.x
                + velocity.z * velocity.z;
        if (horizontalSpeed < 0.000001D || horizon <= 0) return position;
        Vec3 relative = position.subtract(observer);
        double ticks = -(relative.x * velocity.x
                + relative.z * velocity.z) / horizontalSpeed;
        ticks = Math.max(0.0D, Math.min(horizon, ticks));
        return position.add(velocity.scale(ticks));
    }

    public enum Threat { NONE, TNT, CREEPER }
}
