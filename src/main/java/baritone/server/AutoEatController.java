package baritone.server;

import baritone.Baritone;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Survival-style food selection that continues eating until hunger is full. */
public final class AutoEatController {
    private final Baritone baritone;
    private boolean eatingUntilFull;

    public AutoEatController(Baritone baritone) {
        this.baritone = baritone;
    }

    public void tick(boolean safeToEat) {
        ServerPlayer player = baritone.getPlayerContext().player();
        if (!Baritone.settings().autoEat.value || !safeToEat
                || player.isFallFlying()) {
            stop(player);
            return;
        }
        int food = player.getFoodData().getFoodLevel();
        if (food >= 20) {
            stop(player);
            return;
        }
        if (!eatingUntilFull && !shouldStart(food, player.getHealth(),
                player.getMaxHealth(),
                Baritone.settings().autoEatHungerThreshold.value,
                Baritone.settings().autoEatWhenHealthMissing.value)) {
            return;
        }
        eatingUntilFull = true;
        ItemStack current = player.getMainHandItem();
        if (!isAllowedFood(current)) {
            int missing = 20 - food;
            if (!selectBestFood(missing)) {
                eatingUntilFull = false;
                return;
            }
            current = player.getMainHandItem();
        }
        if (!player.isUsingItem()
                || player.getUseItem() != current) {
            player.startUsingItem(InteractionHand.MAIN_HAND);
        }
    }

    public boolean isEating() {
        return eatingUntilFull;
    }

    private boolean selectBestFood(int missingHunger) {
        ItemStack best = baritone.getPlayerContext().player().getInventory()
                .getNonEquipmentItems().stream()
                .filter(this::isAllowedFood)
                .max(java.util.Comparator.comparingDouble(
                        stack -> foodScore(stack, missingHunger)))
                .orElse(ItemStack.EMPTY);
        if (best.isEmpty()) return false;
        var item = best.getItem();
        return baritone.getInventoryController().selectItemForBuilder(
                stack -> stack.is(item) && isAllowedFood(stack));
    }

    private boolean isAllowedFood(ItemStack stack) {
        if (stack.isEmpty() || !stack.has(DataComponents.FOOD)
                || !stack.has(DataComponents.CONSUMABLE)) return false;
        if (Baritone.settings().autoEatAvoidValuableFoods.value
                && (stack.is(Items.GOLDEN_APPLE)
                || stack.is(Items.ENCHANTED_GOLDEN_APPLE))) return false;
        return !Baritone.settings().autoEatAvoidHarmfulFoods.value
                || !(stack.is(Items.PUFFERFISH)
                || stack.is(Items.SPIDER_EYE)
                || stack.is(Items.ROTTEN_FLESH)
                || stack.is(Items.POISONOUS_POTATO)
                || stack.is(Items.CHICKEN));
    }

    private static double foodScore(ItemStack stack, int missingHunger) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) return Double.NEGATIVE_INFINITY;
        int useful = Math.min(missingHunger, food.nutrition());
        int wasted = Math.max(0, food.nutrition() - missingHunger);
        return useful * 10.0D + food.saturation() * 2.0D - wasted * 3.0D;
    }

    static boolean shouldStart(
            int food, float health, float maxHealth, int threshold,
            boolean eatWhenHurt) {
        if (food >= 20) return false;
        return food < Math.max(0, Math.min(20, threshold))
                || eatWhenHurt && health < maxHealth;
    }

    private void stop(ServerPlayer player) {
        if (eatingUntilFull && player.isUsingItem()) {
            player.stopUsingItem();
        }
        eatingUntilFull = false;
    }
}
