# MC AI Assistant 功能更新总结

## 🎉 新增功能

### 1. 🚦 **发送速率限制**
- **功能**: 限制每分钟最多 AI 响应次数，防止滥用
- **配置**:
  ```yaml
  rate_limit:
    enabled: true                    # 是否启用速率限制
    max_requests_per_minute: 10      # 每分钟最多请求次数
    limit_message: "⚠️ 您的请求过于频繁，请稍后再试。每分钟最多 {limit} 次请求。"
  ```
- **特性**:
  - 按玩家独立计算限制
  - 自动清理过期记录
  - 可自定义限制提示消息
  - 支持 `{limit}` 占位符显示限制数量

### 2. 🔍 **网络搜索功能**
- **功能**: 识别 `@search` 触发词，使用专门的搜索模型
- **配置**:
  ```yaml
  ai:
    search_model: "gpt-4o-search-preview"  # 搜索专用模型
  
  chat:
    search_keywords:                       # 搜索触发词
      - "@search"
      - "@搜索"
  ```
- **特性**:
  - 优先级高于普通 AI 触发词
  - 使用专门的搜索模型
  - 支持智能匹配模式
  - 搜索响应带有 🔍 图标标识

### 3. 📊 **服务器信息增强**
- **功能**: 在 API 请求中自动包含服务器状态信息
- **包含信息**:
  - 当前时间 (yyyy-MM-dd HH:mm:ss)
  - 在线玩家数量
  - 在线玩家列表 (最多显示10个)
- **示例**:
  ```
  当前时间: 2024-01-15 14:30:25
  当前在线玩家数量: 5
  在线玩家: Player1, Player2, Player3, Player4, Player5
  
  用户问题: 怎么合成钻石剑？
  ```

### 4. 🌐 **浏览器请求头模拟**
- **功能**: 模拟真实浏览器请求，提高 API 兼容性
- **配置**:
  ```yaml
  ai:
    simulate_browser: true  # 是否模拟浏览器请求头
  ```
- **包含的请求头**:
  - `User-Agent`: Chrome 120 浏览器标识
  - `Accept`: 内容类型偏好
  - `Accept-Language`: 中文优先语言设置
  - `Accept-Encoding`: 支持 gzip/deflate/br 压缩
  - `Sec-Ch-Ua`: Chrome 客户端提示
  - `DNT`: 请勿跟踪
  - `Origin` / `Referer`: 来源信息
- **优势**:
  - 避免被识别为机器人请求
  - 提高请求成功率
  - 更好的 API 兼容性

## 🔧 **改进功能**

### 1. **Toast 通知修复**
- 修复了 Toast 通知不显示的问题
- 使用 Action Bar 实现持续 3 秒的通知
- 支持配置开关控制

### 2. **处理消息延迟**
- `processing_message` 现在延迟 0.3 秒发送
- Toast 通知立即显示
- 避免通知冲突，提供更好的用户体验

### 3. **触发词优先级**
- 搜索触发词 (`@search`) 优先级高于 AI 触发词 (`@ai`)
- 避免触发词冲突
- 支持智能匹配模式

## 📋 **配置文件完整示例**

```yaml
# AI API 配置
ai:
  api_key: "your-api-key-here"
  api_url: "https://api.openai.com/v1"
  model: "gpt-3.5-turbo"
  search_model: "gpt-4o-search-preview"
  max_tokens: 1000
  temperature: 0.7
  timeout: 30
  simulate_browser: true

# 聊天配置
chat:
  ai_name: "AI助手"
  ai_prefix: "[AI] "
  enable_context: true
  context_messages: 10
  trigger_keywords:
    - "@ai"
    - "@AI"
  search_keywords:
    - "@search"
    - "@搜索"
  smart_matching: true

# 功能配置
features:
  redis_chat_compatibility: true
  enable_chat_logging: true
  debug_mode: false

# 速率限制配置
rate_limit:
  enabled: true
  max_requests_per_minute: 10
  limit_message: "⚠️ 您的请求过于频繁，请稍后再试。每分钟最多 {limit} 次请求。"

# UI 通知配置
notifications:
  enable_toast: true
  enable_chat_status: true
  processing_message: "🤖 AI 正在处理您的请求，请稍候..."

# 权限配置
permissions:
  allow_all_players: true
  required_permission: "mcaiassistant.use"
```

## 🚀 **使用示例**

### 普通 AI 对话
```
玩家: 怎么合成钻石剑 @ai
AI助手: 钻石剑的合成配方是...
```

### 网络搜索
```
玩家: 最新的 Minecraft 更新内容 @search
🔍 AI助手: 根据最新信息，Minecraft 1.21.4 更新包含...
```

### 速率限制触发
```
玩家: 连续发送多个请求...
系统: ⚠️ 您的请求过于频繁，请稍后再试。每分钟最多 10 次请求。
```

## 📝 **技术实现**

- **RateLimitManager**: 管理玩家请求频率
- **浏览器模拟**: OkHttp 拦截器 + 动态请求头
- **搜索功能**: 独立的触发词检测和模型选择
- **服务器信息**: 动态获取在线玩家和时间信息
- **延迟通知**: Bukkit 调度器实现精确延迟

## 🔄 **向后兼容性**

- 所有新功能都有默认配置
- 现有配置文件无需修改即可使用
- 新功能可以独立开启/关闭
- 保持与现有聊天插件的兼容性
