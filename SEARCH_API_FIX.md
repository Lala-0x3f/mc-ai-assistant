# 🔧 API 响应解析问题修复

## 🐛 问题描述

AI 助手功能（包括 `@ai` 和 `@search`）出现响应解析问题，主要原因是：

1. **URL 拼接错误**（搜索功能）：代码中将 `configManager.getApiUrl()` 与 `/v1/responses` 直接拼接，导致 URL 变成 `https://api.openai.com/v1/v1/responses`（重复的 `/v1`）
2. **响应解压缩问题**（两个功能都有）：手动设置 `Accept-Encoding: gzip, deflate, br` 后，OkHttp 不会自动解压缩响应，导致收到压缩的二进制数据无法解析为 JSON
3. **API 端点配置**：需要根据实际使用的 API 服务配置正确的基础 URL

**影响范围**：
- `@search` 功能：SearchApiClient.java
- `@ai` 功能：AiApiClient.java

## 🔨 修复内容

### 1. 修复 URL 构建逻辑

在 `SearchApiClient.java` 中添加了智能 URL 构建方法：

### 2. 修复响应解压缩问题

在 **SearchApiClient.java** 和 **AiApiClient.java** 中都移除了手动设置的 `Accept-Encoding` 请求头：

```java
// 修改前：手动设置压缩编码
.addHeader("Accept-Encoding", "gzip, deflate, br")

// 修改后：移除手动设置，让 OkHttp 自动处理
// 移除 Accept-Encoding 让 OkHttp 自动处理压缩和解压缩
```

**修复的文件**：
- `src/main/java/com/mcaiassistant/mcaiassistant/SearchApiClient.java` (第 101 行)
- `src/main/java/com/mcaiassistant/mcaiassistant/AiApiClient.java` (第 110 行)

这样可以确保：
- OkHttp 自动添加合适的 `Accept-Encoding` 头
- 自动解压缩 gzip/deflate 响应
- 返回可读的 JSON 字符串而不是二进制数据

### 3. 智能 URL 构建方法

```java
private String buildSearchApiUrl() {
    String baseUrl = configManager.getApiUrl();
    String searchUrl;
    
    // 如果 baseUrl 已经包含 /v1，则直接拼接 /responses
    if (baseUrl.endsWith("/v1")) {
        searchUrl = baseUrl + "/responses";
    }
    // 否则拼接 /v1/responses
    else {
        searchUrl = baseUrl + "/v1/responses";
    }
    
    // 调试模式下记录 URL 构建过程
    if (configManager.isDebugMode()) {
        plugin.getLogger().info("=== 搜索 API URL 构建 ===");
        plugin.getLogger().info("配置的基础 URL: " + baseUrl);
        plugin.getLogger().info("最终搜索 URL: " + searchUrl);
    }
    
    return searchUrl;
}
```

### 4. 更新配置文件说明

在配置文件中添加了更详细的 API URL 配置说明：

```yaml
ai:
  # API 基础 URL (支持 OpenAI 格式的 API)
  # 示例：
  # - OpenAI 官方: "https://api.openai.com/v1"
  # - poloai.top: "https://poloai.top/v1"
  # - 其他兼容 API: 根据实际情况设置
  api_url: "https://api.openai.com/v1"
```

## 📋 配置指南

### 对于 poloai.top 用户

将配置文件中的 `api_url` 设置为：
```yaml
ai:
  api_url: "https://poloai.top/v1"
```

### 对于其他 OpenAI 兼容 API

根据实际的 API 文档设置正确的基础 URL。

## 🔍 调试功能

修复后的代码在调试模式下会显示：
- 配置的基础 URL
- 最终构建的搜索 URL
- 详细的请求和响应信息

这有助于快速诊断 URL 配置问题。

## ✅ 验证步骤

1. 更新插件代码
2. 重新编译插件
3. 在服务器配置文件中设置正确的 `api_url`
4. 重启服务器
5. 测试 AI 功能：`@ai 你好` 和搜索功能：`@search 测试查询`
6. 查看服务器日志中的 URL 构建信息

## 🚀 预期结果

修复后，AI 助手应该能够：
- ✅ 正确构建 API URL（避免重复 `/v1`）
- ✅ 正确处理压缩响应（自动解压缩）
- ✅ 成功解析 JSON 响应
- ✅ 在游戏中正常显示 AI 和搜索结果
- ✅ `@ai` 和 `@search` 功能都能正常工作
- ✅ 搜索结果在服务器控制台正常显示

## 🔧 额外修复：控制台显示问题

### 问题描述
搜索结果在客户端正常显示，但在服务器控制台看不到搜索结果。

### 原因分析
- `@ai` 功能使用 `Bukkit.broadcastMessage()` 同时发送给玩家和控制台
- `@search` 功能使用 Adventure API 的 `player.sendMessage()` 只发送给玩家

### 修复方案
在 `sendSearchResponse` 方法中添加了控制台广播：

```java
// 同时在控制台显示搜索结果（使用传统格式）
StringBuilder consoleMessage = new StringBuilder();
if (searchResult.getSearchQuery() != null && !searchResult.getSearchQuery().trim().isEmpty()) {
    consoleMessage.append("🔍 搜索: ").append(searchResult.getSearchQuery()).append("\n");
}
consoleMessage.append(aiPrefix).append(searchResult.getResultText());

// 添加链接信息到控制台消息
if (searchResult.getLinks() != null && !searchResult.getLinks().isEmpty()) {
    consoleMessage.append("\n📎 相关链接: ");
    for (int i = 0; i < searchResult.getLinks().size(); i++) {
        SearchApiClient.LinkInfo link = searchResult.getLinks().get(i);
        if (i > 0) {
            consoleMessage.append(" | ");
        }
        consoleMessage.append(link.getTitle()).append(" (").append(link.getUrl()).append(")");
    }
}

// 广播到控制台
Bukkit.broadcastMessage(consoleMessage.toString());
```

现在搜索结果会同时显示在：
- 玩家客户端（可点击链接格式）
- 服务器控制台（传统文本格式，包含完整 URL）
