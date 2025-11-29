package com.mcaiassistant.mcaiassistant;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 聊天事件监听器
 * 监听玩家聊天消息，检测 AI 触发关键词并处理 AI 响应
 */
public class ChatListener implements Listener {
    
    private final McAiAssistant plugin;
    private final ConfigManager configManager;
    private final ChatHistoryManager chatHistoryManager;
    private final AiApiClient aiApiClient;
    private final SearchApiClient searchApiClient;
    private final KnowledgeBaseManager knowledgeBaseManager;
    private final ImageApiClient imageApiClient;
    private final ToastNotification toastNotification;
    private final RateLimitManager rateLimitManager;
    
    // 智能匹配模式，避免匹配到 @airport 等词
    private static final Pattern SMART_AI_PATTERN = Pattern.compile("(?:^|\\s)@ai(?:\\s|$|[^a-zA-Z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SMART_SEARCH_PATTERN = Pattern.compile("(?:^|\\s)@search(?:\\s|$|[^a-zA-Z])", Pattern.CASE_INSENSITIVE);

    // 用于跟踪已处理的消息，避免重复处理（存储消息ID和时间戳）
    private final Map<String, Long> processedMessages = new ConcurrentHashMap<>();
    
    public ChatListener(McAiAssistant plugin, ConfigManager configManager,
                       ChatHistoryManager chatHistoryManager, AiApiClient aiApiClient,
                       SearchApiClient searchApiClient, KnowledgeBaseManager knowledgeBaseManager,
                       ImageApiClient imageApiClient, ToastNotification toastNotification,
                       RateLimitManager rateLimitManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.chatHistoryManager = chatHistoryManager;
        this.aiApiClient = aiApiClient;
        this.searchApiClient = searchApiClient;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.imageApiClient = imageApiClient;
        this.toastNotification = toastNotification;
        this.rateLimitManager = rateLimitManager;
    }
    
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("检测到玩家聊天消息: " + player.getName() + " -> " + message);
        }

        // 创建消息唯一标识符，避免重复处理
        String messageId = player.getName() + ":" + message + ":" + System.currentTimeMillis();

        // 清理过期的消息记录（例如，超过60秒）
        cleanupProcessedMessages();

        // 检查是否已经处理过这条消息
        if (processedMessages.containsKey(messageId)) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("消息已被处理，跳过: " + messageId);
            }
            return;
        }

        // 记录聊天历史
        if (configManager.isChatLoggingEnabled()) {
            chatHistoryManager.addMessage(player.getName(), message);
        }

        // 检查是否包含搜索触发关键词（优先级更高）
        if (containsSearchTrigger(message)) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("玩家聊天消息包含搜索触发词: " + message);
            }

            // 标记消息为已处理
            processedMessages.put(messageId, System.currentTimeMillis());

            // 检查权限
            if (!hasPermission(player)) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("玩家 " + player.getName() + " 没有使用 AI 的权限");
                }
                return;
            }

            // 检查速率限制
            if (!rateLimitManager.canMakeRequest(player)) {
                String limitMessage = rateLimitManager.getRateLimitMessage();
                String formattedMessage = ChatColor.translateAlternateColorCodes('&', limitMessage);
                player.sendMessage(ChatColor.RED + formattedMessage);
                return;
            }

            // 记录请求
            rateLimitManager.recordRequest(player);

            // 异步处理搜索请求
            handleSearchRequest(player, message);
            return;
        }

        // 检查是否包含 AI 触发关键词
        if (containsAiTrigger(message)) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("玩家聊天消息包含 AI 触发词: " + message);
            }

            // 标记消息为已处理
            processedMessages.put(messageId, System.currentTimeMillis());

            // 检查权限
            if (!hasPermission(player)) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("玩家 " + player.getName() + " 没有使用 AI 的权限");
                }
                return;
            }

            // 检查速率限制
            if (!rateLimitManager.canMakeRequest(player)) {
                String limitMessage = rateLimitManager.getRateLimitMessage();
                String formattedMessage = ChatColor.translateAlternateColorCodes('&', limitMessage);
                player.sendMessage(ChatColor.RED + formattedMessage);
                return;
            }

            // 记录请求
            rateLimitManager.recordRequest(player);

            // 异步处理 AI 请求
            handleAiRequest(player, message);
        }
    }
    
    /**
     * 检查消息是否包含 AI 触发关键词
     */
    public boolean containsAiTrigger(String message) {
        List<String> keywords = configManager.getTriggerKeywords();
        
        if (configManager.isSmartMatching()) {
            // 使用智能匹配，避免匹配到 @airport 等词
            return SMART_AI_PATTERN.matcher(message).find();
        } else {
            // 简单匹配
            String lowerMessage = message.toLowerCase();
            return keywords.stream().anyMatch(keyword -> 
                lowerMessage.contains(keyword.toLowerCase()));
        }
    }
    
    /**
     * 检查玩家是否有使用权限
     */
    public boolean hasPermission(Player player) {
        if (configManager.isAllowAllPlayers()) {
            return true;
        }

        String permission = configManager.getRequiredPermission();
        return player.hasPermission(permission);
    }

    /**
     * 标记消息为已处理（供RedisChatCompatibility使用）
     */
    public void markMessageAsProcessed(Player player, String message) {
        String messageId = player.getName() + ":" + message + ":" + System.currentTimeMillis();
        processedMessages.put(messageId, System.currentTimeMillis());

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("消息已标记为已处理: " + messageId);
        }
    }
    
    /**
     * 处理 AI 请求
     */
    private void handleAiRequest(Player player, String message) {
        // 清理消息，移除 @ai 标记
        final String cleanMessage = cleanMessage(message);

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("处理 AI 请求: " + player.getName() + " -> " + cleanMessage);
        }

        // 显示通知
        showProcessingNotifications(player);
        
        // 异步调用 AI API
        CompletableFuture.supplyAsync(() -> {
            try {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("开始处理 AI 请求，清理后的消息: " + cleanMessage);
                }

                // 获取上下文消息
                List<String> context = configManager.isContextEnabled() ?
                    chatHistoryManager.getRecentMessages(configManager.getContextMessages()) : null;

                if (configManager.isDebugMode() && context != null) {
                    plugin.getLogger().info("AI 请求上下文消息数量: " + context.size());
                }

                // 调用 AI API（不再自动查询知识库）
                return aiApiClient.sendMessage(cleanMessage, context);
            } catch (Exception e) {
                plugin.getLogger().severe("AI API 调用失败: " + e.getMessage());
                if (configManager.isDebugMode()) {
                    plugin.getLogger().severe("AI API 调用异常详情:");
                    e.printStackTrace();
                }
                return "抱歉，AI 助手暂时无法响应，请稍后再试。技术详情: " + e.getMessage();
            }
        }).thenAccept(response -> {
            // 在主线程中发送响应
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("AI 请求处理完成，响应长度: " + (response != null ? response.length() : 0));
                }
                sendAiResponse(response, player, cleanMessage);
            });
        });
    }
    
    /**
     * 清理消息，移除 AI 触发关键词
     */
    public String cleanMessage(String message) {
        String cleaned = message;
        
        // 移除所有触发关键词
        for (String keyword : configManager.getTriggerKeywords()) {
            cleaned = cleaned.replaceAll("(?i)" + Pattern.quote(keyword), "").trim();
        }
        
        // 清理多余的空格
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        
        return cleaned;
    }
    
    /**
     * 发送 AI 响应到聊天
     */
    public void sendAiResponse(String response) {
        sendAiResponse(response, null, null);
    }

    /**
     * 发送 AI 响应到聊天（带玩家信息用于图像生成）
     */
    public void sendAiResponse(String response, Player requestPlayer, String originalMessage) {
        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[响应处理] 📥 收到 AI 响应，开始处理");
            if (response != null) {
                plugin.getLogger().info("[响应处理] 响应长度: " + response.length() + " 字符");
                if (response.length() < 500) {
                    plugin.getLogger().info("[响应处理] 完整响应内容: " + response);
                } else {
                    plugin.getLogger().info("[响应处理] 响应内容预览: " + response.substring(0, 500) + "...");
                }
            } else {
                plugin.getLogger().warning("[响应处理] ❌ 收到空响应");
                return;
            }
        }

        if (response == null || response.trim().isEmpty()) {
            return;
        }

        String aiName = configManager.getAiName();
        String aiPrefix = configManager.getAiPrefix();

        // 1. 移除所有工具标签，获取纯文本响应
        String cleanResponse = removeImageTags(removeKnowledgeTags(response));

        // 2. 如果有纯文本内容，立即发送
        if (!cleanResponse.trim().isEmpty()) {
            String formattedMessage = ChatColor.AQUA + aiPrefix + ChatColor.WHITE + cleanResponse;
            Bukkit.broadcastMessage(formattedMessage);
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[响应处理] ✅ 已发送纯文本内容: " + cleanResponse);
            }
        }

        // 3. 记录完整的原始响应到历史记录（如果启用）
        if (configManager.isChatLoggingEnabled()) {
            chatHistoryManager.addMessage(aiName, response);
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[响应处理] 📝 已将原始响应记录到历史");
            }
        }

        // 4. 处理知识库查询（如果存在）
        if (requestPlayer != null && configManager.isKnowledgeEnabled() && response.contains("<query_knowledge")) {
        Pattern knowledgePattern = Pattern.compile("<query_knowledge\\s+query=\"([^\"]+)\"\\s*/>");
            Matcher matcher = knowledgePattern.matcher(response);
            if (matcher.find()) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("[响应处理] 🔍 检测到知识库查询标签，开始处理...");
                }
                // 告诉用户正在查询知识库
                String waitingMessage = ChatColor.AQUA + aiPrefix + ChatColor.YELLOW + "正在查询知识库获取更准确的信息，请稍候...";
                Bukkit.broadcastMessage(waitingMessage);
                processKnowledgeQuery(requestPlayer, response, originalMessage);
                // 注意：这里不再 return，允许后续的图像生成等工具继续执行（如果需要）
            }
        }

        // 5. 处理图像生成（如果存在）
        if (requestPlayer != null && configManager.isImageGenerationEnabled() && response.contains("<create_image")) {
             if (configManager.isDebugMode()) {
                plugin.getLogger().info("[响应处理] 🎨 检测到图像生成标签，开始处理...");
            }
            processImageGeneration(requestPlayer, response);
        }
    }

    /**
     * 显示 AI 处理中的通知
     */
    private void showProcessingNotifications(Player player) {
        // 立即显示 Toast 通知
        if (configManager.isToastEnabled()) {
            toastNotification.showAiProcessingToast(player);
        }

        // 延迟 0.3 秒显示聊天状态提示
        if (configManager.isChatStatusEnabled()) {
            String processingMessage = configManager.getProcessingMessage();
            // 转换颜色代码
            String formattedMessage = ChatColor.translateAlternateColorCodes('&', processingMessage);

            // 延迟 6 ticks (0.3 秒) 发送消息
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(formattedMessage);

                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("发送延迟聊天状态提示给玩家 " + player.getName() + ": " + processingMessage);
                    }
                }
            }, 6L); // 6 ticks = 0.3 秒
        }
    }

    /**
     * 检查消息是否包含搜索触发关键词
     */
    public boolean containsSearchTrigger(String message) {
        if (configManager.isSmartMatching()) {
            return SMART_SEARCH_PATTERN.matcher(message).find();
        } else {
            String lowerMessage = message.toLowerCase();
            for (String keyword : configManager.getSearchKeywords()) {
                if (lowerMessage.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }



    /**
     * 处理搜索请求
     */
    private void handleSearchRequest(Player player, String message) {
        // 清理消息，移除 @search 标记
        String cleanMessage = cleanSearchMessage(message);

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("处理搜索请求: " + player.getName() + " -> " + cleanMessage);
        }

        // 显示通知
        showProcessingNotifications(player);

        // 异步调用搜索 API
        CompletableFuture.supplyAsync(() -> {
            try {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("开始处理搜索请求，清理后的消息: " + cleanMessage);
                }

                // 调用新的搜索 API
                return searchApiClient.search(cleanMessage);
            } catch (Exception e) {
                plugin.getLogger().severe("搜索 API 调用失败: " + e.getMessage());
                if (configManager.isDebugMode()) {
                    plugin.getLogger().severe("搜索 API 调用异常详情:");
                    e.printStackTrace();
                }

                // 返回错误结果
                SearchApiClient.SearchResult errorResult = new SearchApiClient.SearchResult();
                errorResult.setSearchQuery("搜索失败");
                errorResult.setResultText("抱歉，搜索功能暂时无法使用，请稍后再试。技术详情: " + e.getMessage());
                return errorResult;
            }
        }).thenAccept(searchResult -> {
            // 在主线程中发送响应
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("搜索请求处理完成");
                    plugin.getLogger().info("搜索查询: " + searchResult.getSearchQuery());
                    plugin.getLogger().info("搜索结果长度: " + (searchResult.getResultText() != null ? searchResult.getResultText().length() : 0));
                }
                sendSearchResponse(searchResult);
            });
        });
    }

    /**
     * 清理搜索消息，移除触发关键词
     */
    private String cleanSearchMessage(String message) {
        String cleanedMessage = message;

        // 移除搜索关键词
        for (String keyword : configManager.getSearchKeywords()) {
            cleanedMessage = cleanedMessage.replaceAll("(?i)" + Pattern.quote(keyword), "").trim();
        }

        // 清理多余的空格
        cleanedMessage = cleanedMessage.replaceAll("\\s+", " ").trim();

        return cleanedMessage;
    }

    /**
     * 发送搜索响应
     */
    public void sendSearchResponse(SearchApiClient.SearchResult searchResult) {
        if (searchResult == null || searchResult.getResultText() == null || searchResult.getResultText().trim().isEmpty()) {
            return;
        }

        String aiName = "🔍 " + configManager.getAiName();
        String aiPrefix = configManager.getAiPrefix();

        // 构建搜索响应组件
        Component messageComponent = Component.empty();

        // 添加搜索查询信息
        if (searchResult.getSearchQuery() != null && !searchResult.getSearchQuery().trim().isEmpty()) {
            messageComponent = messageComponent
                .append(Component.text("🔍 搜索: ", NamedTextColor.GRAY))
                .append(Component.text(searchResult.getSearchQuery(), NamedTextColor.GRAY))
                .append(Component.newline());
        }

        // 添加搜索结果前缀
        messageComponent = messageComponent
            .append(Component.text(aiPrefix, NamedTextColor.GREEN))
            .append(Component.text(searchResult.getResultText(), NamedTextColor.WHITE));

        // 如果有链接，添加链接部分
        if (searchResult.getLinks() != null && !searchResult.getLinks().isEmpty()) {
            messageComponent = messageComponent
                .append(Component.newline())
                .append(Component.text("📎 相关链接: ", NamedTextColor.AQUA));

            for (int i = 0; i < searchResult.getLinks().size(); i++) {
                SearchApiClient.LinkInfo link = searchResult.getLinks().get(i);
                if (i > 0) {
                    messageComponent = messageComponent.append(Component.text(" | ", NamedTextColor.GRAY));
                }

                // 创建可点击的链接
                Component linkComponent = Component.text(link.getTitle(), NamedTextColor.BLUE)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(link.getUrl()))
                    .hoverEvent(HoverEvent.showText(Component.text("点击打开: " + link.getUrl(), NamedTextColor.YELLOW)));

                messageComponent = messageComponent.append(linkComponent);
            }
        }

        // 广播消息给所有在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(messageComponent);
        }

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

        // 仅在控制台显示搜索结果（不广播给玩家）
        plugin.getLogger().info(consoleMessage.toString());

        // 记录搜索响应到聊天历史
        if (configManager.isChatLoggingEnabled()) {
            String logMessage = "搜索: " + searchResult.getSearchQuery() + " | 结果: " + searchResult.getResultText();
            chatHistoryManager.addMessage(aiName, logMessage);
        }

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("搜索查询: " + searchResult.getSearchQuery());
            plugin.getLogger().info("搜索结果: " + searchResult.getResultText());
            plugin.getLogger().info("提取的链接数量: " + (searchResult.getLinks() != null ? searchResult.getLinks().size() : 0));
        }
    }

    /**
     * 处理图像生成标签
     */
    private void processImageGeneration(Player player, String response) {
        if (!configManager.isImageGenerationEnabled()) {
            return;
        }

        // 检查响应中是否包含图像生成标签（支持新的 alt 属性）
        Pattern imagePattern = Pattern.compile("<create_image\\s+prompt=\"([^\"]+)\"\\s+alt=\"([^\"]+)\"\\s*/>");
        Matcher matcher = imagePattern.matcher(response);

        if (matcher.find()) {
            String prompt = matcher.group(1);
            String alt = matcher.group(2);

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[图像生成] 检测到图像生成请求，prompt: " + prompt + ", alt: " + alt);
            }

            // 显示生成开始通知
            if (configManager.isToastEnabled()) {
                toastNotification.showToast(player, "正在生成图像...", "请稍候，图像生成中");
            }

            // 异步生成图像
            CompletableFuture.supplyAsync(() -> {
                long startTime = System.currentTimeMillis();
                ImageApiClient.ImageResponse imageResponse = imageApiClient.createImage(prompt);
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;

                return new Object[]{imageResponse, duration, alt};
            }).thenAccept(result -> {
                ImageApiClient.ImageResponse imageResponse = (ImageApiClient.ImageResponse) result[0];
                long duration = (Long) result[1];
                String imageAlt = (String) result[2];

                // 在主线程中处理结果
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (imageResponse.isSuccess() && imageResponse.getImages() != null && !imageResponse.getImages().isEmpty()) {
                        // 成功生成图像
                        List<String> images = imageResponse.getImages();

                        // 获取 AI 前缀
                        String aiPrefix = configManager.getAiPrefix();

                        // 创建主消息
                        TextComponent.Builder messageBuilder = Component.text()
                            .append(Component.text(aiPrefix, NamedTextColor.AQUA))
                            .append(Component.text("🎨 ", NamedTextColor.GOLD))
                            .append(Component.text("图像生成完成！", NamedTextColor.GREEN))
                                .hoverEvent(HoverEvent.showText(Component.text(" (由 " + player.getName() + " 生成)", NamedTextColor.GRAY)))
                            .append(Component.text("\n📝 ", NamedTextColor.GRAY))
                            .append(Component.text(imageAlt, NamedTextColor.WHITE)
                                .hoverEvent(HoverEvent.showText(Component.text("原始 Prompt: " + prompt, NamedTextColor.GRAY))))
                            .append(Component.text("\n⏱️ 用时: ", NamedTextColor.GRAY))
                            .append(Component.text(String.format("%.1f秒", duration / 1000.0), NamedTextColor.YELLOW))
                            .append(Component.text("\n🖼️ 生成了 ", NamedTextColor.GRAY))
                            .append(Component.text(images.size() + " 张图像", NamedTextColor.AQUA));

                        // 为每张图像添加可点击链接
                        for (int i = 0; i < images.size(); i++) {
                            String imagePath = images.get(i);
                            String command = "/image create " + imagePath;

                            messageBuilder.append(Component.text("\n🖱️ ", NamedTextColor.BLUE))
                                .append(Component.text("图像 " + (i + 1), NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                                    .clickEvent(ClickEvent.suggestCommand(command))
                                    .hoverEvent(HoverEvent.showText(Component.text()
                                        .append(Component.text("点击获取图像 " + (i + 1), NamedTextColor.GREEN))
                                        .append(Component.text("\n执行命令: ", NamedTextColor.GRAY))
                                        .append(Component.text(command, NamedTextColor.WHITE))
                                        .append(Component.text("\n路径: ", NamedTextColor.GRAY))
                                        .append(Component.text(imagePath, NamedTextColor.YELLOW))
                                        .build())));
                        }

                        Component imageMessage = messageBuilder.build();
                        Bukkit.broadcast(imageMessage);

                        if (configManager.isDebugMode()) {
                            plugin.getLogger().info("[图像生成] 成功生成 " + images.size() + " 张图像，用时: " + duration + "ms");
                            for (int i = 0; i < images.size(); i++) {
                                plugin.getLogger().info("[图像生成] 图像 " + (i + 1) + ": " + images.get(i));
                            }
                        }
                    } else {
                        // 生成失败
                        String errorMsg = imageResponse.getErrorMessage() != null ?
                            imageResponse.getErrorMessage() : "未知错误";

                        String aiPrefix = configManager.getAiPrefix();

                        Component errorMessage = Component.text()
                            .append(Component.text(aiPrefix, NamedTextColor.AQUA))
                            .append(Component.text("❌ ", NamedTextColor.RED))
                            .append(Component.text("图像生成失败 (请求者: " + player.getName() + "): ", NamedTextColor.RED))
                            .append(Component.text(errorMsg, NamedTextColor.GRAY))
                            .build();

                        Bukkit.broadcast(errorMessage);

                        if (configManager.isDebugMode()) {
                            plugin.getLogger().warning("[图像生成] 生成失败: " + errorMsg);
                        }
                    }
                });
            }).exceptionally(throwable -> {
                // 处理异常
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String aiPrefix = configManager.getAiPrefix();

                    Component errorMessage = Component.text()
                        .append(Component.text(aiPrefix, NamedTextColor.AQUA))
                        .append(Component.text("❌ ", NamedTextColor.RED))
                        .append(Component.text("图像生成异常 (请求者: " + player.getName() + "): ", NamedTextColor.RED))
                        .append(Component.text(throwable.getMessage(), NamedTextColor.GRAY))
                        .build();

                    Bukkit.broadcast(errorMessage);
                });

                if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[图像生成] 处理异常: " + throwable.getMessage());
                    throwable.printStackTrace();
                }
                return null;
            });
        }
    }

    /**
     * 移除响应中的图像生成标签
     */
    private String removeImageTags(String response) {
        if (!configManager.isImageGenerationEnabled()) {
            return response;
        }

        // 支持新的 alt 属性格式和旧格式
        Pattern newImagePattern = Pattern.compile("<create_image\\s+prompt=\"[^\"]+\"\\s+alt=\"[^\"]+\"\\s*/>");
        Pattern oldImagePattern = Pattern.compile("<create_image\\s+prompt=\"[^\"]+\"\\s*/>");

        String result = newImagePattern.matcher(response).replaceAll("").trim();
        result = oldImagePattern.matcher(result).replaceAll("").trim();

        return result;
    }

    /**
     * 处理知识库查询标签
     */
    private void processKnowledgeQuery(Player player, String response, final String cleanMessage) {
        if (!configManager.isKnowledgeEnabled()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库查询] 知识库功能未启用，跳过处理");
            }
            return;
        }

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] 开始检查响应中的知识库查询标签");
            plugin.getLogger().info("[知识库查询] 响应内容长度: " + response.length());
            if (response.length() < 500) {
                plugin.getLogger().info("[知识库查询] 完整响应内容: " + response);
            } else {
                plugin.getLogger().info("[知识库查询] 响应内容预览: " + response.substring(0, 500) + "...");
            }
        }

        Pattern knowledgePattern = Pattern.compile("<query_knowledge\\s+query=\"([^\"]+)\"\\s*/>");
        Matcher matcher = knowledgePattern.matcher(response);

        if (!matcher.find()) {
            return;
        }

        final String query = matcher.group(1).trim();

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] ✅ 检测到知识库查询请求");
            plugin.getLogger().info("[知识库查询] 查询内容: " + query);
            plugin.getLogger().info("[知识库查询] 请求玩家: " + player.getName());
            plugin.getLogger().info("[知识库查询] 知识库配置内容: " + configManager.getKnowledgeContent());
        }

        CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            KnowledgeSearchResult result = knowledgeBaseManager.searchKnowledge(query);
            long duration = System.currentTimeMillis() - startTime;

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库查询] 本地检索耗时: " + duration + "ms");
                if (result != null && !result.isEmpty()) {
                    plugin.getLogger().info("[知识库查询] AI 摘要长度: " + (result.getAiAnswer() == null ? 0 : result.getAiAnswer().length()));
                    plugin.getLogger().info("[知识库查询] 直接命中数量: " + result.getSnippets().size());
                } else {
                    plugin.getLogger().info("[知识库查询] 未命中任何知识片段");
                }
            }
            return result;
        }).thenAccept(result -> {
            if (result == null || result.isEmpty()) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("[知识库查询] 未找到任何可用片段，直接反馈暂无资料");
                }
                Bukkit.getScheduler().runTask(plugin, () -> sendFinalAiResponse("抱歉，本地知识库暂未收录相关资料，我会持续关注更新。"));
                return;
            }
            CompletableFuture.supplyAsync(() -> {
                try {
                    List<String> context = configManager.isContextEnabled()
                            ? chatHistoryManager.getRecentMessages(configManager.getContextMessages())
                            : null;
                    String knowledgePayload = result == null ? null : result.toPromptPayload();
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("[知识库查询] 传入知识片段是否为空: " + (knowledgePayload == null ? "是" : "否"));
                    }
                    return aiApiClient.sendMessageWithKnowledge(cleanMessage, context, knowledgePayload);
                } catch (Exception e) {
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().warning("[知识库查询] 生成知识库回复失败: " + e.getMessage());
                    }
                    return null;
                }
            }).thenAccept(finalResponse -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (finalResponse != null && !finalResponse.trim().isEmpty()) {
                    sendFinalAiResponse(finalResponse);
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("[知识库查询] ✅ 知识库查询流程完毕");
                    }
                } else if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[知识库查询] ⚠ 最终响应为空，跳过发送");
                }
            }));
        });
    }

    /**
     * 发送最终 AI 响应（不处理标签）
     */
    private void sendFinalAiResponse(String response) {
        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] 📤 准备发送最终 AI 响应");
        }

        if (response == null || response.trim().isEmpty()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[知识库查询] ❌ 响应为空，取消发送");
            }
            return;
        }

        // 移除知识库查询标签
        String cleanResponse = removeKnowledgeTags(response);

        String aiName = configManager.getAiName();
        String aiPrefix = configManager.getAiPrefix();

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] AI 名称: " + aiName);
            plugin.getLogger().info("[知识库查询] AI 前缀: " + aiPrefix);
            plugin.getLogger().info("[知识库查询] 原始响应长度: " + response.length() + " 字符");
            plugin.getLogger().info("[知识库查询] 清理后响应长度: " + cleanResponse.length() + " 字符");
        }

        // 格式化 AI 响应消息
        String formattedMessage = ChatColor.AQUA + aiPrefix + ChatColor.WHITE + cleanResponse;

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] 格式化后消息长度: " + formattedMessage.length() + " 字符");
            plugin.getLogger().info("[知识库查询] 📢 广播消息给所有在线玩家");
        }

        // 广播消息给所有在线玩家
        Bukkit.broadcastMessage(formattedMessage);

        // 记录到聊天历史
        if (configManager.isChatLoggingEnabled()) {
            chatHistoryManager.addMessage(aiName, cleanResponse);
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库查询] ✅ 已记录到聊天历史");
            }
        } else {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库查询] 聊天日志功能未启用，跳过记录");
            }
        }

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] ✅ 最终 AI 响应发送完成");
            plugin.getLogger().info("[知识库查询] 清理后响应内容: " + cleanResponse);
        }
    }

    /**
     * 移除响应中的知识库查询标签
     */
    private String removeKnowledgeTags(String response) {
        if (!configManager.isKnowledgeEnabled()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库查询] 知识库功能未启用，跳过标签移除");
            }
            return response;
        }

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] 🏷️ 开始移除知识库查询标签");
            plugin.getLogger().info("[知识库查询] 原始响应长度: " + response.length() + " 字符");
        }

        // 移除知识库查询标签
        Pattern knowledgePattern = Pattern.compile("<query_knowledge\\s+query=\"[^\"]+\"\\s*/>");
        Matcher matcher = knowledgePattern.matcher(response);

        boolean foundTags = matcher.find();
        String result = knowledgePattern.matcher(response).replaceAll("").trim();

        if (configManager.isDebugMode()) {
            if (foundTags) {
                plugin.getLogger().info("[知识库查询] ✅ 发现并移除了知识库查询标签");
                plugin.getLogger().info("[知识库查询] 移除后响应长度: " + result.length() + " 字符");
                plugin.getLogger().info("[知识库查询] 长度变化: " + (response.length() - result.length()) + " 字符");
            } else {
                plugin.getLogger().info("[知识库查询] ❌ 未发现知识库查询标签");
            }
        }

        return result;
    }

    /**
     * 清理过期的已处理消息记录
     */
    private void cleanupProcessedMessages() {
        long expirationTime = System.currentTimeMillis() - 60000; // 60秒前
        processedMessages.entrySet().removeIf(entry -> entry.getValue() < expirationTime);
    }
}
