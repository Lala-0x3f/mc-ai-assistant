package com.mcaiassistant.mcaiassistant;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

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
    private final ToastNotification toastNotification;
    private final RateLimitManager rateLimitManager;
    
    // 智能匹配模式，避免匹配到 @airport 等词
    private static final Pattern SMART_AI_PATTERN = Pattern.compile("(?:^|\\s)@ai(?:\\s|$|[^a-zA-Z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SMART_SEARCH_PATTERN = Pattern.compile("(?:^|\\s)@search(?:\\s|$|[^a-zA-Z])", Pattern.CASE_INSENSITIVE);
    
    public ChatListener(McAiAssistant plugin, ConfigManager configManager,
                       ChatHistoryManager chatHistoryManager, AiApiClient aiApiClient,
                       SearchApiClient searchApiClient, ToastNotification toastNotification,
                       RateLimitManager rateLimitManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.chatHistoryManager = chatHistoryManager;
        this.aiApiClient = aiApiClient;
        this.searchApiClient = searchApiClient;
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

        // 记录聊天历史
        if (configManager.isChatLoggingEnabled()) {
            chatHistoryManager.addMessage(player.getName(), message);
        }

        // 检查是否包含搜索触发关键词（优先级更高）
        if (containsSearchTrigger(message)) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("玩家聊天消息包含搜索触发词: " + message);
            }

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
     * 处理 AI 请求
     */
    private void handleAiRequest(Player player, String message) {
        // 清理消息，移除 @ai 标记
        String cleanMessage = cleanMessage(message);

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

                // 调用 AI API
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
                sendAiResponse(response);
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
        if (response == null || response.trim().isEmpty()) {
            return;
        }
        
        String aiName = configManager.getAiName();
        String aiPrefix = configManager.getAiPrefix();
        
        // 格式化 AI 响应消息
        String formattedMessage = ChatColor.AQUA + aiPrefix + ChatColor.WHITE + response;
        
        // 广播消息给所有在线玩家
        Bukkit.broadcastMessage(formattedMessage);
        
        // 记录 AI 响应到聊天历史
        if (configManager.isChatLoggingEnabled()) {
            chatHistoryManager.addMessage(aiName, response);
        }
        
        if (configManager.isDebugMode()) {
            plugin.getLogger().info("AI 响应: " + response);
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

        // 广播到控制台
        Bukkit.broadcastMessage(consoleMessage.toString());

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
}
