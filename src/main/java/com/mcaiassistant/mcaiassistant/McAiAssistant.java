package com.mcaiassistant.mcaiassistant;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

/**
 * MC AI Assistant Plugin
 * 为 PaperMC 1.21.x (1.21-1.21.11) 提供 AI 聊天助手功能
 */
public class McAiAssistant extends JavaPlugin {
    
    private static McAiAssistant instance;
    private ConfigManager configManager;
    private ModelManager modelManager;
    private ChatListener chatListener;
    private ChatHistoryManager chatHistoryManager;
    private GlobalMemoryManager globalMemoryManager;
    private AiApiClient aiApiClient;
    private SearchApiClient searchApiClient;
    private KnowledgeBaseManager knowledgeBaseManager;
    private ImageApiClient imageApiClient;
    private RedisChatCompatibility redisChatCompatibility;
    private ToastNotification toastNotification;
    private RateLimitManager rateLimitManager;
    private EconomyManager economyManager;
    private CommandWhitelistManager commandWhitelistManager;
    private McpManager mcpManager;
    private ScreenshotManager screenshotManager;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // 初始化配置管理器
        configManager = new ConfigManager(this);
        configManager.loadConfig();

        // 初始化模型管理器（运行时覆盖 + /models 缓存）
        modelManager = new ModelManager(this, configManager);
        // 异步预取一次模型列表，供后续补全使用
        modelManager.refreshModelsAsync();
        
        // 初始化聊天记录管理器
        chatHistoryManager = new ChatHistoryManager();

        // 初始化全局记忆管理器（相关性检索 + 摘要写入）
        globalMemoryManager = new GlobalMemoryManager(this, configManager);
        globalMemoryManager.initialize();

        // 初始化 AI API 客户端
        aiApiClient = new AiApiClient(configManager, modelManager);

        // 初始化搜索 API 客户端
        searchApiClient = new SearchApiClient(configManager);
        // 初始化本地知识库管理器
        knowledgeBaseManager = new KnowledgeBaseManager(this, configManager);
        knowledgeBaseManager.initialize();

        // 初始化图像生成 API 客户端
        imageApiClient = new ImageApiClient(this, configManager);

        // 初始化 Toast 通知
        toastNotification = new ToastNotification(this);

        // 初始化速率限制管理器
        rateLimitManager = new RateLimitManager(this, configManager);

        // 初始化经济扣费管理器
        economyManager = new EconomyManager(this, configManager);
        economyManager.initialize();

        // 初始化 AI 指令白名单管理器
        commandWhitelistManager = new CommandWhitelistManager(this);
        commandWhitelistManager.initialize();

        // 初始化 MCP 管理器（mcp.json）
        mcpManager = new McpManager(this);
        mcpManager.initialize();

        // 初始化截图管理器（视觉附属 mod 支持）
        screenshotManager = new ScreenshotManager(this, configManager);

        // 注册聊天监听器
        chatListener = new ChatListener(this, configManager, chatHistoryManager, aiApiClient, searchApiClient, knowledgeBaseManager, imageApiClient, toastNotification, rateLimitManager, economyManager, globalMemoryManager, commandWhitelistManager, mcpManager);
        getServer().getPluginManager().registerEvents(chatListener, this);

        ModelCommand modelCommand = new ModelCommand(this, configManager, modelManager);
        TestCommand testCommand = new TestCommand(this, configManager, aiApiClient, knowledgeBaseManager);
        AiCommandWhitelistCommand whitelistCommand = new AiCommandWhitelistCommand(this, commandWhitelistManager);

        // 注册 /model 指令与补全
        if (getCommand("model") != null) {
            getCommand("model").setExecutor(modelCommand);
            getCommand("model").setTabCompleter(modelCommand);
        } else {
            getLogger().warning("未在 plugin.yml 中找到 model 指令定义，/model 无法注册");
        }

        // 注册 /aitest 指令与补全（模型/知识库健康检查）
        if (getCommand("aitest") != null) {
            getCommand("aitest").setExecutor(testCommand);
            getCommand("aitest").setTabCompleter(testCommand);
        } else {
            getLogger().warning("未在 plugin.yml 中找到 aitest 指令定义，/aitest 无法注册");
        }

        // 注册 /aicmdwl 指令（AI 指令白名单管理）
        if (getCommand("aicmdwl") != null) {
            getCommand("aicmdwl").setExecutor(whitelistCommand);
            getCommand("aicmdwl").setTabCompleter(whitelistCommand);
        } else {
            getLogger().warning("未在 plugin.yml 中找到 aicmdwl 指令定义，/aicmdwl 无法注册");
        }

        // 注册 /ai 统一管理指令
        if (getCommand("ai") != null) {
            AiCommand aiCommand = new AiCommand(this, configManager, modelManager,
                    testCommand, modelCommand, whitelistCommand, commandWhitelistManager, mcpManager);
            getCommand("ai").setExecutor(aiCommand);
            getCommand("ai").setTabCompleter(aiCommand);
        } else {
            getLogger().warning("未在 plugin.yml 中找到 ai 指令定义，/ai 无法注册");
        }
 
        // 初始化 RedisChat 兼容性
        redisChatCompatibility = new RedisChatCompatibility(this, configManager, chatHistoryManager, aiApiClient, searchApiClient, chatListener, toastNotification, globalMemoryManager, rateLimitManager, economyManager);
        
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

        if (knowledgeBaseManager != null) {
            knowledgeBaseManager.shutdown();
        }

        if (imageApiClient != null) {
            imageApiClient.shutdown();
        }

        if (mcpManager != null) {
            mcpManager.shutdown();
        }

        if (screenshotManager != null) {
            screenshotManager.shutdown();
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
     * 获取知识库管理器
     */
    public KnowledgeBaseManager getKnowledgeBaseManager() {
        return knowledgeBaseManager;
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
     * 获取经济扣费管理器
     */
    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    /**
     * 获取 AI 指令白名单管理器
     */
    public CommandWhitelistManager getCommandWhitelistManager() {
        return commandWhitelistManager;
    }

    /**
     * 获取 MCP 管理器
     */
    public McpManager getMcpManager() {
        return mcpManager;
    }

    /**
     * 获取截图管理器（视觉附属 mod 支持）
     */
    public ScreenshotManager getScreenshotManager() {
        return screenshotManager;
    }
    
    /**
     * 重载配置
     */
    public void reloadPluginConfig() {
        configManager.loadConfig();
        // 更新 ModelManager 与各客户端配置
        if (modelManager != null) {
            modelManager.updateConfig(configManager);
        }
        aiApiClient.updateConfig(configManager);
        knowledgeBaseManager.updateConfig(configManager);
        rateLimitManager.updateConfig(configManager);
        if (globalMemoryManager != null) {
            globalMemoryManager.updateConfig(configManager);
        }
        redisChatCompatibility.updateConfig(configManager);
        if (economyManager != null) {
            economyManager.reload();
        }
        // imageApiClient 也需要更新配置
        if (imageApiClient != null) {
            imageApiClient.updateConfig(configManager);
        }
        if (mcpManager != null) {
            mcpManager.reload();
        }
        getLogger().info("配置已重载");
    }
}
