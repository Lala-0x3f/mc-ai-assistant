package com.mcaiassistant.mcaiassistant;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 速率限制管理器
 * 用于限制玩家的 AI 请求频率
 */
public class RateLimitManager {

    private final McAiAssistant plugin;
    private ConfigManager configManager;
    
    // 存储每个玩家的请求时间戳
    private final Map<UUID, RequestHistory> playerRequests = new ConcurrentHashMap<>();

    public RateLimitManager(McAiAssistant plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * 更新配置
     */
    public void updateConfig(ConfigManager newConfigManager) {
        this.configManager = newConfigManager;
    }

    /**
     * 检查玩家是否可以发送请求
     *
     * @param player 玩家
     * @return 是否允许请求
     */
    public boolean canMakeRequest(Player player) {
        if (!configManager.isRateLimitEnabled()) {
            return true;
        }

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        int maxRequests = configManager.getMaxRequestsPerMinute();

        RequestHistory history = playerRequests.computeIfAbsent(playerId, k -> new RequestHistory());
        
        // 清理超过1分钟的旧请求
        history.cleanOldRequests(currentTime);
        
        // 检查是否超过限制
        if (history.getRequestCount() >= maxRequests) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("玩家 " + player.getName() + " 请求被速率限制阻止 (" + 
                    history.getRequestCount() + "/" + maxRequests + ")");
            }
            return false;
        }

        return true;
    }

    /**
     * 记录玩家的请求
     *
     * @param player 玩家
     */
    public void recordRequest(Player player) {
        if (!configManager.isRateLimitEnabled()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        RequestHistory history = playerRequests.computeIfAbsent(playerId, k -> new RequestHistory());
        history.addRequest(currentTime);

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("记录玩家 " + player.getName() + " 的请求 (" + 
                history.getRequestCount() + "/" + configManager.getMaxRequestsPerMinute() + ")");
        }
    }

    /**
     * 获取速率限制提示消息
     *
     * @return 格式化的提示消息
     */
    public String getRateLimitMessage() {
        String message = configManager.getRateLimitMessage();
        return message.replace("{limit}", String.valueOf(configManager.getMaxRequestsPerMinute()));
    }

    /**
     * 获取玩家剩余的请求次数
     *
     * @param player 玩家
     * @return 剩余请求次数
     */
    public int getRemainingRequests(Player player) {
        if (!configManager.isRateLimitEnabled()) {
            return Integer.MAX_VALUE;
        }

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        int maxRequests = configManager.getMaxRequestsPerMinute();

        RequestHistory history = playerRequests.get(playerId);
        if (history == null) {
            return maxRequests;
        }

        history.cleanOldRequests(currentTime);
        return Math.max(0, maxRequests - history.getRequestCount());
    }

    /**
     * 清理所有过期的请求记录
     */
    public void cleanupExpiredRequests() {
        long currentTime = System.currentTimeMillis();
        playerRequests.values().forEach(history -> history.cleanOldRequests(currentTime));
        
        // 移除空的历史记录
        playerRequests.entrySet().removeIf(entry -> entry.getValue().getRequestCount() == 0);
    }

    /**
     * 请求历史记录类
     */
    private static class RequestHistory {
        private static final long ONE_MINUTE = 60 * 1000; // 1分钟的毫秒数
        private final Map<Long, Integer> requests = new HashMap<>();

        /**
         * 添加请求记录
         */
        public void addRequest(long timestamp) {
            // 将时间戳按分钟分组
            long minuteKey = timestamp / ONE_MINUTE;
            requests.put(minuteKey, requests.getOrDefault(minuteKey, 0) + 1);
        }

        /**
         * 清理超过1分钟的旧请求
         */
        public void cleanOldRequests(long currentTime) {
            long currentMinute = currentTime / ONE_MINUTE;
            requests.entrySet().removeIf(entry -> entry.getKey() < currentMinute);
        }

        /**
         * 获取当前分钟内的请求数量
         */
        public int getRequestCount() {
            return requests.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
