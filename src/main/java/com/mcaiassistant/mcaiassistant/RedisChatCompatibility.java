package com.mcaiassistant.mcaiassistant;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * RedisChat 兼容性处理器
 * 处理 RedisChat 插件的聊天事件
 */
public class RedisChatCompatibility implements Listener {
    
    private final McAiAssistant plugin;
    private final ConfigManager configManager;
    private final ChatHistoryManager chatHistoryManager;
    private final AiApiClient aiApiClient;
    private final SearchApiClient searchApiClient;
    private final ChatListener chatListener;
    private final ToastNotification toastNotification;
    
    private boolean redisChatEnabled = false;
    private Class<?> redisChatEventClass;
    private Method getPlayerMethod;
    private Method getMessageMethod;
    
    public RedisChatCompatibility(McAiAssistant plugin, ConfigManager configManager,
                                 ChatHistoryManager chatHistoryManager, AiApiClient aiApiClient,
                                 SearchApiClient searchApiClient, ChatListener chatListener,
                                 ToastNotification toastNotification) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.chatHistoryManager = chatHistoryManager;
        this.aiApiClient = aiApiClient;
        this.searchApiClient = searchApiClient;
        this.chatListener = chatListener;
        this.toastNotification = toastNotification;
        
        initializeRedisChatSupport();
    }
    
    /**
     * 初始化 RedisChat 支持
     */
    private void initializeRedisChatSupport() {
        if (!configManager.isRedisChatCompatibility()) {
            plugin.getLogger().info("RedisChat 兼容模式已禁用");
            return;
        }

        Plugin redisChatPlugin = Bukkit.getPluginManager().getPlugin("RedisChat");
        if (redisChatPlugin == null) {
            plugin.getLogger().info("未检测到 RedisChat 插件");
            return;
        }

        plugin.getLogger().info("检测到 RedisChat 插件，版本: " + redisChatPlugin.getDescription().getVersion());

        try {
            // 尝试加载 RedisChat 的聊天事件类
            // 注意：这里的类名可能需要根据实际的 RedisChat 版本进行调整
            redisChatEventClass = Class.forName("dev.unnm3d.redischat.api.events.RedisChatMessageEvent");

            // 获取必要的方法
            getPlayerMethod = redisChatEventClass.getMethod("getPlayer");
            getMessageMethod = redisChatEventClass.getMethod("getMessage");

            // 动态注册事件监听器
            registerRedisChatEventListener();

            redisChatEnabled = true;
            plugin.getLogger().info("RedisChat 兼容模式已启用");

        } catch (ClassNotFoundException | NoSuchMethodException e) {
            plugin.getLogger().warning("无法加载 RedisChat 事件类，可能是版本不兼容: " + e.getMessage());

            // 尝试其他可能的类名
            tryAlternativeRedisChatClasses();
        }
    }
    
    /**
     * 尝试其他可能的 RedisChat 事件类名
     */
    private void tryAlternativeRedisChatClasses() {
        String[] possibleClassNames = {
            "dev.unnm3d.redischat.events.RedisChatMessageEvent",
            "com.github.emibergo.redischat.events.RedisChatMessageEvent",
            "redischat.events.RedisChatMessageEvent",
            "dev.unnm3d.redischat.api.events.ChatMessageEvent",
            "dev.unnm3d.redischat.api.events.PlayerChatEvent",
            "dev.unnm3d.redischat.events.ChatMessageEvent",
            "dev.unnm3d.redischat.events.PlayerChatEvent"
        };

        for (String className : possibleClassNames) {
            plugin.getLogger().info("尝试加载 RedisChat 事件类: " + className);
            try {
                redisChatEventClass = Class.forName(className);
                getPlayerMethod = redisChatEventClass.getMethod("getPlayer");
                getMessageMethod = redisChatEventClass.getMethod("getMessage");

                // 动态注册事件监听器
                registerRedisChatEventListener();

                redisChatEnabled = true;
                plugin.getLogger().info("RedisChat 兼容模式已启用 (使用类: " + className + ")");
                return;

            } catch (ClassNotFoundException e) {
                plugin.getLogger().info("类不存在: " + className);
            } catch (NoSuchMethodException e) {
                plugin.getLogger().info("类 " + className + " 缺少必要的方法: " + e.getMessage());
            }
        }

        plugin.getLogger().warning("无法找到兼容的 RedisChat 事件类");
    }

    /**
     * 动态注册 RedisChat 事件监听器
     */
    @SuppressWarnings("unchecked")
    private void registerRedisChatEventListener() {
        if (redisChatEventClass == null) {
            return;
        }

        try {
            // 创建事件执行器
            EventExecutor executor = (listener, event) -> {
                if (redisChatEventClass.isInstance(event)) {
                    handleRedisChatEvent((Event) event);
                }
            };

            // 动态注册事件监听器
            Bukkit.getPluginManager().registerEvent(
                (Class<? extends Event>) redisChatEventClass,
                this,
                EventPriority.MONITOR,
                executor,
                plugin,
                true // ignoreCancelled
            );

        } catch (Exception e) {
            plugin.getLogger().warning("注册 RedisChat 事件监听器失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理 RedisChat 聊天事件
     * 使用反射来处理事件，避免硬依赖
     */
    private void handleRedisChatEvent(Event event) {
        if (!redisChatEnabled || !redisChatEventClass.isInstance(event)) {
            return;
        }

        try {
            // 使用反射获取玩家和消息
            Player player = (Player) getPlayerMethod.invoke(event);
            String message = (String) getMessageMethod.invoke(event);

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("检测到 RedisChat 消息: " + (player != null ? player.getName() : "null") + " -> " + message);
            }

            if (player == null || message == null) {
                return;
            }

            // 记录聊天历史
            if (configManager.isChatLoggingEnabled()) {
                chatHistoryManager.addMessage(player.getName(), message);
            }

            // 检查是否包含搜索触发关键词（优先级更高）
            if (chatListener.containsSearchTrigger(message)) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("RedisChat 消息包含搜索触发词: " + message);
                }

                // 检查权限
                if (!chatListener.hasPermission(player)) {
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("玩家 " + player.getName() + " 没有使用 AI 的权限");
                    }
                    return;
                }

                // 异步处理搜索请求
                handleRedisChatSearchRequest(player, message);
                return;
            }

            // 检查是否包含 AI 触发关键词
            if (chatListener.containsAiTrigger(message)) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("RedisChat 消息包含 AI 触发词: " + message);
                }

                // 检查权限
                if (!chatListener.hasPermission(player)) {
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("玩家 " + player.getName() + " 没有使用 AI 的权限");
                    }
                    return;
                }

                // 异步处理 AI 请求
                handleRedisChatAiRequest(player, message);
            }

        } catch (Exception e) {
            plugin.getLogger().warning("处理 RedisChat 事件时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 处理 RedisChat 的 AI 请求
     */
    private void handleRedisChatAiRequest(Player player, String message) {
        // 清理消息，移除 @ai 标记
        String cleanMessage = chatListener.cleanMessage(message);

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("处理 RedisChat AI 请求: " + player.getName() + " -> " + cleanMessage);
        }

        // 显示通知
        showProcessingNotifications(player);
        
        // 异步调用 AI API
        CompletableFuture.supplyAsync(() -> {
            try {
                // 获取上下文消息（包含所有消息，包括AI响应）
                return configManager.isContextEnabled() ?
                    chatHistoryManager.getRecentMessages(configManager.getContextMessages()) : null;
            } catch (Exception e) {
                plugin.getLogger().severe("获取聊天上下文失败: " + e.getMessage());
                return null;
            }
        }).thenCompose(context -> {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return aiApiClient.sendMessage(cleanMessage, context);
                } catch (Exception e) {
                    plugin.getLogger().severe("RedisChat AI API 调用失败: " + e.getMessage());
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().severe("RedisChat AI API 调用异常详情:");
                        e.printStackTrace();
                    }
                    return "抱歉，AI 助手暂时无法响应，请稍后再试。技术详情: " + e.getMessage();
                }
            });
        }).thenAccept(response -> {
            // 在主线程中发送响应
            Bukkit.getScheduler().runTask(plugin, () -> {
                chatListener.sendAiResponse(response);
            });
        });
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
                        plugin.getLogger().info("发送延迟 RedisChat 聊天状态提示给玩家 " + player.getName() + ": " + processingMessage);
                    }
                }
            }, 6L); // 6 ticks = 0.3 秒
        }
    }

    /**
     * 处理 RedisChat 搜索请求
     */
    private void handleRedisChatSearchRequest(Player player, String message) {
        // 清理消息，移除 @search 标记
        String cleanMessage = cleanSearchMessage(message);

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("处理 RedisChat 搜索请求: " + player.getName() + " -> " + cleanMessage);
        }

        // 显示通知
        showProcessingNotifications(player);

        // 异步调用搜索 API
        CompletableFuture.supplyAsync(() -> {
            try {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("RedisChat 开始处理搜索请求，清理后的消息: " + cleanMessage);
                }

                // 调用新的搜索 API
                return searchApiClient.search(cleanMessage);
            } catch (Exception e) {
                plugin.getLogger().severe("RedisChat 搜索 API 调用失败: " + e.getMessage());
                if (configManager.isDebugMode()) {
                    plugin.getLogger().severe("RedisChat 搜索 API 调用异常详情:");
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
                    plugin.getLogger().info("RedisChat 搜索请求处理完成");
                    plugin.getLogger().info("搜索查询: " + searchResult.getSearchQuery());
                    plugin.getLogger().info("搜索结果长度: " + (searchResult.getResultText() != null ? searchResult.getResultText().length() : 0));
                }
                chatListener.sendSearchResponse(searchResult);
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
     * 检查 RedisChat 是否已启用
     */
    public boolean isRedisChatEnabled() {
        return redisChatEnabled;
    }
}
