package baritone.server.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Provider-neutral function definitions exposed to the model. */
public final class LlmToolCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmToolCatalog() { }

    public static List<LlmToolDefinition> definitions() {
        List<LlmToolDefinition> result = new ArrayList<>();
        result.add(tool("get_context", "读取发送者、假人、选区和当前计划状态",
                object()));
        result.add(tool("list_capabilities", "列出 AI 可以规划的全部机器人动作",
                object()));
        result.add(tool("get_action_help", "查询一个动作的说明、别名和完成模式",
                singleString("action")));
        result.add(tool("scan_blocks", "在已加载或缓存区块中按距离搜索方块",
                radiusQuery("block_ids")));
        result.add(tool("scan_storage_items", "实时扫描已加载容器和潜影盒中的物品",
                radiusQuery("item_ids")));
        result.add(tool("scan_dropped_items", "扫描附近掉落物实体",
                radiusQuery("item_ids")));
        result.add(tool("inspect_inventory", "读取假人物品栏及其中潜影盒内容",
                object()));
        result.add(tool("inspect_block", "读取指定位置方块、流体和交互条件",
                positionSchema()));
        result.add(tool("scan_entities", "扫描附近玩家、生物和敌对生物",
                radiusOnly()));
        result.add(tool("get_waypoints", "读取当前世界的 Baritone 路径点",
                object()));
        result.add(tool("list_players", "读取在线玩家、维度和位置",
                object()));
        result.add(tool("list_blueprints", "列出服务器可见的 Syncmatica 蓝图",
                object()));
        result.add(tool("probe_reachability", "小预算检查坐标是否已可触及或已有路径覆盖",
                positionSchema()));
        result.add(tool("reply_to_player", "只回复玩家，不执行任务",
                singleString("message")));
        result.add(tool("cancel_plan", "取消当前等待确认或正在执行的 AI 计划",
                singleString("message")));
        result.add(tool("submit_plan", "原子提交一份带依赖关系的多任务计划",
                planSchema()));
        return List.copyOf(result);
    }

    private static LlmToolDefinition tool(
            String name, String description, ObjectNode parameters) {
        parameters.put("type", "object");
        if (!parameters.has("properties")) parameters.set("properties", properties());
        if (!parameters.has("required")) parameters.putArray("required");
        parameters.put("additionalProperties", false);
        return new LlmToolDefinition(name, description, parameters);
    }

    private static ObjectNode radiusQuery(String listName) {
        ObjectNode schema = radiusOnly();
        ((ObjectNode) schema.get("properties")).set(listName,
                MAPPER.createObjectNode().put("type", "array")
                        .set("items", string()));
        schema.withArray("required").add(listName);
        return schema;
    }

    private static ObjectNode singleString(String name) {
        ObjectNode schema = object();
        schema.set("properties", properties().set(name, string()));
        schema.putArray("required").add(name);
        return schema;
    }

    private static ObjectNode radiusOnly() {
        ObjectNode schema = object();
        ObjectNode properties = properties();
        properties.set("radius", integer(1, 4096));
        properties.set("max_results", integer(1, 1024));
        schema.set("properties", properties);
        schema.putArray("required").add("radius").add("max_results");
        return schema;
    }

    private static ObjectNode positionSchema() {
        ObjectNode schema = object();
        ObjectNode properties = properties();
        properties.set("x", integer(-30_000_000, 30_000_000));
        properties.set("y", integer(-2048, 4096));
        properties.set("z", integer(-30_000_000, 30_000_000));
        schema.set("properties", properties);
        schema.putArray("required").add("x").add("y").add("z");
        return schema;
    }

    private static ObjectNode planSchema() {
        ObjectNode dependency = object();
        ObjectNode dependencyProperties = properties();
        dependencyProperties.set("task_id", string());
        dependencyProperties.set("mode", enumeration("required", "optional"));
        dependency.set("properties", dependencyProperties);
        dependency.putArray("required").add("task_id").add("mode");
        dependency.put("additionalProperties", false);

        ObjectNode task = object();
        ObjectNode taskProperties = properties();
        taskProperties.set("id", string());
        taskProperties.set("action", string());
        taskProperties.set("arguments", object().put("type", "array")
                .set("items", string()));
        taskProperties.set("depends_on", object().put("type", "array")
                .set("items", dependency));
        ObjectNode timeout = integer(1, 86_400);
        ArrayNode types = timeout.putArray("type");
        types.add("integer").add("null");
        taskProperties.set("timeout_seconds", timeout);
        task.set("properties", taskProperties);
        task.putArray("required").add("id").add("action")
                .add("arguments").add("depends_on").add("timeout_seconds");
        task.put("additionalProperties", false);

        ObjectNode schema = object();
        ObjectNode planProperties = properties();
        planProperties.set("summary", string());
        ObjectNode tasks = object().put("type", "array");
        tasks.set("items", task);
        planProperties.set("tasks", tasks);
        schema.set("properties", planProperties);
        schema.putArray("required").add("summary").add("tasks");
        return schema;
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static ObjectNode properties() { return MAPPER.createObjectNode(); }
    private static ObjectNode string() { return object().put("type", "string"); }
    private static ObjectNode integer(int minimum, int maximum) {
        return object().put("type", "integer")
                .put("minimum", minimum).put("maximum", maximum);
    }
    private static ObjectNode enumeration(String... values) {
        ObjectNode result = string();
        ArrayNode options = result.putArray("enum");
        for (String value : values) options.add(value);
        return result;
    }
}
