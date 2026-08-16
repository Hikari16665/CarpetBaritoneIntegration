# CarpetBaritoneIntegration

本模组将客户端模组Baritone大部分移植到了 Fabric Carpet 生电服务器中。  
安装此模组后，您服务器中的假人可以拥有寻路、方块放置、破坏、物品栏交互等多种功能。

## 当前的功能列表
  - 来我这里（*完成*）
  - 建造（**有Syncmatica/Litematica支持**，并有来自**Litematica-Printer**的打印算法）
  - 挖矿（*完成*）
  - 农作
  - 鞘翅飞行
  - 跟随玩家
  - 远离威胁
  - 接近方块
  - 回填
  - 交付全部物品
  - 破坏方块
  - 放置方块
  - 拾取掉落物
  - 可设置的垃圾黑名单
  - 拉黑最近目标
  - 设置选区
  - 设置，前往路径点与家

## 什么情况你可能需要此模组？
  - 你希望你的假人拥有更智能，更方便的体验。
  - 你认为假人也要遵守真人玩家的规矩。

## 什么情况你不应该使用此模组？
  - 你认为假人不应该拥有扫描能力。
  - 你认为假人不应该卡住。
  - 你认为你的服务器不需要人类。

## 操作与设置  
为了方便更好的操作假人，本模组提供了两种操控方式：
  - 自然语言私聊：`/tell <假人名称> 帮我挖 16 个钻石矿`。发送给
    Carpet 假人的所有非 `cbi` 前缀私聊都会进入独立的连续 AI 会话。
  - 精确指令：`/tell <假人名称> cbi <命令>`。`cbi` 前缀不会经过
    AI，可用于帮助、设置和确定性控制。
  - GUI操作：在客户端安装此模组，按住B（按键可后期调整）进行控制。

### AI 自然语言控制

服务端通过设置系统直接配置兼容 OpenAI Responses API 的 `base_url`、
`model` 和 `api_key`。`base_url` 会自动补全 `/responses`，也兼容直接填写
完整接口地址。例如：

```text
/tell Steve cbi settings default llmBaseUrl https://api.openai.com/v1
/tell Steve cbi settings default llmApiMode auto
/tell Steve cbi settings default llmModel gpt-5.6-luna
/tell Steve cbi settings default llmApiKey <你的 API Key>
```

`llmApiMode` 支持 `auto`、`responses` 和 `chat_completions`。`auto` 默认
为 OpenAI 使用 Responses API；当地址是 DeepSeek 或完整地址以
`/chat/completions` 结尾时改用 Chat Completions。DeepSeek 示例：

```text
/tell Steve cbi settings default llmBaseUrl https://api.deepseek.com
/tell Steve cbi settings default llmApiMode auto
/tell Steve cbi settings default llmModel deepseek-chat
```

持久 API Key 会明文保存在服务端的 CBI 默认设置文件中。聊天查询、日志与
发送给客户端的设置数据只会显示掩码，不会返回密钥原文。无需鉴权的本地端点
可以把 `llmApiKey` 留空。配置密钥时直接粘贴原始值即可；误带的 `Bearer `
前缀或一层引号会在发送请求前自动清理。

会话按“发送者 + 假人”隔离。模型每轮只能回复、提出待确认任务、执行一条
白名单 CBI 指令或取消待确认任务。`clean`、直接放置和直接破坏等修改方块的
指令必须先提出并由玩家下一轮明确确认。发送者的位置、维度、在线玩家列表和
假人的当前选区会随每轮请求更新。

## 许可证
本模组包含来自多个模组的源代码，其许可证如下：
 - [Baritone](https://github.com/cabaletta/baritone) LGPL-3.0 license [原文](https://github.com/cabaletta/baritone?tab=LGPL-3.0-1-ov-file)
 - [Litematica-Printer](https://github.com/aleksilassila/litematica-printer) AGPL-3.0 license [原文](https://github.com/aleksilassila/litematica-printer?tab=AGPL-3.0-1-ov-file)

Baritone 的代码部分遵循 LGPL-3.0。  
但根据 LGPL 的允许，在本项目中已升级为 GPLv3。  
以便与Litematica-Printer（AGPLv3）兼容。  
考虑许可证兼容性问题，本模组将以`AGPLv3`开源。详见LICENSE文件。
