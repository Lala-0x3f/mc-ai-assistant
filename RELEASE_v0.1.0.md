# 🚀 MC AI Assistant v0.1.0 发布说明

## 📅 发布信息
- **版本**: v0.1.0
- **发布日期**: 2025-07-29
- **基于版本**: v0.0.4
- **重大更新**: 图像生成功能

## 🎨 重大新功能：图像生成

### ✨ 核心特性
- **🤖 智能图像生成**: 通过 `@ai` 命令请求生成图像
- **🔄 自动识别**: AI 自动识别画图请求并处理
- **🌐 英文转换**: 自动将中文描述转换为英文 prompt
- **⚡ 异步处理**: 不阻塞游戏进程的图像生成
- **🎯 固定比例**: 强制 1:1 正方形比例

### 🎮 用户体验
- **📱 实时通知**: Toast 通知显示生成状态
- **🖼️ 多图像支持**: 支持显示 API 返回的所有图像（通常 4 张）
- **🎨 美观显示**: 显示中文 alt 描述，悬停显示英文 prompt
- **🖱️ 可点击链接**: 每张图像都有独立的可点击获取按钮
- **📊 详细信息**: 显示生成用时和图像数量
- **🎮 服务器集成**: 通过 `/image create` 命令获取图像

### 💻 技术实现
- **🔌 API 集成**: 支持标准图像生成 API
- **🛡️ 错误处理**: 完善的错误提示和重试机制
- **⏱️ 超时控制**: 120秒超时防止长时间等待
- **🔍 调试支持**: 详细的调试日志和性能统计

## 🔧 配置更新

### 新增配置项
```yaml
# 图像生成配置
image_generation:
  # 是否启用图像生成功能
  enabled: false
  
  # 图像生成 API 地址
  api_url: "http://localhost:8000"
```

### 系统提示词增强
AI 现在包含图像生成指令：
```
如果玩家要求画图，你要使用 create_image 给玩家生成参考图，每次响应只能使用一次
如果你要生成一张图像，请在响应中添加一行

<create_image prompt="" alt="" />

Prompt 必须是纯英语，否则无法生成，Prompt 不支持任何额外的配置参数
Alt 是图像的中文描述，用于在游戏中显示，应该简洁美观
需要格式完全准确，才能生成图像
示例：<create_image prompt="beautiful sunset over mountains" alt="美丽的山间日落" />
```

## 🛠️ 技术架构更新

### 新增核心组件

#### 1. ImageApiClient.java
- **功能**: 图像生成 API 客户端
- **特性**: 
  - 异步请求处理
  - 完善的错误处理
  - 超时控制（120秒）
  - 调试日志支持

#### 2. 响应处理增强
- **标签识别**: 自动识别 `<create_image prompt="" />` 标签
- **标签移除**: 处理后自动移除标签，保持文本响应清洁
- **异步生成**: 不阻塞主线程的图像生成

#### 3. 用户界面改进
- **Toast 通知**: 生成开始和状态提示
- **可点击组件**: 使用 Adventure API 创建可点击链接
- **悬停提示**: 显示完整命令信息

### 代码结构优化
- **模块化设计**: 图像生成功能独立模块
- **依赖注入**: 清晰的组件依赖关系
- **资源管理**: 自动清理 HTTP 连接

## 📋 使用示例

### 基本使用
```
玩家: @ai 给我画一张美丽的日落图
AI: 我来为您生成一张美丽的日落图像...

[AI] 🎨 图像生成完成！
📝 美丽的山间日落 (悬停显示原始 Prompt)
⏱️ 用时: 3.2秒
🖼️ 生成了 4 张图像
🖱️ 图像 1 -> /image create /images/xxx/xxx_0.jpg
🖱️ 图像 2 -> /image create /images/xxx/xxx_1.jpg
🖱️ 图像 3 -> /image create /images/xxx/xxx_2.jpg
🖱️ 图像 4 -> /image create /images/xxx/xxx_3.jpg
```

### 高级使用
```
玩家: @ai 帮我画一个科幻风格的城市，要有飞行汽车
AI: 我来为您创建一个科幻风格的未来城市场景...

[AI] 🎨 图像生成完成！
📝 科幻未来城市 (悬停显示详细 Prompt)
⏱️ 用时: 5.8秒
🖼️ 生成了 4 张图像
🖱️ 图像 1 -> /image create /images/xxx/xxx_0.jpg
🖱️ 图像 2 -> /image create /images/xxx/xxx_1.jpg
🖱️ 图像 3 -> /image create /images/xxx/xxx_2.jpg
🖱️ 图像 4 -> /image create /images/xxx/xxx_3.jpg
```

## 🔌 API 接口规范

### 请求格式
```http
POST /create
Content-Type: application/json

{
  "prompt": "beautiful sunset over mountains",
  "ar": "1:1"
}
```

### 成功响应
```json
{
  "job_id": "1234567890abcdef",
  "images": [
    "/images/1234567890abcdef/1234567890abcdef_0.jpg",
    "/images/1234567890abcdef/1234567890abcdef_1.jpg"
  ]
}
```

### 错误响应
```json
{
  "detail": "错误描述信息"
}
```

## 🔄 工作流程

1. **请求识别**: 玩家发送包含画图请求的 `@ai` 命令
2. **AI 处理**: AI 识别请求并在响应中添加 `<create_image>` 标签
3. **标签解析**: 插件解析标签并提取英文 prompt
4. **异步生成**: 调用图像生成 API 进行异步处理
5. **状态通知**: 显示生成开始的 Toast 通知
6. **结果处理**: 生成完成后显示可点击的获取链接
7. **图像获取**: 玩家点击链接执行 `/image create` 命令

## 📊 性能优化

### 异步处理
- **非阻塞**: 图像生成不影响游戏性能
- **超时控制**: 防止长时间等待
- **资源管理**: 自动清理连接

### 内存优化
- **流式处理**: 不在内存中缓存图像数据
- **连接池**: 复用 HTTP 连接
- **垃圾回收**: 及时释放临时对象

## 🔍 调试功能

### 启用调试模式
```yaml
debug_mode: true
```

### 调试信息
```
[INFO] [图像生成] 检测到图像生成请求，prompt: beautiful sunset
[INFO] [图像生成] 发送请求到: http://localhost:8000/create
[INFO] [图像生成] 响应状态码: 200
[INFO] [图像生成] 成功生成图像: /images/xxx/xxx_0.jpg，用时: 3200ms
```

## 🛡️ 安全特性

### 输入验证
- **Prompt 清理**: 自动验证和清理 prompt 内容
- **长度限制**: 防止过长的 prompt
- **特殊字符**: 处理特殊字符和转义

### 错误处理
- **异常捕获**: 完善的异常处理机制
- **信息过滤**: 不暴露敏感的系统信息
- **降级处理**: API 失败时的优雅降级

## 📦 文件结构更新

### 新增文件
```
src/main/java/com/mcaiassistant/mcaiassistant/
├── ImageApiClient.java          # 图像生成 API 客户端
└── (其他现有文件...)

文档:
├── IMAGE_GENERATION_FEATURE.md # 图像生成功能详细说明
├── RELEASE_v0.1.0.md           # v0.1.0 发布说明
└── (其他现有文档...)
```

### 更新文件
- `McAiAssistant.java` - 添加 ImageApiClient 初始化
- `ChatListener.java` - 添加图像生成处理逻辑
- `ConfigManager.java` - 添加图像生成配置支持
- `AiApiClient.java` - 添加图像生成系统提示词
- `ToastNotification.java` - 添加 showToast 方法
- `config.yml` - 添加图像生成配置项
- `config-example.yml` - 添加详细配置说明
- `plugin.yml` - 更新版本号到 0.1.0
- `pom.xml` - 更新版本号到 0.1.0

## 🔄 兼容性

### 向后兼容
- ✅ 完全兼容 v0.0.4 的所有功能
- ✅ 现有配置文件无需修改
- ✅ 现有权限系统继续有效

### 系统要求
- **Java**: 21 或更高版本
- **服务器**: PaperMC 1.21.4
- **图像服务**: 兼容的图像生成 API（可选）

## ⚠️ 注意事项

### 1. 功能启用
- 图像生成功能默认**禁用**
- 需要手动设置 `image_generation.enabled: true`
- 需要配置正确的 `api_url`

### 2. 性能影响
- 图像生成为异步处理，不影响游戏性能
- 建议监控 API 服务的响应时间
- 可通过调试模式查看性能统计

### 3. 网络要求
- 需要服务器能访问图像生成 API
- 建议使用内网部署以提高速度
- 确保防火墙允许相关端口

## 🚀 部署说明

### 1. 编译
```bash
mvn clean package -DskipTests
```

### 2. 安装
- 将 `target/mc-ai-assistant-0.1.0.jar` 复制到服务器 `plugins` 目录
- 重启服务器或使用 `/reload` 命令

### 3. 配置
- 编辑 `plugins/McAiAssistant/config.yml`
- 启用图像生成功能：`image_generation.enabled: true`
- 设置 API 地址：`image_generation.api_url: "http://your-api-server:8000"`

### 4. 测试
- 使用 `@ai 给我画一张猫的图` 测试图像生成功能
- 检查日志确认功能正常工作

## 📈 统计信息

- **总文件数**: 25 个（+2 个新文件）
- **代码行数**: 4800+ 行（+600 行新代码）
- **Java 类**: 11 个（+1 个新类）
- **新增功能**: 图像生成完整功能链
- **文档更新**: 2 个新文档

## 🎯 下一步计划

- 🔄 支持更多图像比例选择
- 🔄 批量图像生成功能
- 🔄 图像历史记录管理
- 🔄 自定义样式参数
- 🔄 图像质量选择选项
- 🔄 图像编辑和修改功能

---

**MC AI Assistant v0.1.0** - 现在支持图像生成，让您的 Minecraft 服务器更具创造力！🎨
