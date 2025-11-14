# MC AI Assistant - 服务器 AI 助手

用于 PaperMC 1.21.4 的 AI 聊天助手插件

## 功能特性

- 🤖 **智能聊天**: 在游戏中通过 @ai 调用 AI 助手
- 🧠 **上下文支持**: 自动发送最近 10 条聊天记录作为上下文
- 🎯 **智能识别**: 精确匹配 @ai 标记，不会误触发 @airport 等词汇
- 🔌 **插件兼容**: 完美兼容 [RedisChat 聊天插件](https://github.com/Emibergo02/RedisChat)
- ⚙️ **高度可配置**: 支持自定义 API、模型、提示词等
- 🔒 **权限管理**: 支持权限控制，可设置所有人可用或特定权限

## 使用方式

不需要特殊的指令，只需要像和其他人在游戏里面聊天一样，在聊天中 @ai

### 使用示例

```
怎么合成木棍 @ai
@ai 中式建筑中的椽子在什么位置
问下 @ai 这个怎么办
@ai 红石电路怎么做
```

AI 会以助手的身份在聊天栏回复，就像一个玩家一样（所有人可见）

## 调试模式

如果插件无法正常识别 @ai 触发词，可以启用调试模式来排查问题。

### 启用调试模式

1. 编辑 `plugins/McAiAssistant/config.yml` 文件
2. 找到 `features` 部分，将 `debug_mode` 设置为 `true`：
   ```yaml
   features:
     debug_mode: true
   ```
3. 重启服务器或使用 `/reload` 命令重载插件

### 调试日志说明

启用调试模式后，服务器控制台会显示详细的调试信息：

#### 插件加载时的日志
```
[INFO]: 检测到 RedisChat 插件，版本: 5.4.9
[INFO]: 尝试加载 RedisChat 事件类: dev.unnm3d.redischat.api.events.RedisChatMessageEvent
[INFO]: RedisChat 兼容模式已启用 (使用类: dev.unnm3d.redischat.api.events.RedisChatMessageEvent)
```

#### 聊天消息检测日志
```
[INFO]: 检测到玩家聊天消息: PlayerName -> hi @ai
[INFO]: 玩家聊天消息包含 AI 触发词: hi @ai
[INFO]: 处理 AI 请求: PlayerName -> hi
```

或者对于 RedisChat：
```
[INFO]: 检测到 RedisChat 消息: PlayerName -> hi @ai
[INFO]: RedisChat 消息包含 AI 触发词: hi @ai
[INFO]: 处理 RedisChat AI 请求: PlayerName -> hi
```

### 常见问题排查

#### 1. 没有检测到聊天消息
如果控制台没有显示 "检测到玩家聊天消息" 或 "检测到 RedisChat 消息"，说明：
- 可能其他聊天插件阻止了事件传播
- 事件优先级设置有问题
- 插件加载顺序有问题

#### 2. 检测到消息但没有触发 AI
如果看到消息检测日志但没有 "包含 AI 触发词" 的日志，说明：
- 消息格式可能被其他插件修改
- 触发词配置有问题
- 智能匹配模式设置有问题

#### 3. RedisChat 兼容性问题
如果看到以下错误日志：
```
[WARNING]: 无法加载 RedisChat 事件类，可能是版本不兼容
[WARNING]: 无法找到兼容的 RedisChat 事件类
```
说明 RedisChat 版本与插件不兼容，请联系开发者更新兼容性代码。

### 关闭调试模式

排查完问题后，建议关闭调试模式以减少日志输出：
1. 将 `debug_mode` 设置为 `false`
2. 重启服务器或重载插件

## 安装说明

### 环境要求

- Java 21
- PaperMC 1.21.4 服务器
- Maven 3.8.7 或更高版本（仅构建时需要）

### 构建插件

1. 克隆或下载项目代码
2. 在项目根目录运行构建脚本：
   ```bash
   # Windows
   build.bat

   # 或直接使用 Maven
   mvn clean package
   ```
3. 构建成功后，插件文件位于 `target/mc-ai-assistant-1.0.0.jar`

### 安装插件

1. 将构建好的 JAR 文件复制到 PaperMC 服务器的 `plugins` 目录
2. 重启服务器或使用插件管理器加载插件
3. 首次加载后，插件将在 `plugins/McAiAssistant` 目录下生成配置文件
4. 编辑 `config.yml` 文件，设置您的 API 密钥和其他配置

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

  # 网络搜索模型名称 (用于 @search 触发词)
  search_model: "gpt-4o-search-preview"

  # 最大 token 数量
  max_tokens: 1000

  # 温度参数 (0.0-2.0)
  temperature: 0.7

  # 请求超时时间 (秒)
  timeout: 30

  # 是否模拟浏览器请求头 (提高兼容性，避免被识别为机器人)
  simulate_browser: true
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

### 功能配置

插件提供了多种功能开关：

```yaml
features:
  # 是否启用 RedisChat 兼容模式
  redis_chat_compatibility: true

  # 是否记录聊天日志
  enable_chat_logging: true

  # 是否启用调试模式（排查问题时启用）
  debug_mode: false

# UI 通知配置
notifications:
  # 是否显示 Toast 通知
  enable_toast: true

  # 是否在聊天栏显示 AI 处理状态
  enable_chat_status: true

  # AI 处理中的提示消息
  processing_message: "🤖 AI 正在处理您的请求，请稍候..."
```

#### 重要配置说明

- **debug_mode（调试模式）**
  - **默认值**: `false`
  - **作用**: 启用后会在服务器控制台输出详细的调试信息
  - **何时启用**: 当插件无法正常识别 @ai 触发词时，启用此模式进行问题排查
  - **注意**: 排查完问题后建议关闭，避免产生过多日志

- **redis_chat_compatibility（RedisChat 兼容）**
  - **默认值**: `true`
  - **作用**: 自动检测并兼容 RedisChat 插件的聊天事件
  - **注意**: 如果服务器没有安装 RedisChat，此选项不会产生任何影响

#### API 请求配置说明

- **simulate_browser（浏览器模拟）**
  - **默认值**: `true`
  - **作用**: 模拟浏览器请求头，提高 API 请求的成功率
  - **包含的请求头**:
    - `User-Agent`: 模拟 Chrome 浏览器
    - `Accept`: 指定接受的内容类型
    - `Accept-Language`: 设置语言偏好
    - `Accept-Encoding`: 支持压缩
    - `Sec-Ch-Ua`: Chrome 客户端提示
    - `DNT`: 请勿跟踪标头
  - **建议**: 保持启用，除非遇到特定的 API 兼容性问题

#### UI 通知配置说明

- **enable_toast（Toast 通知）**
  - **默认值**: `true`
  - **作用**: 是否显示 Toast 通知（Action Bar 提示，立即显示）
  - **显示方式**: 在屏幕上方显示 Action Bar 消息，持续 3 秒
  - **建议**: 如果觉得 Toast 通知干扰游戏体验，可以关闭

- **enable_chat_status（聊天状态提示）**
  - **默认值**: `true`
  - **作用**: 是否在聊天栏显示 "AI 正在处理请求" 的提示消息
  - **显示时机**: 延迟 0.3 秒发送，避免与 Toast 通知冲突
  - **建议**: 建议保持开启，让玩家知道 AI 正在处理

- **processing_message（处理提示消息）**
  - **默认值**: `"🤖 AI 正在处理您的请求，请稍候..."`
  - **作用**: 自定义 AI 处理时显示的提示消息
  - **支持**: 支持颜色代码和 Emoji 表情
  - **延迟**: 此消息会延迟 0.3 秒发送，确保良好的用户体验

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
```

## 权限配置

默认情况下，所有玩家都可以使用 AI 助手。如果需要限制使用，可以在配置文件中修改：

```yaml
permissions:
  # 是否所有玩家都可以使用 AI 助手
  allow_all_players: false

  # 如果不允许所有玩家使用，需要的权限节点
  required_permission: "mcaiassistant.use"
```

然后使用权限插件为特定玩家或组分配 `mcaiassistant.use` 权限。

## 配置示例

### 通知配置示例

#### 1. 关闭所有通知（静默模式）
```yaml
notifications:
  enable_toast: false
  enable_chat_status: false
```

#### 2. 仅显示 Toast 通知
```yaml
notifications:
  enable_toast: true
  enable_chat_status: false
```

#### 3. 仅显示聊天状态提示
```yaml
notifications:
  enable_toast: false
  enable_chat_status: true
  processing_message: "💭 AI 助手正在为您准备答案..."
```

#### 4. 自定义处理消息（支持颜色代码）
```yaml
notifications:
  enable_chat_status: true
  processing_message: "&e🤖 &aAI 正在处理您的请求，请稍候..."
```

**颜色代码说明：**
- `&a` = 绿色, `&e` = 黄色, `&c` = 红色, `&b` = 青色
- `&l` = 粗体, `&o` = 斜体, `&n` = 下划线, `&r` = 重置

#### 5. 完全自定义的通知体验
```yaml
notifications:
  enable_toast: true
  enable_chat_status: true
  processing_message: "&l&6[&e⚡&6] &f正在召唤 AI 精灵... &7请稍候"
```

## 故障排除

### 插件无法识别 @ai 触发词

如果在聊天中使用 @ai 没有任何反应，请按以下步骤排查：

#### 1. 启用调试模式

编辑 `plugins/McAiAssistant/config.yml`，将 `debug_mode` 设置为 `true`：

```yaml
features:
  debug_mode: true
```

重启服务器或重载插件后，在游戏中发送包含 @ai 的消息，然后查看服务器控制台。

#### 2. 检查调试日志

**正常情况下应该看到：**
```
[INFO]: 检测到玩家聊天消息: PlayerName -> hi @ai
[INFO]: 玩家聊天消息包含 AI 触发词: hi @ai
[INFO]: 处理 AI 请求: PlayerName -> hi
```

**如果使用 RedisChat，应该看到：**
```
[INFO]: 检测到 RedisChat 插件，版本: 5.4.9
[INFO]: RedisChat 兼容模式已启用
[INFO]: 检测到 RedisChat 消息: PlayerName -> hi @ai
[INFO]: RedisChat 消息包含 AI 触发词: hi @ai
```

#### 3. 常见问题及解决方案

**问题：没有检测到任何聊天消息**
- 可能其他聊天插件阻止了事件传播
- 检查插件加载顺序，确保 McAiAssistant 在聊天插件之后加载

**问题：检测到消息但没有触发 AI**
- 检查消息格式是否被其他插件修改
- 确认 `smart_matching` 配置是否正确
- 尝试不同的触发词格式

**问题：RedisChat 兼容性错误**
```
[WARNING]: 无法找到兼容的 RedisChat 事件类
```
- RedisChat 版本可能不兼容
- 请联系开发者更新兼容性代码
- 或临时禁用 RedisChat 兼容模式

#### 4. 获取帮助

如果问题仍然存在，请提供以下信息：
- 服务器版本（Paper/Spigot 版本）
- 插件列表
- 完整的服务器启动日志
- 调试模式下的相关日志

### API 相关问题

**问题：AI 响应 "抱歉，AI 助手暂时无法响应"**
- 检查 API 密钥是否正确
- 确认 API URL 是否可访问
- 查看服务器日志中的详细错误信息

**问题：响应速度慢**
- 检查网络连接
- 调整 `timeout` 配置
- 考虑使用更快的 API 服务

### 性能优化

- 适当调整 `context_messages` 数量
- 在高负载服务器上考虑禁用 `enable_chat_logging`
- 生产环境中关闭 `debug_mode`

## 模型切换与 /model 指令

通过一个简单的指令即可在运行时切换聊天使用的 AI 模型。默认模型仍取自配置文件 `ai.model`，未设置覆盖时生效。

### 快速使用

- 查看当前“有效模型”（及来源：覆盖/配置）
  ```
  /model
  ```
- 切换到指定模型
  ```
  /model <modelId>
  ```
  示例：
  ```
  /model gpt-4o-mini
  /model o4-mini
  /model llama-3.1-8b-instruct
  ```

说明：
- 切换为“运行时覆盖”，仅影响聊天用模型；
- 重载或重启后将回到配置默认模型（不写回配置）；

### 自动补全（动态模型列表）

- 输入 `/model ` 后，Tab 自动补全候选来自 OpenAI 风格的 `/models` 接口；
- 列表缓存默认 10 分钟，首次或过期会异步刷新，不阻塞主线程；
- 如果 `/models` 接口不可用或返回不全，仍允许设置任意 `modelId`，并在聊天中提示“未在列表中发现”。

### 权限

- 默认需要 `mcaiassistant.admin` 才能使用 `/model`；
- 可在 `plugin.yml` 中调整命令权限或用权限插件授予权限。

### 覆盖策略与回退

- 仅对“聊天用模型”生效，搜索模型仍按配置项工作；
- 当前“有效模型”优先级：运行时覆盖 &gt; 配置默认；
- 使用 `/model` 空参可查看当前有效模型与来源。

### 实现参考

- 指令与补全：[src/main/java/com/mcaiassistant/mcaiassistant/ModelCommand.java](src/main/java/com/mcaiassistant/mcaiassistant/ModelCommand.java)
- 模型覆盖与 `/models` 缓存：[src/main/java/com/mcaiassistant/mcaiassistant/ModelManager.java](src/main/java/com/mcaiassistant/mcaiassistant/ModelManager.java)
- 请求体使用覆盖模型（聊天场景）：[src/main/java/com/mcaiassistant/mcaiassistant/AiApiClient.java](src/main/java/com/mcaiassistant/mcaiassistant/AiApiClient.java)
- 插件启动注册与配置重载：[src/main/java/com/mcaiassistant/mcaiassistant/McAiAssistant.java](src/main/java/com/mcaiassistant/mcaiassistant/McAiAssistant.java)
- 命令声明与权限：[src/main/resources/plugin.yml](src/main/resources/plugin.yml)