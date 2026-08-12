package baritone.server;

import baritone.Baritone;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Public, configurable and edge-triggered fake-player status messages. */
public final class FakePlayerStatusMessenger {
    private static final int MAX_MESSAGE_LENGTH = 256;

    private final Baritone baritone;
    private final AutoEatController autoEat;
    private final Map<Event, Long> lastSent = new EnumMap<>(Event.class);
    private EmergencyAvoidanceController.Threat previousThreat =
            EmergencyAvoidanceController.Threat.NONE;
    private boolean wasLowFood;
    private boolean wasHurtHungry;
    private boolean wasOutOfFood;
    private boolean wasFinalPathFailure;

    public FakePlayerStatusMessenger(
            Baritone baritone, AutoEatController autoEat) {
        this.baritone = baritone;
        this.autoEat = autoEat;
    }

    public void tick(EmergencyAvoidanceController.Threat threat) {
        ServerPlayer player = baritone.getPlayerContext().player();
        if (!Baritone.settings().fakePlayerPublicMessages.value) {
            resetEdges();
            return;
        }

        int food = player.getFoodData().getFoodLevel();
        int threshold = Math.max(0, Math.min(20,
                Baritone.settings().autoEatHungerThreshold.value));
        boolean lowFood = food < threshold;
        boolean hurtHungry = player.getHealth() < player.getMaxHealth()
                && food < 20 && !lowFood;
        boolean needsFood = lowFood || hurtHungry;
        boolean outOfFood = needsFood && !autoEat.hasAvailableFood();
        boolean finalPathFailure = baritone.getConsecutivePathFailures() > 0
                && baritone.getActiveGoal() == null;

        Event event = transition(previousThreat, threat,
                wasLowFood, lowFood, wasHurtHungry, hurtHungry,
                wasOutOfFood, outOfFood,
                wasFinalPathFailure, finalPathFailure);

        previousThreat = threat;
        wasLowFood = lowFood;
        wasHurtHungry = hurtHungry;
        wasOutOfFood = outOfFood;
        wasFinalPathFailure = finalPathFailure;
        if (event != null) announce(event, player, food, threat);
    }

    static Event transition(
            EmergencyAvoidanceController.Threat previousThreat,
            EmergencyAvoidanceController.Threat threat,
            boolean wasLowFood, boolean lowFood,
            boolean wasHurtHungry, boolean hurtHungry,
            boolean wasOutOfFood, boolean outOfFood,
            boolean wasFinalPathFailure, boolean finalPathFailure) {
        if (threat != EmergencyAvoidanceController.Threat.NONE
                && threat != previousThreat) {
            return switch (threat) {
                case TNT -> Event.TNT;
                case CREEPER -> Event.CREEPER;
                case HOSTILE_COMBAT -> Event.COMBAT;
                case HOSTILE_FLEE -> Event.FLEE;
                case NONE -> null;
            };
        }
        if (outOfFood && !wasOutOfFood) return Event.NO_FOOD;
        if (lowFood && !wasLowFood) return Event.LOW_FOOD;
        if (hurtHungry && !wasHurtHungry) return Event.HURT_FOOD;
        if (finalPathFailure && !wasFinalPathFailure) {
            return Event.PATH_FAILURE;
        }
        return null;
    }

    private void announce(
            Event event, ServerPlayer player, int food,
            EmergencyAvoidanceController.Threat threat) {
        long now = player.level().getGameTime();
        long cooldown = Math.max(0,
                Baritone.settings().fakePlayerMessageCooldownTicks.value);
        long previous = lastSent.getOrDefault(event, Long.MIN_VALUE / 2);
        if (now - previous < cooldown) return;
        String message = templateFor(event);
        if (message == null || message.isBlank()) return;
        message = renderTemplate(message, player.getScoreboardName(), food,
                player.getHealth(), player.getMaxHealth(), threat);
        if (message.isBlank()) return;
        lastSent.put(event, now);
        MinecraftServer server = baritone.getPlayerContext().server();
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(
                    "<" + player.getScoreboardName() + "> " + message), false);
        }
    }

    private static String renderTemplate(
            String template, String player, int food, float health,
            float maxHealth, EmergencyAvoidanceController.Threat threat) {
        String rendered = template
                .replace("{player}", player)
                .replace("{food}", Integer.toString(food))
                .replace("{health}", oneDecimal(health))
                .replace("{max_health}", oneDecimal(maxHealth))
                .replace("{threat}", threat.name().toLowerCase(Locale.ROOT))
                .replace('\r', ' ').replace('\n', ' ').trim();
        return rendered.length() <= MAX_MESSAGE_LENGTH ? rendered
                : rendered.substring(0, MAX_MESSAGE_LENGTH);
    }

    private static String oneDecimal(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String templateFor(Event event) {
        return switch (event) {
            case LOW_FOOD -> Baritone.settings().fakePlayerLowFoodMessage.value;
            case NO_FOOD -> Baritone.settings().fakePlayerNoFoodMessage.value;
            case HURT_FOOD -> Baritone.settings().fakePlayerHurtFoodMessage.value;
            case TNT -> Baritone.settings().fakePlayerTntMessage.value;
            case CREEPER -> Baritone.settings().fakePlayerCreeperMessage.value;
            case COMBAT -> Baritone.settings().fakePlayerCombatMessage.value;
            case FLEE -> Baritone.settings().fakePlayerFleeMessage.value;
            case PATH_FAILURE -> Baritone.settings()
                    .fakePlayerPathFailureMessage.value;
        };
    }

    private void resetEdges() {
        previousThreat = EmergencyAvoidanceController.Threat.NONE;
        wasLowFood = false;
        wasHurtHungry = false;
        wasOutOfFood = false;
        wasFinalPathFailure = false;
    }

    enum Event {
        LOW_FOOD, NO_FOOD, HURT_FOOD, TNT, CREEPER, COMBAT, FLEE,
        PATH_FAILURE
    }
}
