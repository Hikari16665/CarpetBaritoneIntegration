package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IGiveAllProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Consumer;

/** Walks to a player and Q-drops every inventory and equipment stack. */
public final class GiveAllProcess implements IGiveAllProcess {
    private static final EquipmentSlot[] EXTRA_SLOTS = {
            EquipmentSlot.OFFHAND,
            EquipmentSlot.FEET,
            EquipmentSlot.LEGS,
            EquipmentSlot.CHEST,
            EquipmentSlot.HEAD,
            EquipmentSlot.BODY
    };

    private final Baritone baritone;
    private UUID recipientId;
    private Consumer<String> feedback = ignored -> { };

    public GiveAllProcess(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void giveAll(ServerPlayer recipient, Consumer<String> feedback) {
        onLostControl();
        recipientId = recipient.getUUID();
        this.feedback = feedback == null ? ignored -> { } : feedback;
    }

    public void serverTick() {
        if (!isActive()) return;
        ServerPlayer recipient = recipient();
        ServerPlayer player = baritone.getPlayerContext().player();
        if (recipient == null) {
            feedback.accept("目标玩家已离线，giveAll 已停止");
            onLostControl();
            return;
        }
        if (recipient.level() != player.level()) {
            feedback.accept("目标玩家不在同一维度，无法交付");
            onLostControl();
            return;
        }
        if (player.distanceToSqr(recipient) > 9.0D) return;
        baritone.cancelPath();

        int stacks = 0;
        int items = 0;
        NonNullList<ItemStack> inventory =
                player.getInventory().items;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) continue;
            inventory.set(slot, ItemStack.EMPTY);
            items += stack.getCount();
            stacks++;
            dropToward(recipient, stack);
        }
        for (EquipmentSlot slot : EXTRA_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            player.setItemSlot(slot, ItemStack.EMPTY);
            items += stack.getCount();
            stacks++;
            dropToward(recipient, stack);
        }
        player.inventoryMenu.broadcastChanges();
        feedback.accept("已向 " + recipient.getScoreboardName()
                + " 丢出全部物品，共 " + stacks + " 组、"
                + items + " 件");
        onLostControl();
    }

    private void dropToward(ServerPlayer recipient, ItemStack stack) {
        ServerPlayer player = baritone.getPlayerContext().player();
        var entity = player.drop(stack, false);
        if (entity == null) return;
        var direction = recipient.getEyePosition()
                .subtract(player.getEyePosition()).normalize();
        entity.setDeltaMovement(direction.scale(0.35D)
                .add(0.0D, 0.15D, 0.0D));
        entity.setPickUpDelay(10);
    }

    private ServerPlayer recipient() {
        return recipientId == null ? null
                : baritone.getPlayerContext().server().getPlayerList()
                .getPlayer(recipientId);
    }

    public boolean isProtectedStack(ItemStack stack) {
        return isActive() && !stack.isEmpty();
    }

    @Override public boolean isActive() { return recipientId != null; }
    @Override public PathingCommand onTick(
            boolean calcFailed, boolean isSafeToCancel) {
        if (calcFailed) {
            feedback.accept("无法在不破坏或放置方块的情况下到达目标玩家");
            onLostControl();
            return new PathingCommand(
                    null, PathingCommandType.REQUEST_PAUSE);
        }
        ServerPlayer recipient = recipient();
        return new PathingCommand(
                recipient == null ? null
                        : new GoalNear(recipient.blockPosition(), 2),
                recipient == null ? PathingCommandType.REQUEST_PAUSE
                        : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    @Override public boolean isTemporary() { return false; }
    @Override public void onLostControl() {
        recipientId = null;
        feedback = ignored -> { };
    }
    @Override public String displayName0() {
        ServerPlayer recipient = recipient();
        return "Give all to " + (recipient == null
                ? recipientId : recipient.getScoreboardName());
    }
}
