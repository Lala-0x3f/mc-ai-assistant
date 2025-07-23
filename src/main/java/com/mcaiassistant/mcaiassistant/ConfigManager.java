package com.mcaiassistant.mcaiassistant;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 配置管理器
 * 负责加载和管理插件配置
 */
public class ConfigManager {
    
    private final JavaPlugin plugin;
    private FileConfiguration config;
    
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * 加载配置文件
     */
    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        
        // 验证必要的配置项
        validateConfig();
    }
    
    /**
     * 验证配置文件
     */
    private void validateConfig() {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.equals("your-api-key-here")) {
            plugin.getLogger().warning("请在 config.yml 中设置正确的 API Key！");
        }
        
        String apiUrl = getApiUrl();
        if (apiUrl == null || apiUrl.isEmpty()) {
            plugin.getLogger().warning("API URL 配置不能为空！");
        }
    }
    
    // AI API 配置
    public String getApiKey() {
        return config.getString("ai.api_key", "");
    }
    
    public String getApiUrl() {
        return config.getString("ai.api_url", "https://api.openai.com/v1");
    }
    
    public String getModel() {
        return config.getString("ai.model", "gpt-3.5-turbo");
    }

    public String getSearchModel() {
        return config.getString("search.model", "gpt-4.1-mini");
    }

    public String getSearchPromptPrefix() {
        return config.getString("search.prompt_prefix", "请简短的回答：");
    }

    public String getSearchToolType() {
        return config.getString("search.tool_type", "web_search_preview");
    }
    
    public int getMaxTokens() {
        return config.getInt("ai.max_tokens", 1000);
    }
    
    public double getTemperature() {
        return config.getDouble("ai.temperature", 0.7);
    }
    
    public int getTimeout() {
        return config.getInt("ai.timeout", 30);
    }

    public boolean isSimulateBrowser() {
        return config.getBoolean("ai.simulate_browser", true);
    }
    
    // 聊天配置
    public String getAiName() {
        return config.getString("chat.ai_name", "AI助手");
    }
    
    public String getAiPrefix() {
        return config.getString("chat.ai_prefix", "[AI] ");
    }
    
    public boolean isContextEnabled() {
        return config.getBoolean("chat.enable_context", true);
    }
    
    public int getContextMessages() {
        return config.getInt("chat.context_messages", 10);
    }
    
    public List<String> getTriggerKeywords() {
        return config.getStringList("chat.trigger_keywords");
    }

    public List<String> getSearchKeywords() {
        return config.getStringList("chat.search_keywords");
    }
    
    public boolean isSmartMatching() {
        return config.getBoolean("chat.smart_matching", true);
    }
    
    // 系统提示词
    public String getSystemPrompt() {
        return config.getString("system_prompt", "你是一个 Minecraft 服务器的 AI 助手。");
    }
    
    // 功能配置
    public boolean isRedisChatCompatibility() {
        return config.getBoolean("features.redis_chat_compatibility", true);
    }
    
    public boolean isChatLoggingEnabled() {
        return config.getBoolean("features.enable_chat_logging", true);
    }
    
    public boolean isDebugMode() {
        return config.getBoolean("features.debug_mode", false);
    }
    
    // 权限配置
    public boolean isAllowAllPlayers() {
        return config.getBoolean("permissions.allow_all_players", true);
    }

    public String getRequiredPermission() {
        return config.getString("permissions.required_permission", "mcaiassistant.use");
    }

    // UI 通知配置
    public boolean isToastEnabled() {
        return config.getBoolean("notifications.enable_toast", true);
    }

    public boolean isChatStatusEnabled() {
        return config.getBoolean("notifications.enable_chat_status", true);
    }

    public String getProcessingMessage() {
        return config.getString("notifications.processing_message", "🤖 AI 正在处理您的请求，请稍候...");
    }

    // 速率限制配置
    public boolean isRateLimitEnabled() {
        return config.getBoolean("rate_limit.enabled", true);
    }

    public int getMaxRequestsPerMinute() {
        return config.getInt("rate_limit.max_requests_per_minute", 10);
    }

    public String getRateLimitMessage() {
        return config.getString("rate_limit.limit_message", "⚠️ 您的请求过于频繁，请稍后再试。每分钟最多 {limit} 次请求。");
    }
}
