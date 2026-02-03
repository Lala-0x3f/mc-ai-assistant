# Feat: AI 驱动的全局记忆标注与后台判定

## 背景
- 目的：让 AI 自主决定“何时写入/更新全局记忆”，减少人工干预与无效记忆。
- 范围：`ChatListener`、`GlobalMemoryManager`、`McAiAssistant`，配置示例 `config-example.yml`。
- 备注：这是功能更新说明，非版本发布。

## 变更摘要
- 新增 mark 占位触发：AI 回复可插入 `<mark />` / `<mark></mark>`，展示前移除，后台检测后触发记忆判定。
- 后台判定：将用户消息、AI 回复、近期上下文、相关已有记忆打包给 AI，请求产出 `ADD/UPDATE` 摘要；仅摘要非空时写入。
- 记忆写入：使用 `captureBySummaryAsync` 直接写/更新，仍保留去重、容量、截断保护。
- 关键词配置移除：`global_memory.keywords` 被清理，示例配置同步删除。

## 工作流程（简版）
1. 玩家触发 @ai，AI 生成回复（可含 `<mark />` 占位）。
2. 展示前清理遗留工具标签（兼容旧格式）；玩家看到纯文本。
3. 若检测到 mark：
   - 收集上下文（最近聊天）、现有相关记忆、用户消息、AI 回复。
   - 发送给 AI 判定器，要求输出 `ADD: <摘要>` / `UPDATE: <摘要>` 或 `NONE`。
   - 对返回摘要调用 `GlobalMemoryManager.captureBySummaryAsync`，执行去重/合并/容量限制后落盘 `global-memory.yml`。
4. 后续请求时，仍按相关性选取记忆片段（最多 3 条、总 320 字）注入上下文。

## 配置与默认值
- `global_memory.enabled`: true（如需停用可设 false）
- `min_length`: 12；`min_info_score`: 2
- `max_entries`: 50；`max_summary_length`: 200
- `max_inject_entries`: 3；`max_inject_chars`: 320
- `global_memory.keywords`: 已移除（不再使用）

## 兼容性与风险
- 需要模型支持 mark 占位与后台判定提示；判定失败时忽略写入，不影响主回复。
- 磁盘写入仍按每条记忆落盘，极高频场景可视情况关闭 `enabled` 或调高阈值。
- RedisChat 路径也受新判定流程影响（同一检测机制）。

## 主要代码位置
- `src/main/java/com/mcaiassistant/mcaiassistant/ChatListener.java`
- `src/main/java/com/mcaiassistant/mcaiassistant/GlobalMemoryManager.java`
- `src/main/java/com/mcaiassistant/mcaiassistant/McAiAssistant.java`
- `config-example.yml`（示例配置更新）

## 使用指引
1. 模型回复中使用 `<mark />` 作为“需长期记忆判定”占位（内容不写在标签内）。
2. 确认 `global_memory.enabled: true`（或按需关闭）。
3. 根据需要调整 `min_length`/`min_info_score`/`max_entries` 等阈值；关键词无需配置。
4. 构建并部署：`mvn clean package`，替换插件 Jar，重载插件。

## 验证
- 编译：`mvn clean package`（已通过）
- 交互：发送包含 mark 的 AI 回复，日志应显示后台判定，符合条件时写入/更新 `global-memory.yml`，不影响玩家可见消息。
