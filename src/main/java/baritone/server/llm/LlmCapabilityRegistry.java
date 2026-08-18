package baritone.server.llm;

import baritone.Baritone;
import baritone.api.command.ICommand;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import me.nuoyuan.carpetbaritoneintegration.compat.SyncmaticaBridge;

import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Model-visible capabilities derived from the commands actually registered on
 * a Baritone instance. Security metadata remains server-owned.
 */
public final class LlmCapabilityRegistry {
    private static final Set<String> EXCLUDED = Set.of(
            "gc", "reloadall", "saveall", "repack", "cache");
    private static final Set<String> CONTINUOUS = Set.of(
            "follow", "farm", "areamine", "explore", "backfill");
    private static final Set<String> FINITE = Set.of(
            "goto", "come", "y", "mine", "collectitem", "collect_item",
            "collect", "giveall", "give_all", "break", "place", "get",
            "getto", "get_to_block", "build", "schematica", "litematica",
            "elytra", "fly", "runaway", "run_away", "path", "surface",
            "top", "thisway", "forward", "axis", "highway", "tunnel",
            "home", "pickup", "clean");
    private static final Set<String> HIGH_IMPACT = Set.of(
            "clean", "break", "place", "build", "schematica",
            "litematica", "mine", "areamine", "farm", "tunnel",
            "collectitem", "collect_item", "collect", "giveall",
            "give_all", "trash", "trashlist", "sel", "selection", "s");
    private static final Set<String> READ_ONLY = Set.of(
            "help", "commands", "?", "status", "stats", "paused",
            "proc", "eta", "version", "find", "waypoints", "waypoint",
            "wp");

    private final Map<String, Capability> byName;
    private final Baritone baritone;

    private LlmCapabilityRegistry(Map<String, Capability> byName) {
        this(byName, null);
    }

    private LlmCapabilityRegistry(Map<String, Capability> byName,
                                  Baritone baritone) {
        this.byName = Map.copyOf(byName);
        this.baritone = baritone;
    }

    public static LlmCapabilityRegistry from(Baritone baritone) {
        Map<String, Capability> result = new LinkedHashMap<>();
        for (ICommand command : baritone.getCommandManager()
                .getRegistry().entries) {
            List<String> available = command.getNames().stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .filter(name -> !EXCLUDED.contains(name))
                    .toList();
            for (String name : available) {
                CompletionMode completion = CONTINUOUS.contains(name)
                        ? CompletionMode.CONTINUOUS
                        : FINITE.contains(name)
                        ? CompletionMode.FINITE : CompletionMode.INSTANT;
                Capability capability = new Capability(
                        name, available, command.getShortDesc(), completion,
                        HIGH_IMPACT.contains(name), READ_ONLY.contains(name));
                result.put(name, capability);
            }
        }
        return new LlmCapabilityRegistry(result, baritone);
    }

    public Capability require(String action, List<String> arguments) {
        String normalized = action == null ? ""
                : action.trim().toLowerCase(Locale.ROOT);
        Capability capability = describe(normalized);
        validateArguments(capability, arguments);
        if (Set.of("sel", "selection", "s").contains(normalized)
                && !arguments.isEmpty()
                && Set.of("fill", "set", "cleararea").contains(
                        arguments.getFirst().toLowerCase(Locale.ROOT))) {
            return with(capability, CompletionMode.FINITE, true);
        }
        if (Set.of("set", "setting", "settings").contains(normalized)
                && settingsMutation(arguments)) {
            return with(capability, CompletionMode.INSTANT, true);
        }
        if (normalized.equals("tunnel") && arguments.isEmpty()) {
            return with(capability, CompletionMode.CONTINUOUS, true);
        }
        if (Set.of("get", "getto", "get_to_block").contains(normalized)
                && Baritone.settings().exploreForBlocks.value) {
            return with(capability, CompletionMode.CONTINUOUS,
                    capability.highImpact());
        }
        return capability;
    }

    public Capability describe(String action) {
        String normalized = action == null ? ""
                : action.trim().toLowerCase(Locale.ROOT);
        Capability capability = byName.get(normalized);
        if (capability == null) throw new IllegalArgumentException(
                "AI action is unavailable or forbidden: " + normalized);
        return capability;
    }

    public Collection<Capability> capabilities() {
        return byName.values().stream()
                .distinct()
                .sorted(Comparator.comparing(Capability::name))
                .toList();
    }

    private void validateArguments(
            Capability capability, List<String> arguments) {
        int totalLength = capability.name().length();
        for (String argument : arguments) {
            totalLength += argument.length() + 1;
            for (int index = 0; index < argument.length(); index++) {
                char value = argument.charAt(index);
                if (Character.isISOControl(value) || value == ';'
                        || value == '|' || value == '&') {
                    throw new IllegalArgumentException(
                            "task contains a forbidden character");
                }
            }
        }
        if (totalLength > 512) {
            throw new IllegalArgumentException("task command is too long");
        }
        if (capability.name().equals("set")
                || capability.name().equals("setting")
                || capability.name().equals("settings")) {
            if (arguments.stream().anyMatch(argument ->
                    argument.toLowerCase(Locale.ROOT).startsWith("llm"))) {
                throw new IllegalArgumentException(
                        "AI may not read or modify sensitive LLM settings");
            }
            if (settingsMutation(arguments) && arguments.stream()
                    .anyMatch(argument -> argument.equalsIgnoreCase("all"))) {
                throw new IllegalArgumentException(
                        "AI may not bulk-reset settings because that includes LLM secrets");
            }
        }
        if (baritone != null) validateKnownSyntax(capability.name(), arguments);
    }

    private static boolean settingsMutation(List<String> arguments) {
        if (arguments.isEmpty()) return false;
        String first = arguments.getFirst().toLowerCase(Locale.ROOT);
        if (Set.of("list", "all", "modified").contains(first)) return false;
        // A single setting name is a read; additional values, reset/toggle,
        // and persistent defaults change server state.
        return arguments.size() > 1 || Set.of("reset", "toggle", "default")
                .contains(first);
    }

    private void validateKnownSyntax(String action, List<String> args) {
        switch (action) {
            case "come", "axis", "highway", "surface", "top", "path",
                 "schematica", "clean", "sethome", "paused", "status",
                 "stats", "proc", "eta", "stop", "cancel", "pause", "p",
                 "resume", "r", "unpause" -> exact(args, 0, action);
            case "goto" -> {
                if (args.size() != 2 && args.size() != 3) usage(action);
                args.forEach(LlmCapabilityRegistry::coordinate);
            }
            case "y" -> { exact(args, 1, action); coordinate(args.getFirst()); }
            case "break" -> coordinates(args, 3, action, 0);
            case "place" -> {
                exact(args, 4, action); block(args.getFirst());
                coordinates(args, 4, action, 1);
            }
            case "pos1", "pos2" -> {
                if (!args.isEmpty() && args.size() != 3) usage(action);
                args.forEach(LlmCapabilityRegistry::coordinate);
            }
            case "mine" -> validateMine(args, false);
            case "areamine" -> validateMine(args, true);
            case "get", "getto", "get_to_block" -> {
                exact(args, 1, action); block(args.getFirst());
            }
            case "giveall", "give_all", "follow" -> {
                exact(args, 1, action); onlinePlayer(args.getFirst());
            }
            case "collectitem", "collect_item", "collect" -> validateCollect(args);
            case "runaway", "run_away", "thisway", "forward" -> {
                exact(args, 1, action); positive(args.getFirst());
            }
            case "explore" -> {
                if (!args.isEmpty() && args.size() != 2) usage(action);
                args.forEach(LlmCapabilityRegistry::coordinate);
            }
            case "farm" -> {
                if (args.size() > 1) usage(action);
                if (!args.isEmpty()) positive(args.getFirst());
            }
            case "tunnel" -> {
                if (!args.isEmpty() && args.size() != 3) usage(action);
                args.forEach(LlmCapabilityRegistry::positive);
            }
            case "elytra", "fly" -> {
                if (args.size() != 3) usage(action);
                args.forEach(LlmCapabilityRegistry::coordinate);
            }
            case "pickup" -> {
                args.forEach(LlmCapabilityRegistry::item);
            }
            case "build" -> validateBuild(args);
            case "litematica" -> {
                if (args.size() > 1) usage(action);
                if (!args.isEmpty()) positive(args.getFirst());
            }
            case "trash", "trashlist" -> {
                if (args.isEmpty() || args.size() == 1
                        && args.getFirst().equalsIgnoreCase("list")) return;
                if (args.size() != 2 || !Set.of("add", "remove").contains(
                        args.getFirst().toLowerCase(Locale.ROOT))) usage(action);
                item(args.get(1));
            }
            default -> { /* Actual command handler performs remaining syntax checks. */ }
        }
    }

    private void validateBuild(List<String> args) {
        if (args.isEmpty()) usage("build");
        String mode = args.getFirst().toLowerCase(Locale.ROOT);
        if (mode.equals("syncmatica")) {
            exact(args, 2, "build syncmatica");
            UUID id;
            try { id = UUID.fromString(args.get(1)); }
            catch (IllegalArgumentException exception) { usage("syncmatica id"); return; }
            if (baritone != null && SyncmaticaBridge.find(
                    baritone.getPlayerContext().server(), id).isEmpty()) {
                throw new IllegalArgumentException("unknown Syncmatica blueprint: " + id);
            }
            return;
        }
        if (mode.equals("fill")) {
            exact(args, 8, "build fill");
            block(args.get(1));
            for (int index = 2; index < args.size(); index++) coordinate(args.get(index));
            return;
        }
        if (mode.equals("clear")) {
            exact(args, 7, "build clear");
            for (int index = 1; index < args.size(); index++) coordinate(args.get(index));
            return;
        }
        int pathIndex = mode.equals("file") ? 1 : 0;
        int expectedWithoutOrigin = pathIndex + 1;
        if (args.size() != expectedWithoutOrigin
                && args.size() != expectedWithoutOrigin + 3) usage("build file");
        safeSchematicPath(args.get(pathIndex));
        for (int index = expectedWithoutOrigin; index < args.size(); index++) {
            coordinate(args.get(index));
        }
    }

    private static void safeSchematicPath(String value) {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        Path requested = root.resolve(value).normalize();
        if (Path.of(value).isAbsolute() || !requested.startsWith(root)) {
            throw new IllegalArgumentException(
                    "AI blueprint paths must stay inside the schematics directory");
        }
    }

    private void validateCollect(List<String> args) {
        if (args.size() < 3 || args.size() % 2 == 0) usage("collectItem");
        for (int index = 0; index < args.size() - 1; index += 2) {
            item(args.get(index));
            positive(args.get(index + 1));
        }
        onlinePlayer(args.getLast());
    }

    private static void validateMine(List<String> args, boolean area) {
        if (args.isEmpty()) usage(area ? "areamine" : "mine");
        int blockEnd = args.size();
        if (!area && args.size() > 1 && args.getLast().matches("[0-9]+")) {
            positive(args.getLast());
            blockEnd--;
        }
        for (int index = 0; index < blockEnd; index++) {
            for (String value : args.get(index).split(",")) block(value);
        }
    }

    private void onlinePlayer(String name) {
        if (baritone == null) return;
        ServerPlayer player = baritone.getPlayerContext().server()
                .getPlayerList().getPlayerByName(name);
        if (player == null || player == baritone.getPlayerContext().player()) {
            throw new IllegalArgumentException("unknown recipient/player: " + name);
        }
    }

    private static void coordinates(List<String> args, int total,
                                    String action, int start) {
        exact(args, total, action);
        for (int index = start; index < args.size(); index++) coordinate(args.get(index));
    }

    private static void exact(List<String> args, int count, String action) {
        if (args.size() != count) usage(action);
    }

    private static void usage(String action) {
        throw new IllegalArgumentException("invalid arguments for " + action);
    }

    private static void coordinate(String value) {
        try {
            int coordinate = Integer.parseInt(value);
            if (Math.abs((long) coordinate) > 30_000_000L) usage("coordinate");
        } catch (NumberFormatException exception) { usage("coordinate"); }
    }

    private static void positive(String value) {
        try {
            if (Integer.parseInt(value) <= 0) usage("positive integer");
        } catch (NumberFormatException exception) { usage("positive integer"); }
    }

    private static void block(String value) {
        ResourceLocation id = resource(value);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("unknown block: " + value);
        }
    }

    private static void item(String value) {
        ResourceLocation id = resource(value);
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown item: " + value);
        }
    }

    private static ResourceLocation resource(String value) {
        ResourceLocation id = ResourceLocation.tryParse(
                value.contains(":") ? value : "minecraft:" + value);
        if (id == null) throw new IllegalArgumentException(
                "invalid resource id: " + value);
        return id;
    }

    private static Capability with(Capability original, CompletionMode mode,
                                   boolean highImpact) {
        return new Capability(original.name(), original.aliases(),
                original.description(), mode, highImpact, original.readOnly());
    }

    public enum CompletionMode {
        INSTANT,
        FINITE,
        CONTINUOUS
    }

    public record Capability(
            String name,
            List<String> aliases,
            String description,
            CompletionMode completionMode,
            boolean highImpact,
            boolean readOnly) {
        public Capability {
            aliases = List.copyOf(aliases);
        }
    }
}
