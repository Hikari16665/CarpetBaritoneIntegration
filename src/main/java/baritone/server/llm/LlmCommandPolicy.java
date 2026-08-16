package baritone.server.llm;

import java.util.Locale;
import java.util.Set;

/** Server-side boundary around model generated commands. */
public final class LlmCommandPolicy {
    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "goto", "come", "y", "mine", "areamine", "trash",
            "collectitem", "collect_item", "collect", "giveall",
            "give_all", "break", "place", "follow", "explore",
            "get", "getto", "get_to_block", "backfill", "farm",
            "build", "schematica", "litematica", "elytra", "fly",
            "runaway", "run_away", "goal", "path", "proc", "eta",
            "surface", "top", "thisway", "forward", "axis",
            "highway", "tunnel", "sel", "selection", "s",
            "waypoints", "waypoint", "wp", "sethome", "home",
            "blacklist", "find", "pickup", "avoid", "avoidance",
            "pos1", "pos2", "clean", "stop", "cancel", "pause",
            "p", "resume", "r", "unpause", "paused", "status",
            "stats"
    );

    private static final Set<String> AFFIRMATIVE = Set.of(
            "好", "好的", "可以", "是", "确认", "确认执行", "执行",
            "执行吧", "好的执行", "好的执行吧", "现在执行", "立即执行",
            "开始", "开始吧", "继续", "继续吧", "yes", "y", "ok",
            "okay", "confirm", "execute", "do it"
    );

    private static final Set<String> NEGATIVE = Set.of(
            "不", "不要", "不要执行", "取消", "算了", "否", "no", "n",
            "cancel", "stop"
    );

    private LlmCommandPolicy() { }

    public static String validate(String command) {
        String raw = command == null ? "" : command.trim();
        for (int index = 0; index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (Character.isISOControl(value) || value == ';'
                    || value == '|' || value == '&') {
                throw new IllegalArgumentException(
                        "模型生成的任务包含不允许的字符");
            }
        }
        String normalized = raw.replaceAll(" +", " ");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("模型没有提供任务指令");
        }
        if (normalized.length() > 512 || normalized.startsWith("/")) {
            throw new IllegalArgumentException("模型生成的任务格式无效");
        }
        String root = root(normalized);
        if (!ALLOWED_ROOTS.contains(root)) {
            throw new IllegalArgumentException(
                    "模型尝试使用未授权的任务: " + root);
        }
        return normalized;
    }

    public static boolean requiresConfirmation(String command) {
        String normalized = validate(command);
        String root = root(normalized);
        if (root.equals("clean") || root.equals("break")
                || root.equals("place")) return true;
        if (root.equals("sel") || root.equals("selection")
                || root.equals("s")) {
            String[] parts = normalized.split(" ");
            if (parts.length > 1) {
                String action = parts[1].toLowerCase(Locale.ROOT);
                return action.equals("fill") || action.equals("set")
                        || action.equals("cleararea");
            }
        }
        return false;
    }

    public static boolean isAffirmative(String message) {
        String normalized = normalizeAnswer(message);
        if (normalized.isEmpty() || NEGATIVE.contains(normalized)
                || normalized.startsWith("不")
                || normalized.startsWith("别")) return false;
        return AFFIRMATIVE.contains(normalized);
    }

    public static boolean isNegative(String message) {
        String normalized = normalizeAnswer(message);
        return NEGATIVE.contains(normalized) || normalized.startsWith("不")
                || normalized.startsWith("别");
    }

    private static String normalizeAnswer(String message) {
        return message == null ? "" : message.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[。！!？?，,]", "")
                .replaceAll("\\s+", " ");
    }

    private static String root(String command) {
        int separator = command.indexOf(' ');
        return (separator < 0 ? command : command.substring(0, separator))
                .toLowerCase(Locale.ROOT);
    }
}
