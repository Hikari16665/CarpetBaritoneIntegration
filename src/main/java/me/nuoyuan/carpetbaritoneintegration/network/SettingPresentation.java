package me.nuoyuan.carpetbaritoneintegration.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Chinese presentation metadata for the otherwise stable Baritone keys. */
final class SettingPresentation {
    private static final Map<String, String> TERMS = Map.ofEntries(
            Map.entry("allow", "允许"), Map.entry("assume", "假定"),
            Map.entry("inventory", "物品栏"), Map.entry("moves", "移动"),
            Map.entry("only", "仅"), Map.entry("if", "当"),
            Map.entry("stationary", "静止时"), Map.entry("ticks", "刻"),
            Map.entry("between", "间隔"), Map.entry("block", "方块"),
            Map.entry("break", "破坏"), Map.entry("place", "放置"),
            Map.entry("placement", "放置"), Map.entry("speed", "速度"),
            Map.entry("right", "右键"), Map.entry("click", "交互"),
            Map.entry("path", "路径"), Map.entry("pathing", "寻路"),
            Map.entry("cached", "缓存"), Map.entry("cache", "缓存"),
            Map.entry("chunk", "区块"), Map.entry("world", "世界"),
            Map.entry("goal", "目标"), Map.entry("builder", "建造"),
            Map.entry("build", "建造"), Map.entry("printer", "打印机"),
            Map.entry("render", "渲染"), Map.entry("color", "颜色"),
            Map.entry("line", "线条"), Map.entry("width", "宽度"),
            Map.entry("pixels", "像素"), Map.entry("selection", "选区"),
            Map.entry("current", "当前"), Map.entry("next", "下一段"),
            Map.entry("best", "最佳"), Map.entry("recent", "最近"),
            Map.entry("considered", "已探索"), Map.entry("cost", "代价"),
            Map.entry("penalty", "惩罚"), Map.entry("multiplier", "倍率"),
            Map.entry("coefficient", "系数"), Map.entry("timeout", "超时"),
            Map.entry("primary", "首轮"), Map.entry("failure", "失败"),
            Map.entry("retry", "重试"), Map.entry("count", "次数"),
            Map.entry("distance", "距离"), Map.entry("radius", "半径"),
            Map.entry("max", "最大"), Map.entry("min", "最小"),
            Map.entry("minimum", "最小"), Map.entry("maximum", "最大"),
            Map.entry("mine", "采矿"), Map.entry("mining", "采矿"),
            Map.entry("ore", "矿物"), Map.entry("item", "物品"),
            Map.entry("collect", "收集"), Map.entry("scan", "扫描"),
            Map.entry("dropped", "掉落物"), Map.entry("follow", "跟随"),
            Map.entry("offset", "偏移"), Map.entry("direction", "方向"),
            Map.entry("explore", "探索"), Map.entry("farm", "农场"),
            Map.entry("replant", "补种"), Map.entry("elytra", "鞘翅"),
            Map.entry("auto", "自动"), Map.entry("jump", "跳跃"),
            Map.entry("swap", "换装"), Map.entry("durability", "耐久"),
            Map.entry("fireworks", "烟花"), Map.entry("landing", "降落"),
            Map.entry("cruise", "巡航"), Map.entry("altitude", "高度"),
            Map.entry("water", "水"), Map.entry("lava", "岩浆"),
            Map.entry("fluid", "流体"), Map.entry("fluids", "流体"),
            Map.entry("sprint", "疾跑"), Map.entry("walk", "行走"),
            Map.entry("diagonal", "斜向"), Map.entry("ascend", "上升"),
            Map.entry("descend", "下降"), Map.entry("downward", "向下"),
            Map.entry("parkour", "跑酷"), Map.entry("fall", "坠落"),
            Map.entry("height", "高度"), Map.entry("bucket", "水桶"),
            Map.entry("tool", "工具"), Map.entry("sword", "剑"),
            Map.entry("avoid", "避开"), Map.entry("avoidance", "规避"),
            Map.entry("mob", "生物"), Map.entry("spawner", "刷怪笼"),
            Map.entry("diagnostic", "诊断"), Map.entry("logging", "日志"),
            Map.entry("layer", "分层"), Map.entry("layers", "分层"),
            Map.entry("repeat", "重复"), Map.entry("schematic", "蓝图"),
            Map.entry("ignore", "忽略"), Map.entry("existing", "现有"),
            Map.entry("mirror", "镜像"), Map.entry("rotation", "旋转"),
            Map.entry("map", "地图"), Map.entry("art", "画"),
            Map.entry("trash", "垃圾"), Map.entry("acceptable", "可用"),
            Map.entry("throwaway", "垫路物品"), Map.entry("items", "物品"),
            Map.entry("backfill", "路径回填"), Map.entry("smooth", "平滑"),
            Map.entry("look", "视角"), Map.entry("reach", "交互距离"),
            Map.entry("server", "服务器"), Map.entry("tasks", "任务"),
            Map.entry("keep", "保持"), Map.entry("awake", "运行"),
            Map.entry("notification", "通知"), Map.entry("finished", "完成"),
            Map.entry("mode", "模式"), Map.entry("queue", "队列"),
            Map.entry("range", "范围"), Map.entry("shape", "形状"),
            Map.entry("interval", "间隔"), Map.entry("continuous", "连续"),
            Map.entry("container", "容器"), Map.entry("refill", "补货"),
            Map.entry("batch", "批量"), Map.entry("size", "大小"),
            Map.entry("threshold", "阈值"), Map.entry("history", "历史"),
            Map.entry("cutoff", "截断"), Map.entry("factor", "系数"),
            Map.entry("load", "加载"), Map.entry("boundary", "边界"),
            Map.entry("slow", "慢速"), Map.entry("delay", "延迟"),
            Map.entry("cancel", "取消"), Map.entry("invalidation", "失效"),
            Map.entry("blacklist", "黑名单"), Map.entry("closest", "最近目标"),
            Map.entry("exposed", "暴露"), Map.entry("legit", "拟真"),
            Map.entry("internal", "内部"), Map.entry("exception", "例外"),
            Map.entry("synchronous", "同步"), Map.entry("packer", "打包器"),
            Map.entry("expiry", "过期"),
            Map.entry("seconds", "秒"), Map.entry("prune", "清理"),
            Map.entry("regions", "区域"), Map.entry("ram", "内存"),
            Map.entry("update", "更新"), Map.entry("falling", "下落方块"),
            Map.entry("strict", "严格"), Map.entry("liquid", "液体"),
            Map.entry("potion", "药水"), Map.entry("effects", "效果"),
            Map.entry("saver", "保护"), Map.entry("additional", "额外"),
            Map.entry("favoring", "偏好"), Map.entry("axis", "轴线"),
            Map.entry("move", "移动"), Map.entry("anti", "反作弊"),
            Map.entry("cheat", "校验"), Map.entry("compatibility", "兼容"),
            Map.entry("through", "经过"), Map.entry("simplify", "简化"),
            Map.entry("unloaded", "未加载区域"), Map.entry("coord", "坐标"),
            Map.entry("disconnect", "断开连接"), Map.entry("on", "在"),
            Map.entry("arrival", "到达后"), Map.entry("from", "从"),
            Map.entry("above", "上方"), Map.entry("incorrect", "不正确"),
            Map.entry("correct", "正确"), Map.entry("tick", "刻"),
            Map.entry("action", "动作"), Map.entry("actions", "动作"),
            Map.entry("same", "相同"), Map.entry("position", "位置"),
            Map.entry("cooldown", "冷却"), Map.entry("per", "每"),
            Map.entry("print", "打印"), Map.entry("in", "在"),
            Map.entry("air", "空中"), Map.entry("wrong", "错误"),
            Map.entry("blocks", "方块"), Map.entry("replace", "替换"),
            Map.entry("waterlogged", "含水方块"), Map.entry("search", "搜索"),
            Map.entry("chunks", "区块"), Map.entry("trim", "裁剪"),
            Map.entry("as", "作为"), Map.entry("animated", "动画"),
            Map.entry("boxes", "边框"), Map.entry("fade", "淡出"),
            Map.entry("level", "层级"), Map.entry("box", "边框"),
            Map.entry("to", "待"), Map.entry("into", "进入"),
            Map.entry("so", "截至"), Map.entry("far", "目前"),
            Map.entry("most", "最"), Map.entry("inverted", "反色"),
            Map.entry("censor", "隐藏"), Map.entry("coordinates", "坐标"),
            Map.entry("at", "在"), Map.entry("length", "长度"),
            Map.entry("remain", "保持"), Map.entry("with", "使用"),
            Map.entry("prefer", "优先"), Map.entry("silk", "精准采集"),
            Map.entry("touch", "附魔"), Map.entry("border", "边界"),
            Map.entry("fetch", "获取"), Map.entry("snapshot", "快照"),
            Map.entry("warmup", "预热"), Map.entry("budget", "预算"),
            Map.entry("improvement", "改进"),
            Map.entry("repropagation", "重新传播"),
            Map.entry("default", "默认"), Map.entry("time", "时间"),
            Map.entry("ascends", "上坡"), Map.entry("increase", "增幅"),
            Map.entry("verification", "验证"), Map.entry("lookahead", "前瞻"),
            Map.entry("overshoot", "越步"), Map.entry("movement", "移动"),
            Map.entry("amount", "数量"), Map.entry("planning", "规划"),
            Map.entry("plan", "规划"), Map.entry("ahead", "提前"),
            Map.entry("backoff", "退避"), Map.entry("splice", "拼接"),
            Map.entry("locations", "位置"), Map.entry("while", "期间"),
            Map.entry("ores", "矿物"), Map.entry("target", "目标"),
            Map.entry("exploring", "探索"), Map.entry("set", "集合"),
            Map.entry("maintain", "保持"), Map.entry("disable", "禁用"),
            Map.entry("completion", "完成"), Map.entry("check", "检查"),
            Map.entry("enter", "进入"), Map.entry("portal", "传送门"),
            Map.entry("for", "用于"), Map.entry("include", "包含"),
            Map.entry("diagonals", "斜向"), Map.entry("force", "强制"),
            Map.entry("caching", "缓存"), Map.entry("scanner", "扫描器"),
            Map.entry("extend", "扩展"), Map.entry("repack", "重新打包"),
            Map.entry("any", "任意"), Map.entry("change", "变化"),
            Map.entry("crops", "作物"), Map.entry("nether", "下界"),
            Map.entry("wart", "疣"), Map.entry("before", "前"),
            Map.entry("emergency", "紧急"), Map.entry("land", "降落"),
            Map.entry("glide", "滑翔"), Map.entry("low", "低"),
            Map.entry("boost", "加速"), Map.entry("approach", "进近"),
            Map.entry("do", "记录"), Map.entry("bed", "床"),
            Map.entry("death", "死亡"), Map.entry("waypoints", "路径点"),
            Map.entry("order", "顺序"), Map.entry("start", "起始"),
            Map.entry("skip", "跳过"), Map.entry("failed", "失败"),
            Map.entry("ok", "允许保留"), Map.entry("orientation", "朝向"),
            Map.entry("sneaky", "潜行"), Map.entry("limit", "高度限制"),
            Map.entry("flow", "流动区域"), Map.entry("source", "源头"),
            Map.entry("vines", "藤蔓"), Map.entry("bottom", "下半"),
            Map.entry("slab", "台阶"), Map.entry("magma", "岩浆块"),
            Map.entry("external", "外部"), Map.entry("safe", "安全"),
            Map.entry("step", "自动上台阶"), Map.entry("updating", "更新"),
            Map.entry("backtrack", "回溯"), Map.entry("no", "无"),
            Map.entry("traverse", "横向移动"), Map.entry("pause", "暂停"),
            Map.entry("breaking", "破坏中"), Map.entry("consider", "考虑"),
            Map.entry("use", "使用"), Map.entry("one", "单格"));

    private SettingPresentation() { }

    static String name(String key) {
        return switch (key) {
            case "allowInventory" -> "允许自动整理物品栏";
            case "autoTool" -> "自动选择最佳工具";
            case "blockBreakSpeed" -> "连续破坏操作速度";
            case "rightClickSpeed" -> "连续放置操作速度";
            case "costHeuristic" -> "A* 启发式权重";
            case "avoidance" -> "启用危险生物路径规避";
            case "emergencyAvoidance" -> "启用爆炸物紧急躲避";
            case "tntAvoidanceRadius" -> "TNT 紧急躲避半径";
            case "tntPredictionTicks" -> "TNT 轨迹预测刻数";
            case "creeperAvoidanceRadius" -> "苦力怕躲避半径";
            case "creeperCriticalRadius" -> "苦力怕紧急距离";
            case "emergencyAvoidanceJump" -> "紧急躲避时允许跳跃";
            case "autoCombat" -> "自动应对敌对生物";
            case "hostileDetectionRadius" -> "敌对生物感知半径";
            case "hostileAggroRadius" -> "近距离主动威胁半径";
            case "combatAttackReach" -> "自动战斗攻击距离";
            case "combatMinAttackStrength" -> "自动攻击最小蓄力比例";
            case "combatFleeWithoutWeapon" -> "无武器时逃离敌怪";
            case "autoEat" -> "自动进食";
            case "autoEatHungerThreshold" -> "自动进食饥饿阈值";
            case "autoEatWhenHealthMissing" -> "受伤且未吃饱时进食";
            case "autoEatAvoidHarmfulFoods" -> "自动进食避开有害食物";
            case "autoEatAvoidValuableFoods" -> "自动进食保留贵重食物";
            case "fakePlayerPublicMessages" -> "假人公屏状态消息";
            case "fakePlayerMessageCooldownTicks" -> "状态消息重复冷却";
            case "fakePlayerCompletionMessageMinTicks" -> "长任务完成消息门槛";
            case "fakePlayerNoFoodMessage" -> "没有食物消息";
            case "fakePlayerTaskFailureMessage" -> "任务失败消息";
            case "fakePlayerMissingToolMessage" -> "缺少合适工具消息";
            case "fakePlayerBuilderMissingMaterialsMessage" -> "建造缺少材料消息";
            case "fakePlayerNoBridgeBlocksMessage" -> "缺少垫路方块消息";
            case "fakePlayerInventoryBlockedMessage" -> "物品栏已满消息";
            case "fakePlayerCollectIncompleteMessage" -> "收集不完整消息";
            case "fakePlayerTargetUnavailableMessage" -> "目标不可用消息";
            case "fakePlayerStuckMessage" -> "持续卡住消息";
            case "fakePlayerTaskCompleteMessage" -> "长任务完成消息";
            case "llmEnabled" -> "启用 AI 自然语言控制";
            case "llmBaseUrl" -> "AI 基础接口地址";
            case "llmApiMode" -> "AI 接口协议";
            case "llmModel" -> "AI 模型";
            case "llmApiKey" -> "AI 接口密钥";
            case "llmThinkingEnabled" -> "AI 深度思考";
            case "llmReasoningEffort" -> "AI 推理强度";
            case "llmRequestTimeoutSeconds" -> "AI 请求超时秒数";
            case "llmSessionTimeoutSeconds" -> "AI 会话超时秒数";
            case "llmHistoryTurns" -> "AI 会话历史轮数";
            case "llmMaxPlanTasks" -> "AI 单计划最大任务数";
            case "llmMaxToolRounds" -> "AI 工具调用最大轮数";
            case "llmMaxToolCallsPerTurn" -> "AI 单轮最大工具调用数";
            case "llmObservationMaxDistance" -> "AI 观测最大距离";
            case "llmToolResultLimit" -> "AI 工具单次结果上限";
            case "llmPlanRepairAttempts" -> "AI 计划修复次数";
            case "acceptableThrowawayItems" -> "允许用于搭路的物品";
            case "trashItems" -> "垃圾物品黑名单";
            case "trashDiscardEnabled" -> "任务中自动丢弃垃圾";
            case "throwawayBlockReserve" -> "垫路方块基础保留量";
            case "throwawayBlockReserveMaximum" -> "垫路方块最大保留量";
            case "throwawayPlacementSafetyMargin" -> "计划放置安全余量";
            case "blocksToAvoid" -> "寻路应避开的方块";
            case "blocksToDisallowBreaking" -> "绝对禁止破坏的方块";
            case "blocksToAvoidBreaking" -> "尽量避免破坏的方块";
            case "allowBreakAnyway" -> "即使受限也允许破坏的方块";
            case "collectItemMaxDistance" -> "收集物品最大搜索距离";
            case "mineGoalCompositeBatchSize" -> "单批采矿候选目标数";
            case "mineGoalBatchingDistance" -> "采矿候选分批距离";
            case "blockPlacementPenalty" -> "寻路放置方块代价";
            case "blockBreakAdditionalPenalty" -> "寻路破坏方块额外代价";
            case "mineBlockBreakAdditionalPenalty" -> "采矿任务破坏额外代价";
            case "goalDirectedPlacementMultiplier" -> "朝目标搭路代价倍率";
            case "goalDirectedPillarCostMultiplier" -> "朝目标搭高代价倍率";
            case "keepServerAwakeForTasks" -> "有任务时保持服务器运行";
            case "schematicFallbackExtension" -> "蓝图默认扩展名";
            case "buildValidSubstitutes" -> "建造有效替代方块表";
            case "buildSubstitutes" -> "建造强制替代方块表";
            case "okIfAir" -> "可视为空气的方块";
            case "buildIgnoreBlocks" -> "建造忽略方块列表";
            case "buildIgnoreProperties" -> "建造忽略状态属性";
            case "buildSkipBlocks" -> "建造跳过方块列表";
            case "mineDropLoiterDurationMSThanksLouca" -> "采矿掉落物等待时间";
            default -> translateKey(key);
        };
    }

    static String category(String key) {
        if (key.startsWith("render") || key.startsWith("color")
                || key.contains("Render") || key.equals("fadePath")) {
            return "客户端渲染";
        }
        if (key.startsWith("builder") || key.startsWith("build")
                || key.startsWith("printer") || key.startsWith("schematic")
                || key.equals("mapArtMode")) return "建造与打印";
        if (key.startsWith("mine") || key.contains("Mining")
                || key.contains("Ore")) return "采矿";
        if (key.startsWith("collectItem")) return "物品收集";
        if (key.startsWith("elytra")) return "鞘翅飞行";
        if (key.startsWith("farm") || key.startsWith("replant")) return "农场";
        if (key.startsWith("follow")) return "跟随";
        if (key.startsWith("llm")) return "AI 自然语言控制";
        if (key.startsWith("fakePlayer") && key.contains("Message")) {
            return "公屏状态消息";
        }
        if (key.startsWith("explore") || key.startsWith("worldExploring")) return "探索";
        if (key.contains("Cache") || key.startsWith("chunk")
                || key.startsWith("repack") || key.startsWith("prune")) return "世界缓存";
        if (key.contains("Inventory") || key.contains("Tool")
                || key.startsWith("item") || key.contains("Throwaway")
                || key.startsWith("throwaway")
                || key.startsWith("trash")) return "物品栏";
        if (key.contains("Avoid") || key.startsWith("mob")) return "安全与规避";
        if (key.startsWith("allow") || key.startsWith("assume")
                || key.contains("Fall") || key.contains("Walk")
                || key.contains("Sprint") || key.contains("Jump")) return "移动能力";
        if (key.contains("Timeout") || key.contains("Path")
                || key.contains("Cost") || key.contains("Penalty")
                || key.contains("Heuristic")) return "寻路算法";
        return "常规";
    }

    static String description(String key) {
        return switch (key) {
            case "allowInventory" -> "允许假人自动移动物品栏与快捷栏中的物品；关闭后自动换工具和补充搭路方块会受限。";
            case "autoTool" -> "破坏方块前，根据真实挖掘速度、附魔和耐久自动选择快捷栏中的最佳工具。";
            case "acceptableThrowawayItems" -> "寻路需要搭高或架桥时可以消耗的方块物品；任务目标物品仍会受到额外保护。";
            case "trashItems" -> "任务无关且无需保留时可以主动丢弃的物品类型。";
            case "fakePlayerPublicMessages" -> "允许假人以自己的名字向全服公屏报告重要状态变化；关闭后不会发送任何自动公屏消息。";
            case "fakePlayerMessageCooldownTicks" -> "同一种事件再次发送前至少等待的服务器刻数，默认 1200 刻（60 秒）；持续状态只有恢复后再次发生才会重发。";
            case "fakePlayerCompletionMessageMinTicks" -> "任务至少持续这么多服务器刻才公屏报告完成，默认 600 刻（30 秒），避免短指令刷屏。";
            case "fakePlayerNoFoodMessage" -> "确实需要进食且背包中没有允许自动食用的食物时发送。支持 {player}、{food}、{health}、{max_health}、{x}、{y}、{z}；留空禁用。";
            case "fakePlayerTaskFailureMessage" -> "任务耗尽恢复或寻路重试后仍失败时发送。支持通用占位符以及 {task}、{reason}；留空禁用。";
            case "fakePlayerMissingToolMessage" -> "破坏前没有合适工具、只能使用空手或剑等替代物时发送。支持 {tool}、{fallback}；重新获得工具前不会重复。";
            case "fakePlayerBuilderMissingMaterialsMessage" -> "Builder 因缺少建材暂停时发送。支持 {items}；材料恢复后才允许下一次提醒。";
            case "fakePlayerNoBridgeBlocksMessage" -> "实际需要搭高、架桥或填流体却没有安全完整方块时发送；补充垫路方块后恢复。";
            case "fakePlayerInventoryBlockedMessage" -> "物品栏已满并实际阻断当前流程时发送。支持 {task}、{reason}；仅仅背包已满但任务可继续时不发送。";
            case "fakePlayerCollectIncompleteMessage" -> "collectItem 扫描结束仍未收齐时发送。支持 {summary}；每种物品的已交付/请求数量会汇总在其中。";
            case "fakePlayerTargetUnavailableMessage" -> "交付或跟随目标离线、跨维度或最终不可达时发送。支持 {target}、{reason}。";
            case "fakePlayerStuckMessage" -> "任务至少 10 秒没有移动进展且连续三次自动恢复失败时发送。支持位置占位符。";
            case "fakePlayerTaskCompleteMessage" -> "达到持续时间门槛的重要长任务完成时发送。支持 {task}、{summary}；短任务不会发送。";
            case "trashDiscardEnabled" -> "任务移动期间自动丢弃垃圾黑名单中的无用物品；关闭后仅停止主动丢弃，不影响原版拾取。";
            case "throwawayBlockReserve" -> "即使方块位于垃圾黑名单，也至少保留这么多个安全完整方块用于搭高和架桥；默认约两组。";
            case "throwawayBlockReserveMaximum" -> "当前路径需要大量放置时，动态垫路方块保留量允许增长到的上限。";
            case "throwawayPlacementSafetyMargin" -> "在当前路径已知放置数量之外额外保留的垫路方块，避免重算或临时搭高时断料。";
            case "blockPlacementPenalty" -> "A* 为放置一个垫路方块增加的基础代价；越低越愿意搭路。";
            case "blockBreakAdditionalPenalty" -> "普通寻路为破坏方块增加的额外代价；越低越愿意开路。";
            case "mineBlockBreakAdditionalPenalty" -> "mine 任务开挖矿道时使用的额外破坏代价。";
            case "goalDirectedPlacementMultiplier" -> "放置动作能直接缩短到目标距离时，对放置代价应用的倍率。";
            case "goalDirectedPillarCostMultiplier" -> "目标位于上方时，对原地搭高动作代价应用的倍率。";
            case "collectItemMaxDistance" -> "collectItem 从当前位置球形向外搜索容器时允许到达的最大方块距离。";
            case "avoidance" -> "把危险生物与刷怪笼附近区域加入路径代价，优先选择更安全的路线。";
            case "mobAvoidanceRadius" -> "普通危险生物对路径产生额外代价的半径。";
            case "mobAvoidanceCoefficient" -> "普通危险生物附近路径代价的倍率；越大越倾向绕行。";
            case "mobSpawnerAvoidanceRadius" -> "刷怪笼对路径产生额外代价的半径。";
            case "mobSpawnerAvoidanceCoefficient" -> "刷怪笼附近路径代价的倍率。";
            case "pathingSnapshotWarmupChunkBudget" -> "每次异步寻路前允许在服务器线程复制的精确区块快照数量。";
            case "primaryTimeoutMS" -> "普通寻路首次搜索尝试的时间预算，单位毫秒。";
            case "failureTimeoutMS" -> "首次搜索未抵达目标后继续寻找可用路径的时间预算，单位毫秒。";
            case "planningTickLookahead" -> "当前路径预计剩余少于此刻数时，提前计算下一段路径。";
            case "mineGoalCompositeBatchSize" -> "一次交给寻路器竞争的矿物目标上限，过大可能显著增加计算量。";
            case "mineBlacklistCooldownTicks" -> "暂时不可达矿物进入黑名单后，经过多少刻允许重新尝试。";
            case "blockReachDistance" -> "服务端假交互允许触及方块的最大距离。";
            case "printerMaxActionsPerTick" -> "打印建造每个服务器刻最多连续执行的放置或破坏动作数。";
            case "printerRange" -> "打印机从假人当前位置执行蓝图动作的最大距离。";
            case "printerContainerRefill" -> "建造材料不足时，允许从配置的取货选区或附近容器补货。";
            case "printerContainerRefillBatch" -> "单次自动取货允许携带的最大物品数；Builder 会先估算蓝图剩余需求，再在此上限和背包容量内尽量一次取足。";
            case "llmEnabled" -> "开启后，发给 Carpet 假人的所有非 cbi 前缀私聊都会进入 AI 连续会话；cbi 前缀始终保留为确定性的原始指令入口。";
            case "llmBaseUrl" -> "AI 服务的基础地址或完整端点；会根据接口协议补全 /responses 或 /chat/completions。";
            case "llmApiMode" -> "AUTO 会为 OpenAI 使用 Responses、为 DeepSeek 或显式 /chat/completions 地址使用 Chat Completions；也可以手动强制协议。";
            case "llmModel" -> "提交自然语言任务时使用的模型名称；Responses 使用严格 JSON Schema，Chat Completions 使用 JSON Object 并由服务端继续校验。";
            case "llmApiKey" -> "Responses API 的鉴权密钥；直接粘贴密钥即可，误带的 Bearer 前缀或外层引号会被自动清理。设为持久默认值后会明文保存在服务端配置文件中，但聊天查询、日志和客户端设置同步只显示掩码。留空可连接无需鉴权的本地端点。";
            case "llmThinkingEnabled" -> "对 Chat Completions 添加 thinking={type:enabled}；用于 DeepSeek 等支持该扩展字段的服务商。";
            case "llmReasoningEffort" -> "对 Chat Completions 添加 reasoning_effort，例如 high；留空则不发送，不兼容时请留空。";
            case "llmRequestTimeoutSeconds" -> "单轮模型 HTTP 请求的最长等待时间；请求异步执行，不会阻塞服务器 tick。";
            case "llmSessionTimeoutSeconds" -> "发送者与单个假人的连续会话在无新消息后保留多久；超时后历史和待确认任务会丢弃。";
            case "llmHistoryTurns" -> "每次请求最多重放多少轮对话。历史按发送者与假人隔离，并包含每轮受控的玩家位置和选区状态。";
            case "llmMaxPlanTasks" -> "模型一次提交的依赖计划最多包含多少个任务；超过上限会整份拒绝且不执行。";
            case "llmMaxToolRounds" -> "一条玩家消息最多允许模型进行多少轮观测工具调用，防止工具循环。";
            case "llmMaxToolCallsPerTurn" -> "一条玩家消息累计可执行的只读工具调用上限。";
            case "llmObservationMaxDistance" -> "方块、容器、实体和掉落物观测工具允许使用的最大半径；不会因此加载新区块。";
            case "llmToolResultLimit" -> "每个观测工具最多返回的条目数，结果仍会受到 JSON 总长度限制。";
            case "llmPlanRepairAttempts" -> "模型计划未通过服务端验证时，允许把错误返回模型重新生成的次数；执行失败不会自动重规划。";
            case "diagnosticLogging" -> "向服务器日志输出 CBI 寻路、建造与交互诊断信息。";
            default -> category(key) + "设置：“" + name(key)
                    + "”。修改后会影响所有现有假人和以后创建的假人。";
        };
    }

    private static String translateKey(String key) {
        String separated = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .toLowerCase(Locale.ROOT);
        List<String> translated = new ArrayList<>();
        for (String token : separated.split("\\s+")) {
            if (token.equals("ms")) translated.add("毫秒");
            else if (token.equals("y")) translated.add("Y 高度");
            else if (token.equals("x")) translated.add("X 轴");
            else if (token.equals("z")) translated.add("Z 轴");
            else translated.add(TERMS.getOrDefault(token, token));
        }
        return String.join("·", translated);
    }
}
