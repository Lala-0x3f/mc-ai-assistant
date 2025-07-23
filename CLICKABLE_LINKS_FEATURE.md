# 🔗 可点击链接功能

## 🎯 功能概述

为搜索结果添加了可点击链接功能，将 markdown 格式的链接转换为 Minecraft 中可点击的文本组件，提升用户体验。

## ✨ 功能特性

### 1. **自动链接提取**
- 自动识别搜索结果中的 markdown 链接格式：`[标题](URL)`
- 提取链接标题和 URL 信息
- 从主要文本中移除冗长的 URL，保持内容简洁

### 2. **可点击链接**
- 使用 Minecraft Adventure API 创建可点击的文本组件
- 点击链接直接在浏览器中打开对应网页
- 蓝色下划线样式，符合网页链接的视觉习惯

### 3. **悬停提示**
- 鼠标悬停在链接上时显示完整 URL
- 提示文本："点击打开: [URL]"
- 帮助用户了解链接目标

## 🎨 显示效果

### 修改前
```
🔍 搜索: 北欧 半木结构建筑
[筑缘AI] 北欧地区以其丰富的木质建筑传统而闻名，许多历史悠久的木结构建筑至今仍然存在。例如，位于芬兰佩泰耶韦西市的佩泰耶韦西老教堂建于1763年至1765年间，1994年被联合国教科文组织列入世界文化遗产名录。 ([zh.wikipedia.org](https://zh.wikipedia.org/wiki/%E4%BD%A9%E6%B3%B0%E8%80%B6%E9%9F%A6%E8%A5%BF%E8%80%81%E6%95%99%E5%A0%82?utm_source=openai))此外，挪威的米约萨塔（Mjøstårnet）于2019年完工，高达85.4米，是世界上最高的木结构建筑。 ([zh.wikipedia.org](https://zh.wikipedia.org/wiki/%E7%B1%B3%E7%B4%84%E8%96%A9%E5%A1%94?utm_source=openai))
```

### 修改后
```
🔍 搜索: 北欧 半木结构建筑
[筑缘AI] 北欧地区以其丰富的木质建筑传统而闻名，许多历史悠久的木结构建筑至今仍然存在。例如，位于芬兰佩泰耶韦西市的佩泰耶韦西老教堂建于1763年至1765年间，1994年被联合国教科文组织列入世界文化遗产名录。此外，挪威的米约萨塔于2019年完工，高达85.4米，是世界上最高的木结构建筑。
📎 相关链接: zh.wikipedia.org | zh.wikipedia.org | zaobao.com.sg
```

## 🔧 技术实现

### 1. **链接提取算法**
```java
// 正则表达式匹配 markdown 链接
Pattern pattern = Pattern.compile("\\[([^\\]]+)\\]\\(([^\\)]+)\\)");
Matcher matcher = pattern.matcher(text);

// 提取链接信息
while (matcher.find()) {
    String title = matcher.group(1);
    String url = matcher.group(2);
    result.addLink(new LinkInfo(title, url));
}

// 清理文本，移除 URL 部分
return text.replaceAll("\\s*\\([^\\)]+\\)", "");
```

### 2. **可点击组件创建**
```java
// 创建可点击的链接组件
Component linkComponent = Component.text(link.getTitle(), NamedTextColor.BLUE)
    .decorate(TextDecoration.UNDERLINED)
    .clickEvent(ClickEvent.openUrl(link.getUrl()))
    .hoverEvent(HoverEvent.showText(Component.text("点击打开: " + link.getUrl(), NamedTextColor.YELLOW)));
```

### 3. **数据结构扩展**
```java
public static class SearchResult {
    private String searchQuery;
    private String resultText;
    private List<LinkInfo> links;  // 新增链接列表
    
    // ... 其他方法
}

public static class LinkInfo {
    private String title;
    private String url;
    
    // ... 构造函数和访问器
}
```

## 🎯 用户体验改进

### 1. **内容简洁性**
- 移除了冗长的 URL 参数
- 保持搜索结果文本的可读性
- 避免了 markdown 格式在游戏中的显示问题

### 2. **交互便利性**
- 一键点击即可打开相关网页
- 无需手动复制粘贴 URL
- 支持多个链接的并列显示

### 3. **视觉清晰性**
- 蓝色下划线明确标识可点击链接
- 链接区域与主要内容分离
- 悬停提示提供额外信息

## 🔍 调试功能

在调试模式下，会记录提取的链接数量：
```
[INFO] 提取的链接数量: 3
```

## 🚀 兼容性

- ✅ 支持所有现代 Minecraft 客户端
- ✅ 兼容 Paper/Spigot 服务器
- ✅ 使用标准 Adventure API
- ✅ 向后兼容现有功能

## 📝 配置说明

无需额外配置，功能自动启用。链接处理逻辑集成在搜索响应处理中。

## 🔄 未来扩展

可能的功能扩展：
- 支持自定义链接样式
- 添加链接预览功能
- 支持更多 markdown 格式
- 链接访问统计
