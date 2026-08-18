package baritone.server.llm;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.command.datatypes.ForWaypoints;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.api.pathing.goals.GoalNear;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.nuoyuan.carpetbaritoneintegration.compat.SyncmaticaBridge;
import baritone.utils.schematic.format.DefaultSchematicFormats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

/** Server-thread-only, read-only implementation of model observation tools. */
public final class LlmObservationTools {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmObservationTools() { }

    public static ObjectNode execute(
            String name, JsonNode arguments,
            ServerPlayer sender, ServerPlayer fake, Baritone baritone) {
        return switch (name) {
            case "get_context" -> context(sender, fake, baritone);
            case "list_capabilities" -> capabilities(baritone);
            case "get_action_help" -> actionHelp(
                    text(arguments, "action"), baritone);
            case "scan_blocks" -> scanBlocks(arguments, baritone);
            case "scan_storage_items" -> scanStorage(arguments, baritone);
            case "scan_dropped_items" -> scanDrops(arguments, baritone);
            case "inspect_inventory" -> inventory(fake);
            case "inspect_block" -> inspectBlock(arguments, baritone);
            case "scan_entities" -> scanEntities(arguments, baritone);
            case "get_waypoints" -> waypoints(baritone);
            case "list_players" -> players(fake);
            case "list_blueprints" -> blueprints(fake);
            case "probe_reachability" -> reachability(arguments, baritone);
            default -> throw new IllegalArgumentException(
                    "unknown observation tool: " + name);
        };
    }

    public static IncrementalObservation incremental(
            String name, JsonNode arguments,
            ServerPlayer sender, ServerPlayer fake, Baritone baritone) {
        if (name.equals("scan_storage_items")) {
            return new StorageObservation(arguments, baritone);
        }
        return new IncrementalObservation() {
            private ObjectNode result;
            @Override public boolean step(int budget) {
                if (result == null) result = execute(
                        name, arguments, sender, fake, baritone);
                return true;
            }
            @Override public ObjectNode result() { return result; }
        };
    }

    public interface IncrementalObservation {
        /** Performs no more than the supplied logical work units. */
        boolean step(int budget);
        ObjectNode result();
    }

    private static ObjectNode context(
            ServerPlayer sender, ServerPlayer fake, Baritone baritone) {
        ObjectNode root = ok();
        root.set("sender", player(sender));
        root.set("fake_player", player(fake));
        ObjectNode selection = root.putObject("selection");
        putPosition(selection, "pos1", baritone.getSelectionPos1());
        putPosition(selection, "pos2", baritone.getSelectionPos2());
        TaskLifecycleTracker.Snapshot lifecycle =
                baritone.getTaskLifecycleTracker().snapshot();
        if (lifecycle == null) root.putNull("running_task");
        else {
            ObjectNode task = root.putObject("running_task");
            task.put("plan_id", lifecycle.planId());
            task.put("task_id", lifecycle.taskId());
            task.put("status", lifecycle.status().name());
        }
        LlmPlanCoordinator.Snapshot plan =
                LlmPlanCoordinator.INSTANCE.snapshot(fake.getUUID());
        if (plan == null) root.putNull("active_plan");
        else {
            ObjectNode active = root.putObject("active_plan");
            active.put("plan_id", plan.planId());
            active.put("summary", plan.summary());
            ArrayNode statuses = active.putArray("tasks");
            plan.results().values().forEach(result -> statuses.addObject()
                    .put("id", result.taskId())
                    .put("status", result.status().name())
                    .put("summary", result.summary()));
        }
        root.put("pathing", baritone.isPathing());
        root.put("active_process", baritone.getPathingControlManager()
                .mostRecentInControl().map(process -> process.displayName())
                .orElse("none"));
        return root;
    }

    private static ObjectNode player(ServerPlayer player) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", player.getScoreboardName());
        node.put("dimension", player.level().dimension().identifier().toString());
        node.put("x", player.getX());
        node.put("y", player.getY());
        node.put("z", player.getZ());
        node.put("block_x", player.blockPosition().getX());
        node.put("block_y", player.blockPosition().getY());
        node.put("block_z", player.blockPosition().getZ());
        node.put("yaw", player.getYRot());
        node.put("pitch", player.getXRot());
        node.put("facing", player.getDirection().getName());
        Vec3 look = player.getLookAngle();
        ObjectNode vector = node.putObject("look_vector");
        vector.put("x", look.x); vector.put("y", look.y); vector.put("z", look.z);
        Vec3 velocity = player.getDeltaMovement();
        ObjectNode movement = node.putObject("velocity");
        movement.put("x", velocity.x); movement.put("y", velocity.y);
        movement.put("z", velocity.z);
        node.put("on_ground", player.onGround());
        node.put("fall_flying", player.isFallFlying());
        node.put("swimming", player.isSwimming());
        node.put("pose", player.getPose().name());
        node.put("health", player.getHealth());
        node.put("max_health", player.getMaxHealth());
        node.put("food", player.getFoodData().getFoodLevel());
        node.put("air", player.getAirSupply());
        node.put("game_mode", player.gameMode.getGameModeForPlayer().getName());
        node.put("main_hand", itemId(player.getMainHandItem()));
        node.put("off_hand", itemId(player.getOffhandItem()));
        ArrayNode armor = node.putArray("armor");
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            armor.addObject().put("slot", slot.getName())
                    .put("item", itemId(stack)).put("count", stack.getCount())
                    .put("damage", stack.getDamageValue());
        }
        int free = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) free++;
        }
        node.put("free_inventory_slots", free);
        ArrayNode effects = node.putArray("effects");
        player.getActiveEffects().forEach(effect -> effects.addObject()
                .put("id", BuiltInRegistries.MOB_EFFECT.getKey(
                        effect.getEffect().value()).toString())
                .put("amplifier", effect.getAmplifier())
                .put("duration", effect.getDuration()));
        return node;
    }

    private static ObjectNode capabilities(Baritone baritone) {
        ObjectNode root = ok();
        ArrayNode values = root.putArray("capabilities");
        LlmCapabilityRegistry.from(baritone).capabilities().stream()
                .limit(toolLimit()).forEach(capability ->
                values.addObject().put("name", capability.name())
                        .put("description", capability.description())
                        .put("completion_mode", capability.completionMode().name())
                        .put("high_impact", capability.highImpact())
                        .put("read_only", capability.readOnly()));
        return root;
    }

    private static ObjectNode actionHelp(String action, Baritone baritone) {
        LlmCapabilityRegistry.Capability capability =
                LlmCapabilityRegistry.from(baritone).describe(action);
        ObjectNode root = ok();
        root.put("name", capability.name());
        root.put("description", capability.description());
        root.put("completion_mode", capability.completionMode().name());
        root.put("high_impact", capability.highImpact());
        ArrayNode aliases = root.putArray("aliases");
        capability.aliases().forEach(aliases::add);
        return root;
    }

    private static ObjectNode scanBlocks(JsonNode arguments, Baritone baritone) {
        List<Block> blocks = blocks(arguments.path("block_ids"));
        Limits limits = limits(arguments);
        int chunks = Math.max(1, (limits.radius + 15) / 16);
        List<BlockPos> found = baritone.getWorldScanner().scanChunkRadius(
                baritone.getPlayerContext(),
                new BlockOptionalMetaLookup(blocks.toArray(Block[]::new)),
                limits.maximum * 4, -1, chunks);
        BlockPos origin = baritone.getPlayerContext().playerFeet();
        ObjectNode root = ok();
        ArrayNode results = root.putArray("results");
        found.stream().filter(pos -> pos.distSqr(origin)
                        <= (double) limits.radius * limits.radius)
                .sorted(Comparator.comparingDouble(origin::distSqr))
                .limit(limits.maximum).forEach(pos -> {
                    ObjectNode value = results.addObject();
                    position(value, pos);
                    value.put("distance", Math.sqrt(origin.distSqr(pos)));
                    value.put("loaded", baritone.getPlayerContext().world()
                            .hasChunkAt(pos));
                    value.put("source", baritone.getPlayerContext().world()
                            .hasChunkAt(pos) ? "loaded" : "cached");
                    if (baritone.getPlayerContext().world().hasChunkAt(pos)) {
                        value.put("block", BuiltInRegistries.BLOCK.getKey(
                                baritone.getPlayerContext().world()
                                        .getBlockState(pos).getBlock()).toString());
                    }
                });
        root.put("count", results.size());
        return root;
    }

    private static ObjectNode scanStorage(JsonNode arguments, Baritone baritone) {
        StorageObservation scan = new StorageObservation(arguments, baritone);
        while (!scan.step(64)) { /* synchronous compatibility path */ }
        return scan.result();
    }

    private static ObjectNode scanDrops(JsonNode arguments, Baritone baritone) {
        Set<Item> requested = new LinkedHashSet<>(items(arguments.path("item_ids")));
        Limits limits = limits(arguments);
        ServerPlayer player = baritone.getPlayerContext().player();
        ObjectNode root = ok();
        ArrayNode results = root.putArray("results");
        baritone.getPlayerContext().entitiesStream()
                .filter(entity -> entity instanceof ItemEntity)
                .map(entity -> (ItemEntity) entity)
                .filter(entity -> entity.isAlive()
                        && requested.contains(entity.getItem().getItem())
                        && player.distanceToSqr(entity)
                        <= (double) limits.radius * limits.radius)
                .sorted(Comparator.comparingDouble(player::distanceToSqr))
                .limit(limits.maximum).forEach(entity -> {
                    ObjectNode value = results.addObject();
                    position(value, entity.blockPosition());
                    value.put("item", itemId(entity.getItem()));
                    value.put("count", entity.getItem().getCount());
                    value.put("distance", Math.sqrt(player.distanceToSqr(entity)));
                });
        root.put("count", results.size());
        return root;
    }

    private static ObjectNode inventory(ServerPlayer fake) {
        ObjectNode root = ok();
        Map<Item, Integer> counts = new LinkedHashMap<>();
        Map<Item, Integer> boxed = new LinkedHashMap<>();
        for (ItemStack stack : fake.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) continue;
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            shulkerCounts(stack).forEach((item, count) ->
                    boxed.merge(item, count, Integer::sum));
        }
        ObjectNode direct = root.putObject("direct_items");
        counts.entrySet().stream().limit(toolLimit()).forEach(entry -> direct.put(
                BuiltInRegistries.ITEM.getKey(entry.getKey()).toString(), entry.getValue()));
        ObjectNode contained = root.putObject("shulker_contents");
        boxed.entrySet().stream().limit(toolLimit()).forEach(entry -> contained.put(
                BuiltInRegistries.ITEM.getKey(entry.getKey()).toString(), entry.getValue()));
        return root;
    }

    private static ObjectNode inspectBlock(JsonNode arguments, Baritone baritone) {
        BlockPos pos = blockPosition(arguments);
        ObjectNode root = ok();
        position(root, pos);
        boolean loaded = baritone.getPlayerContext().world().hasChunkAt(pos);
        root.put("loaded", loaded);
        if (!loaded) return root;
        var state = baritone.getPlayerContext().world().getBlockState(pos);
        root.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        root.put("state", state.toString());
        root.put("fluid", state.getFluidState().isEmpty() ? "empty"
                : BuiltInRegistries.FLUID.getKey(
                        state.getFluidState().getType()).toString());
        BlockEntity entity = baritone.getPlayerContext().world().getBlockEntity(pos);
        root.put("block_entity", entity == null ? "none"
                : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString());
        root.put("within_interaction_reach", baritone
                .getFakeInteractionController().canReach(pos));
        root.put("line_of_sight", baritone.getFakeInteractionController()
                .canBreakFromHere(pos));
        root.put("breakable", state.getDestroySpeed(
                baritone.getPlayerContext().world(), pos) >= 0.0F);
        return root;
    }

    private static ObjectNode scanEntities(JsonNode arguments, Baritone baritone) {
        Limits limits = limits(arguments);
        ServerPlayer player = baritone.getPlayerContext().player();
        ObjectNode root = ok();
        ArrayNode results = root.putArray("results");
        baritone.getPlayerContext().entitiesStream()
                .filter(Entity::isAlive)
                .filter(entity -> entity != player)
                .filter(entity -> player.distanceToSqr(entity)
                        <= (double) limits.radius * limits.radius)
                .sorted(Comparator.comparingDouble(player::distanceToSqr))
                .limit(limits.maximum).forEach(entity -> {
                    ObjectNode value = results.addObject();
                    value.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(
                            entity.getType()).toString());
                    value.put("name", entity.getName().getString());
                    position(value, entity.blockPosition());
                    value.put("distance", Math.sqrt(player.distanceToSqr(entity)));
                    value.put("hostile", entity instanceof Enemy);
                    if (entity instanceof LivingEntity living) {
                        value.put("health", living.getHealth());
                    }
                });
        root.put("count", results.size());
        return root;
    }

    private static ObjectNode waypoints(Baritone baritone) {
        ObjectNode root = ok();
        ArrayNode results = root.putArray("results");
        int maximum = toolLimit();
        int count = 0;
        for (var waypoint : ForWaypoints.getWaypoints(baritone)) {
            if (count++ >= maximum) break;
            ObjectNode value = results.addObject();
            value.put("name", waypoint.getName());
            value.put("tag", waypoint.getTag().getName());
            position(value, waypoint.getLocation());
        }
        return root;
    }

    private static ObjectNode players(ServerPlayer fake) {
        ObjectNode root = ok();
        ArrayNode results = root.putArray("results");
        fake.level().getServer().getPlayerList().getPlayers().stream()
                .sorted(Comparator.comparing(ServerPlayer::getScoreboardName,
                        String.CASE_INSENSITIVE_ORDER))
                .limit(toolLimit())
                .forEach(player -> {
                    ObjectNode value = results.addObject();
                    value.put("name", player.getScoreboardName());
                    value.put("fake", player instanceof carpet.patches.EntityPlayerMPFake);
                    value.put("dimension", player.level().dimension()
                            .identifier().toString());
                    position(value, player.blockPosition());
                });
        return root;
    }

    private static ObjectNode blueprints(ServerPlayer fake) {
        ObjectNode root = ok();
        ArrayNode local = root.putArray("server_schematics");
        Path schematicRoot = Path.of("schematics").toAbsolutePath().normalize();
        if (Files.isDirectory(schematicRoot)) {
            try (var paths = Files.walk(schematicRoot, 8)) {
                final int[] index = {1};
                paths.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(file -> DefaultSchematicFormats.detect(file) != null)
                        .sorted(Comparator.comparing(java.io.File::getPath,
                                String.CASE_INSENSITIVE_ORDER))
                        .limit(Math.max(1, Baritone.settings()
                                .llmToolResultLimit.value))
                        .forEach(file -> local.addObject()
                                .put("index", index[0]++)
                                .put("name", file.getName())
                                .put("relative_path", schematicRoot.relativize(
                                        file.toPath().toAbsolutePath().normalize())
                                        .toString()));
            } catch (IOException exception) {
                root.put("server_schematics_error", exception.getMessage());
            }
        }
        ArrayNode results = root.putArray("syncmatica");
        SyncmaticaBridge.list(fake.level().getServer()).stream()
                .limit(toolLimit()).forEach(blueprint -> {
            ObjectNode value = results.addObject();
            value.put("id", blueprint.id().toString());
            value.put("name", blueprint.name());
            value.put("dimension", blueprint.dimension());
            position(value, blueprint.origin());
        });
        return root;
    }

    private static ObjectNode reachability(JsonNode arguments, Baritone baritone) {
        BlockPos pos = blockPosition(arguments);
        ObjectNode root = inspectBlock(arguments, baritone);
        BetterBlockPos feet = baritone.getPlayerContext().playerFeet();
        root.put("distance", Math.sqrt(feet.distSqr(pos)));
        root.put("already_at_target", feet.distSqr(pos) <= 2.0D);
        root.put("path_calculation_running", baritone.isPathing());
        root.put("cached_chunk", baritone.getWorldCache().isCached(
                pos.getX() >> 4, pos.getZ() >> 4));
        GoalNear goal = new GoalNear(pos, 2);
        CalculationContext context = new CalculationContext(
                baritone, true, goal);
        AStarPathFinder finder = new AStarPathFinder(feet,
                feet.x, feet.y, feet.z, goal,
                new Favoring(baritone.getPlayerContext(), null, context), context);
        var calculation = finder.calculate(4L, 8L);
        root.put("calculation_type", calculation.getType().name());
        root.put("reachable", calculation.getType()
                == PathCalculationResult.Type.SUCCESS_TO_GOAL);
        calculation.getPath().ifPresent(path -> {
            root.put("path_nodes", path.positions().size());
            BetterBlockPos destination = path.getDest();
            ObjectNode reached = root.putObject("probe_destination");
            position(reached, destination);
        });
        return root;
    }

    private static Limits limits(JsonNode arguments) {
        Settings settings = Baritone.settings();
        int radius = Math.max(1, Math.min(
                settings.llmObservationMaxDistance.value,
                arguments.path("radius").asInt(32)));
        int maximum = Math.max(1, Math.min(
                settings.llmToolResultLimit.value,
                arguments.path("max_results").asInt(32)));
        return new Limits(radius, maximum);
    }

    private static int toolLimit() {
        return Math.max(1, Baritone.settings().llmToolResultLimit.value);
    }

    private static List<Block> blocks(JsonNode values) {
        List<Block> result = new ArrayList<>();
        if (!values.isArray()) throw new IllegalArgumentException(
                "block_ids must be an array");
        values.forEach(value -> {
            Identifier id = resource(value.asText());
            if (!BuiltInRegistries.BLOCK.containsKey(id)) {
                throw new IllegalArgumentException("unknown block " + id);
            }
            result.add(BuiltInRegistries.BLOCK.getValue(id));
        });
        if (result.isEmpty()) throw new IllegalArgumentException(
                "at least one block id is required");
        return result;
    }

    private static List<Item> items(JsonNode values) {
        List<Item> result = new ArrayList<>();
        if (!values.isArray()) throw new IllegalArgumentException(
                "item_ids must be an array");
        values.forEach(value -> {
            Identifier id = resource(value.asText());
            if (!BuiltInRegistries.ITEM.containsKey(id)) {
                throw new IllegalArgumentException("unknown item " + id);
            }
            result.add(BuiltInRegistries.ITEM.getValue(id));
        });
        if (result.isEmpty()) throw new IllegalArgumentException(
                "at least one item id is required");
        return result;
    }

    private static Identifier resource(String value) {
        String normalized = value.contains(":") ? value : "minecraft:" + value;
        Identifier id = Identifier.tryParse(normalized);
        if (id == null) throw new IllegalArgumentException(
                "invalid resource id " + value);
        return id;
    }

    private static String text(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.asText();
    }

    private static BlockPos blockPosition(JsonNode arguments) {
        return new BlockPos(arguments.path("x").asInt(),
                arguments.path("y").asInt(), arguments.path("z").asInt());
    }

    private static Container supportedContainer(BlockEntity entity) {
        if (!(entity instanceof Container container)) return null;
        Block block = entity.getBlockState().getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock ? container : null;
    }

    private static Map<Item, Integer> containerCounts(
            Container container, Set<Item> requested) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (requested.contains(stack.getItem())) {
                result.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
            shulkerCounts(stack).forEach((item, count) -> {
                if (requested.contains(item)) result.merge(item, count, Integer::sum);
            });
        }
        return result;
    }

    private static Map<Item, Integer> shulkerCounts(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) return Map.of();
        Map<Item, Integer> result = new LinkedHashMap<>();
        ItemContainerContents contents = stack.getOrDefault(
                DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        contents.nonEmptyStream().forEach(inner -> result.merge(
                inner.getItem(), inner.getCount(), Integer::sum));
        return result;
    }

    private static void putPosition(
            ObjectNode parent, String name, BlockPos position) {
        if (position == null) parent.putNull(name);
        else position(parent.putObject(name), position);
    }

    private static void position(ObjectNode target, BlockPos position) {
        target.put("x", position.getX());
        target.put("y", position.getY());
        target.put("z", position.getZ());
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static ObjectNode ok() {
        return MAPPER.createObjectNode().put("ok", true);
    }

    private static final class StorageObservation
            implements IncrementalObservation {
        private final Baritone baritone;
        private final Set<Item> requested;
        private final Limits limits;
        private final BlockPos origin;
        private final int centerX;
        private final int centerZ;
        private final List<ChunkOffset> chunks = new ArrayList<>();
        private final List<ContainerResult> found = new ArrayList<>();
        private int cursor;
        private ObjectNode result;

        private StorageObservation(JsonNode arguments, Baritone baritone) {
            this.baritone = baritone;
            requested = new LinkedHashSet<>(items(arguments.path("item_ids")));
            limits = limits(arguments);
            origin = baritone.getPlayerContext().playerFeet();
            centerX = origin.getX() >> 4;
            centerZ = origin.getZ() >> 4;
            int chunkRadius = (limits.radius + 15) / 16;
            for (int distance = 0; distance <= chunkRadius; distance++) {
                for (int dx = -distance; dx <= distance; dx++) {
                    for (int dz = -distance; dz <= distance; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) == distance) {
                            chunks.add(new ChunkOffset(dx, dz));
                        }
                    }
                }
            }
        }

        @Override
        public boolean step(int budget) {
            int remaining = Math.max(1, budget);
            while (remaining-- > 0 && cursor < chunks.size()) {
                ChunkOffset offset = chunks.get(cursor++);
                LevelChunk chunk = baritone.getPlayerContext().world()
                        .getChunkSource().getChunkNow(
                                centerX + offset.x, centerZ + offset.z);
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, BlockEntity> entry
                        : chunk.getBlockEntities().entrySet()) {
                    if (origin.distSqr(entry.getKey())
                            > (double) limits.radius * limits.radius) continue;
                    Container container = supportedContainer(entry.getValue());
                    if (container == null) continue;
                    Map<Item, Integer> counts = containerCounts(container, requested);
                    if (!counts.isEmpty()) found.add(new ContainerResult(
                            entry.getKey().immutable(), counts));
                }
            }
            if (cursor >= chunks.size() && result == null) result = buildResult();
            return result != null;
        }

        @Override public ObjectNode result() { return result; }

        private ObjectNode buildResult() {
            found.sort(Comparator.comparingDouble(value ->
                    origin.distSqr(value.position)));
            ObjectNode root = ok();
            ArrayNode results = root.putArray("results");
            found.stream().limit(limits.maximum).forEach(container -> {
                ObjectNode value = results.addObject();
                position(value, container.position);
                value.put("distance", Math.sqrt(
                        origin.distSqr(container.position)));
                ObjectNode counts = value.putObject("items");
                container.counts.forEach((item, count) -> counts.put(
                        BuiltInRegistries.ITEM.getKey(item).toString(), count));
            });
            root.put("count", results.size());
            root.put("chunks_examined", chunks.size());
            return root;
        }
    }

    private record Limits(int radius, int maximum) { }
    private record ChunkOffset(int x, int z) { }
    private record ContainerResult(BlockPos position, Map<Item, Integer> counts) { }
}
