# MC AI Assistant v0.1.3 发布说明

## 📅 发布信息
- **版本**: v0.1.3
- **发布日期**: 2025-11-29
- **基于版本**: v0.1.1
- **主要更新**: 重构知识库功能 - 本地化与智能检索

## 🎉 核心变革：本地知识库系统

v0.1.3 版本对知识库功能进行了全面重构，从依赖外部 API 的在线知识库转变为完全本地化的文件系统知识库，并提供 AI 智能搜索与直接检索双引擎。

## 🚀 主要新功能

### 1. 本地知识库管理

#### 核心特性
- **📁 文件系统存储**: 知识库以 Markdown 文件形式存储在本地
- **🔄 热更新**: 自动检测文件变化并实时更新缓存
- **💾 智能缓存**: MD5 哈希验证，仅在内容变化时更新
- **📊 元数据管理**: 完整的文件映射与版本追踪

#### 目录结构
```
plugins/McAiAssistant/
├── knowledge-base/          # 知识库 Markdown 文件目录
│   ├── tutorial/           # 教程分类
│   ├── rules/              # 规则文档
│   └── guides/             # 指南文档
├── knowledge-cache/         # 缓存目录
│   ├── *.txt               # 单个文档缓存
│   └── knowledge-base.txt  # 合并后的知识库
└── knowledge-cache.yml      # 缓存元数据映射
```

### 2. 双引擎检索系统

#### AI 智能搜索（默认启用）
- **🤖 AI 理解**: 使用 Gemini API 进行语义理解
- **📝 上下文分析**: 基于完整知识库内容生成答案
- **🎯 智能摘要**: 自动提取相关信息并生成回答
- **⚡ 异步处理**: 不阻塞主线程，提升响应速度

#### 直接检索（默认启用）
- **🔍 关键词匹配**: 快速定位包含查询词的文档片段
- **📍 上下文提取**: 自动获取匹配位置前后文本
- **📚 多结果支持**: 返回多个相关文档片段
- **💨 极速响应**: 本地检索，毫秒级响应

### 3. 配置系统重构

#### 新配置结构
```yaml
knowledge:
  # 基础配置
  enabled: true
  folder_name: "knowledge-base"
  cache_dir_name: "knowledge-cache"
  mapping_file_name: "knowledge-cache.yml"
  
  # 内容描述（用于 AI 判断查询时机）
  content: "Minecraft 服务器规则、插件使用说明、建筑教程等"
  
  # 性能配置
  refresh_interval_ticks: 1200  # 热更新间隔（60秒）
  combined_char_limit: 100000   # 合并文本最大字符数
  
  # AI 搜索配置
  ai_search:
    enabled: true
    api_url: "https://generativelanguage.googleapis.com"
    api_key: "your-gemini-api-key"
    model: "gemini-2.0-flash-exp"
    timeout_seconds: 10
  
  # 直接检索配置
  direct_search:
    enabled: true
    max_snippets: 3
    context_radius: 150
```

### 4. 热更新机制

- **自动扫描**: 定时检测 knowledge-base 目录变化
- **增量更新**: 仅处理新增或修改的文件
- **哈希验证**: 使用 MD5 确保文件一致性
- **无需重启**: 修改 Markdown 文件后自动生效

## 🛠️ 技术架构升级

### 核心组件

#### 1. KnowledgeBaseManager
- **职责**: 知识库扫描、缓存管理、检索调度
- **特性**:
  - 线程安全的读写锁机制
  - 专用线程池处理搜索请求
  - 异步任务调度与超时控制

#### 2. 缓存系统
- **文件缓存**: 每个 Markdown 文档对应一个缓存文件
- **合并缓存**: 所有文档合并为单一文本用于 AI 搜索
- **元数据映射**: YAML 格式记录文件哈希与路径关系

#### 3. 检索引擎
- **AI 引擎**: 调用 Gemini API 进行智能问答
- **直接引擎**: 基于字符串匹配的本地检索
- **并行处理**: 两个引擎异步并行执行

### 工作流程

```
用户查询 "@ai 怎么做红石电路"
    ↓
AI 判断需要查询知识库
    ↓
生成 <query_knowledge query="红石电路制作教程" />
    ↓
插件解析标签，提取查询词
    ↓
并行执行：
    ├─ AI 搜索引擎（Gemini API）
    │   └─ 基于完整知识库生成答案
    └─ 直接检索引擎（本地）
        └─ 匹配关键词并提取片段
    ↓
合并结果并返回给用户
```

## 📊 性能优化

### 响应速度
- **AI 搜索**: 5-10 秒（取决于 API 延迟）
- **直接检索**: <100 毫秒
- **缓存命中**: 即时响应

### 资源使用
- **内存占用**: 根据知识库大小动态调整
- **磁盘 I/O**: 仅在文件变化时写入
- **网络请求**: 仅 AI 搜索时调用外部 API

### 并发处理
- **专用线程池**: 2 个线程处理搜索请求
- **非阻塞设计**: 不影响游戏主线程
- **超时保护**: 防止长时间等待

## ⚙️ 配置指南

### 基础配置

#### 1. 启用本地知识库
```yaml
knowledge:
  enabled: true
  folder_name: "knowledge-base"
  content: "服务器规则、插件教程、建筑指南"
```

#### 2. 配置 AI 搜索（可选）
```yaml
knowledge:
  ai_search:
    enabled: true
    api_url: "https://generativelanguage.googleapis.com"
    api_key: "your-gemini-api-key"  # 从 Google AI Studio 获取
    model: "gemini-2.0-flash-exp"
    timeout_seconds: 10
```

#### 3. 配置直接检索（推荐启用）
```yaml
knowledge:
  direct_search:
    enabled: true
    max_snippets: 3        # 最多返回 3 个片段
    context_radius: 150    # 每个片段前后各 150 字符
```

### 高级配置

#### 性能调优
```yaml
knowledge:
  # 热更新频率（1200 ticks = 60 秒）
  refresh_interval_ticks: 1200
  
  # 合并文本大小限制（防止 API 请求过大）
  combined_char_limit: 100000
  
  # AI 搜索超时时间
  ai_search:
    timeout_seconds: 10
```

#### 调试模式
```yaml
features:
  debug_mode: true  # 查看详细的知识库日志
```

## 📁 知识库管理

### 添加文档

1. 在 `plugins/McAiAssistant/knowledge-base/` 目录下创建 Markdown 文件
2. 支持子目录分类组织
3. 保存后自动检测并更新缓存（无需重启）

#### 示例目录结构
```
knowledge-base/
├── rules/
│   ├── server-rules.md
│   └── chat-rules.md
├── tutorials/
│   ├── building/
│   │   ├── chinese-architecture.md
│   │   └── redstone-basics.md
│   └── plugins/
│       ├── worldedit.md
│       └── plots.md
└── guides/
    └── getting-started.md
```

### 文档格式建议

```markdown
# 文档标题

## 概述
简要说明文档内容...

## 详细内容
### 小节 1
内容...

### 小节 2
内容...

## 示例
实际使用示例...
```

### 维护最佳实践

1. **分类清晰**: 使用子目录组织不同类型的文档
2. **命名规范**: 使用有意义的文件名（如 `redstone-tutorial.md`）
3. **内容简洁**: 保持每个文档专注于单一主题
4. **定期更新**: 及时更新过时的信息

## 🔄 从 v0.1.1 升级

### 配置迁移

#### 移除的配置项
```yaml
# v0.1.1（已废弃）
knowledge:
  api_url: "https://api.dify.ai"
  api_key: "app-xxx"
```

#### 新增的配置项
```yaml
# v0.1.3（新配置）
knowledge:
  enabled: true
  folder_name: "knowledge-base"
  cache_dir_name: "knowledge-cache"
  mapping_file_name: "knowledge-cache.yml"
  
  ai_search:
    enabled: true
    api_url: "https://generativelanguage.googleapis.com"
    api_key: "your-gemini-api-key"
    model: "gemini-2.0-flash-exp"
    timeout_seconds: 10
  
  direct_search:
    enabled: true
    max_snippets: 3
    context_radius: 150
  
  refresh_interval_ticks: 1200
  combined_char_limit: 100000
```

### 升级步骤

1. **备份配置**: 备份现有的 `config.yml` 文件
2. **更新插件**: 替换 jar 文件为 `mc-ai-assistant-0.1.3.jar`
3. **更新配置**: 参考新配置格式更新 `config.yml`
4. **创建知识库**: 在 `plugins/McAiAssistant/` 下创建 `knowledge-base` 目录
5. **添加文档**: 将知识库文档（Markdown 格式）放入该目录
6. **重启服务器**: 重启服务器或重载插件
7. **验证功能**: 测试知识库查询是否正常工作

### 数据迁移

如果之前使用 Dify 知识库，可以：
1. 从 Dify 导出文档内容
2. 转换为 Markdown 格式
3. 放入本地 `knowledge-base` 目录

## 🔍 调试与故障排除

### 启用调试日志
```yaml
features:
  debug_mode: true
```

### 常见日志信息

#### 正常运行
```
[INFO] [知识库] 已加载文档数量: 5
[INFO] [知识库查询] 检测到知识库查询请求，query: 红石电路
[INFO] [知识库] AI 搜索返回结果长度: 234
[INFO] [知识库] 直接检索返回 2 个片段
```

#### 问题诊断
```
[WARNING] [知识库] 本地 knowledge base 为空，跳过此次检索
[WARNING] [知识库] AI 搜索返回异常: 401 Unauthorized
[WARNING] [知识库] 合并后的文本过长，已限制为 100000 字符
```

### 常见问题

#### 1. 知识库查询无响应
- 检查 `knowledge.enabled: true`
- 确认 `knowledge-base` 目录存在且包含 `.md` 文件
- 查看调试日志确认文档是否加载成功

#### 2. AI 搜索失败
- 验证 `knowledge.ai_search.api_key` 是否正确
- 检查网络连接到 Google AI API
- 确认 API 配额是否充足

#### 3. 文档未自动更新
- 检查 `refresh_interval_ticks` 配置
- 确认文件修改时间已更新
- 手动重载插件触发更新

#### 4. 缓存文件过多
- 缓存文件会自动管理，无需手动清理
- 如需清理，删除 `knowledge-cache` 目录后重启

## 🎯 使用示例

### 场景 1: 查询服务器规则
```
玩家: @ai 服务器有哪些规则？
AI: 让我查询一下服务器规则...

<query_knowledge query="服务器规则" />

[AI 搜索结果] 根据知识库，服务器主要规则包括：
1. 禁止使用作弊器...
2. 尊重其他玩家...

[直接检索片段]
📄 rules/server-rules.md:
"...服务器规则：1. 禁止使用任何作弊器..."
```

### 场景 2: 查询插件教程
```
玩家: @ai WorldEdit 怎么用？
AI: <query_knowledge query="WorldEdit 插件使用教程" />

[AI 搜索结果] WorldEdit 是一个强大的地形编辑插件...

[直接检索片段]
📄 tutorials/plugins/worldedit.md:
"...WorldEdit 基础命令：//wand 获取选择工具..."
```

### 场景 3: 不触发知识库查询
```
玩家: @ai 你好
AI: 你好！我是 AI 助手，有什么可以帮助你的吗？

[不会触发知识库查询，直接回答]
```

## 📈 性能对比

| 指标 | v0.1.1（Dify） | v0.1.3（本地） | 改进 |
|------|----------------|----------------|------|
| 知识库响应时间 | 3-8 秒 | 5-10 秒（AI）/ <0.1 秒（直接） | 提供快速选项 |
| 部署复杂度 | 需要外部服务 | 完全本地化 | 大幅简化 |
| 维护成本 | 高（需维护 Dify） | 低（仅管理文件） | 显著降低 |
| 知识库更新 | 手动更新 API | 自动热更新 | 体验提升 |
| 依赖服务 | Dify API | Gemini API（可选） | 更灵活 |
| 离线可用 | ✗ | ✓（仅直接检索） | 支持离线 |

## 🔮 向后兼容

### 完全兼容
- ✅ 所有聊天功能
- ✅ 权限系统
- ✅ 图像生成
- ✅ 模型切换
- ✅ 调试模式

### 不兼容变更
- ❌ Dify 知识库 API 配置（已移除）
- ❌ `knowledge.api_url` 和 `knowledge.api_key`（改为 `ai_search` 下的配置）

## 🚀 部署说明

### 1. 编译
```bash
mvn clean package -DskipTests
```

### 2. 安装
- 将 `target/mc-ai-assistant-0.1.3.jar` 复制到服务器 `plugins` 目录

### 3. 配置
- 编辑 `plugins/McAiAssistant/config.yml`
- 参考上述配置示例进行设置

### 4. 准备知识库
- 创建 `plugins/McAiAssistant/knowledge-base/` 目录
- 添加 Markdown 文档

### 5. 启动
- 重启服务器或重载插件
- 检查日志确认知识库加载成功

### 6. 测试
- 使用 `@ai 测试问题` 测试知识库查询
- 检查调试日志确认功能正常

## 🎯 最佳实践

### 知识库组织

1. **按主题分类**: 使用子目录组织不同主题
2. **清晰命名**: 文件名应反映内容
3. **保持更新**: 定期检查和更新文档
4. **控制大小**: 单个文档不宜过长（建议 < 10KB）

### 性能优化

1. **合理配置刷新间隔**: 根据更新频率调整 `refresh_interval_ticks`
2. **控制知识库大小**: 避免超过 `combined_char_limit` 限制
3. **使用直接检索**: 快速问题优先使用直接检索
4. **启用 AI 搜索**: 复杂问题使用 AI 理解

### 安全建议

1. **保护 API 密钥**: 不要在公开仓库中暴露 Gemini API 密钥
2. **定期审查文档**: 确保知识库内容准确安全
3. **监控使用情况**: 定期检查 API 调用次数和费用

## 🎉 总结

v0.1.3 版本通过将知识库本地化，实现了：

1. **🎯 简化部署** - 无需依赖外部知识库服务
2. **⚡ 灵活检索** - AI 智能搜索 + 快速直接检索
3. **🔄 自动更新** - 文件修改自动生效，无需重启
4. **💾 智能缓存** - 高效的缓存与热更新机制
5. **🛠️ 易于维护** - 直接编辑 Markdown 文件管理知识库

这是知识库功能的重大升级，为用户提供了更强大、更灵活的本地知识管理能力！

---

**MC AI Assistant v0.1.3** - 本地知识库，智能检索，随时更新！