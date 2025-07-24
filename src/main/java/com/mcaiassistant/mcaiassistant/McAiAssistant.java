package com.mcaiassistant.mcaiassistant;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

/**
 * MC AI Assistant Plugin
 * 为 PaperMC 1.21.4 提供 AI 聊天助手功能
 */
public class McAiAssistant extends JavaPlugin {
    
    private static McAiAssistant instance;
    private ConfigManager configManager;
    private ChatListener chatListener;
    private ChatHistoryManager chatHistoryManager;
    private AiApiClient aiApiClient;
    private SearchApiClient searchApiClient;
    private KnowledgeApiClient knowledgeApiClient;
    private RedisChatCompatibility redisChatCompatibility;
    private ToastNotification toastNotification;
    private RateLimitManager rateLimitManager;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // 初始化配置管理器
        configManager = new ConfigManager(this);
        configManager.loadConfig();
        
        // 初始化聊天记录管理器
        chatHistoryManager = new ChatHistoryManager();
        
        // 初始化 AI API 客户端
        aiApiClient = new AiApiClient(configManager);

        // 初始化搜索 API 客户端
        searchApiClient = new SearchApiClient(configManager);

        // 初始化知识库 API 客户端
        knowledgeApiClient = new KnowledgeApiClient(configManager);

        // 初始化 Toast 通知
        toastNotification = new ToastNotification(this);

        // 初始化速率限制管理器
        rateLimitManager = new RateLimitManager(this);

        // 注册聊天监听器
        chatListener = new ChatListener(this, configManager, chatHistoryManager, aiApiClient, searchApiClient, knowledgeApiClient, toastNotification, rateLimitManager);
        getServer().getPluginManager().registerEvents(chatListener, this);

        // 初始化 RedisChat 兼容性
        redisChatCompatibility = new RedisChatCompatibility(this, configManager, chatHistoryManager, aiApiClient, searchApiClient, chatListener, toastNotification);
        
        // 启动定时任务清理过期的速率限制记录
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            rateLimitManager.cleanupExpiredRequests();
        }, 1200L, 1200L); // 每分钟执行一次

        getLogger().info(ChatColor.GREEN + "MC AI Assistant 插件已启用！");
        getLogger().info("支持通过 @ai 在聊天中调用 AI 助手");
        getLogger().info("支持通过 @search 进行网络搜索");
    }
    
    @Override
    public void onDisable() {
        if (aiApiClient != null) {
            aiApiClient.shutdown();
        }

        if (knowledgeApiClient != null) {
            knowledgeApiClient.shutdown();
        }

        getLogger().info(ChatColor.YELLOW + "MC AI Assistant 插件已禁用！");
        instance = null;
    }
    
    /**
     * 获取插件实例
     */
    public static McAiAssistant getInstance() {
        return instance;
    }

    /**
     * 获取配置管理器
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    /**
     * 获取聊天记录管理器
     */
    public ChatHistoryManager getChatHistoryManager() {
        return chatHistoryManager;
    }
    
    /**
     * 获取 AI API 客户端
     */
    public AiApiClient getAiApiClient() {
        return aiApiClient;
    }

    /**
     * 获取知识库 API 客户端
     */
    public KnowledgeApiClient getKnowledgeApiClient() {
        return knowledgeApiClient;
    }
    
    /**
     * 获取 RedisChat 兼容性处理器
     */
    public RedisChatCompatibility getRedisChatCompatibility() {
        return redisChatCompatibility;
    }

    /**
     * 获取 Toast 通知管理器
     */
    public ToastNotification getToastNotification() {
        return toastNotification;
    }
    
    /**
     * 重载配置
     */
    public void reloadPluginConfig() {
        configManager.loadConfig();
        aiApiClient.updateConfig(configManager);
        knowledgeApiClient.updateConfig(configManager);
        getLogger().info("配置已重载");
    }
}
