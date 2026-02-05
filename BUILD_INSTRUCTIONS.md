# MC AI Assistant 构建说明

## 环境要求

- Java 21
- Maven 3.8.7 或更高版本
- PaperMC 1.21.x 服务器（1.21-1.21.11）

## 构建步骤

1. 确保已安装 Java 21 和 Maven
2. 在项目根目录下运行以下命令：

```bash
mvn clean package
```

3. 构建成功后，插件 JAR 文件将位于 `target/mc-ai-assistant-1.0.0.jar`

## 安装步骤

1. 将构建好的 JAR 文件复制到 PaperMC 服务器的 `plugins` 目录
2. 重启服务器或使用插件管理器加载插件
3. 首次加载后，插件将在 `plugins/McAiAssistant` 目录下生成配置文件
4. 编辑 `plugins/McAiAssistant/config.yml` 文件，设置您的 API 密钥和其他配置

## 配置说明

### API 配置

在 `config.yml` 中，您需要设置以下 API 相关配置：

```yaml
ai:
  # API 密钥
  api_key: "your-api-key-here"
  
  # API 基础 URL (支持 OpenAI 格式的 API)
  api_url: "https://api.openai.com/v1"
  
  # 使用的模型名称
  model: "gpt-3.5-turbo"
```

### 聊天配置

您可以自定义 AI 助手在聊天中的表现：

```yaml
chat:
  # AI 在游戏中的显示名称
  ai_name: "AI助手"
  
  # AI 消息前缀
  ai_prefix: "[AI] "
  
  # 是否启用上下文 (发送最近的聊天记录)
  enable_context: true
  
  # 上下文消息数量 (最多发送多少条历史消息)
  context_messages: 10
```

### 系统提示词

您可以自定义 AI 的系统提示词：

```yaml
# 系统提示词
system_prompt: |
  你是一个 Minecraft 服务器的 AI 助手。你需要：
  1. 用友好、有帮助的语气回答玩家的问题
  2. 专注于 Minecraft 相关的内容，包括游戏机制、建筑技巧、红石电路等
  3. 如果玩家问非 Minecraft 相关问题，也可以适当回答，但要保持简洁
  4. 回答要简洁明了，适合在游戏聊天中显示
  5. 使用中文回答
  6. 不要在回答中包含过多的格式化字符
```

## 使用方法

玩家可以在游戏聊天中通过 `@ai` 来调用 AI 助手，例如：

- `怎么合成木棍 @ai`
- `@ai 中式建筑中的椽子在什么位置`
- `问下 @ai 这个怎么办`

AI 助手会以 AI 的身份在聊天栏回复，就像一个玩家一样（所有人可见）。

## RedisChat 兼容性

本插件兼容 RedisChat 聊天插件。如果您的服务器安装了 RedisChat，插件将自动检测并启用兼容模式。

## 权限

默认情况下，所有玩家都可以使用 AI 助手。如果需要限制使用，可以在配置文件中修改：

```yaml
permissions:
  # 是否所有玩家都可以使用 AI 助手
  allow_all_players: false
  
  # 如果不允许所有玩家使用，需要的权限节点
  required_permission: "mcaiassistant.use"
```

然后使用权限插件为特定玩家或组分配 `mcaiassistant.use` 权限。
