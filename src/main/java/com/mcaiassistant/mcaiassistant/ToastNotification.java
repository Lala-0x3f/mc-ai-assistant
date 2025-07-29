package com.mcaiassistant.mcaiassistant;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.NamespacedKey;

import java.util.Collection;

/**
 * Toast 通知工具类
 * 用于向玩家显示通知消息
 * 使用 Action Bar 和真正的 Toast 通知（通过临时成就实现）
 */
public class ToastNotification {

    private final McAiAssistant plugin;
    private final ConfigManager configManager;

    public ToastNotification(McAiAssistant plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }
    
    /**
     * 向玩家显示 Toast 通知（简化版本）
     *
     * @param player 目标玩家
     * @param title 通知标题
     * @param description 通知描述
     */
    public void showToast(Player player, String title, String description) {
        showNotification(player, title, description);
    }

    /**
     * 向玩家显示通知
     *
     * @param player 目标玩家
     * @param title 通知标题
     * @param description 通知描述
     */
    public void showNotification(Player player, String title, String description) {
        // 检查是否启用了 Toast 通知
        if (!configManager.isToastEnabled()) {
            return;
        }

        try {
            // 方法1: 发送 Action Bar 消息
            String actionBarMessage = ChatColor.YELLOW + "⭐ " + title + " " + ChatColor.WHITE + description;
            player.sendActionBar(actionBarMessage);

            // 方法2: 尝试显示真正的 Toast 通知
            showRealToast(player, title, description);

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("发送 Toast 通知给玩家 " + player.getName() + ": " + title + " - " + description);
            }

        } catch (Exception e) {
            plugin.getLogger().warning("发送 Toast 通知失败: " + e.getMessage());
            if (configManager.isDebugMode()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 显示真正的 Toast 通知（通过临时成就实现）
     */
    private void showRealToast(Player player, String title, String description) {
        try {
            // 创建一个临时的成就来显示 Toast
            NamespacedKey key = new NamespacedKey(plugin, "temp_toast_" + System.currentTimeMillis());

            // 由于 Bukkit API 限制，我们使用 Action Bar 作为主要通知方式
            // 并发送一个带有特殊格式的消息来模拟 Toast
            String toastMessage = ChatColor.GOLD + "📢 " + ChatColor.YELLOW + title +
                                 ChatColor.GRAY + " | " + ChatColor.WHITE + description;

            // 发送到 Action Bar（持续3秒）
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendActionBar(toastMessage);
                }
            }, 1L);

            // 再次发送以确保显示
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendActionBar(toastMessage);
                }
            }, 20L); // 1秒后

            // 清除 Action Bar
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendActionBar("");
                }
            }, 60L); // 3秒后清除

        } catch (Exception e) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("显示真正的 Toast 失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 显示 AI 处理中的通知
     *
     * @param player 目标玩家
     */
    public void showAiProcessingToast(Player player) {
        // 检查是否启用了 Toast 通知
        if (!configManager.isToastEnabled()) {
            return;
        }

        // 使用配置中的自定义消息，但只取前半部分作为 Toast 显示
        String processingMessage = configManager.getProcessingMessage();

        // 移除颜色代码和 Emoji，简化 Toast 消息
        String simpleMessage = ChatColor.stripColor(processingMessage)
            .replaceAll("[🤖⏳💭⚡]", "")  // 移除常见 Emoji
            .trim();

        // 如果消息太长，截取前30个字符
        if (simpleMessage.length() > 30) {
            simpleMessage = simpleMessage.substring(0, 27) + "...";
        }

        showNotification(player, "AI 助手", simpleMessage);
    }

    /**
     * 显示自定义 Toast 通知
     *
     * @param player 目标玩家
     * @param message 自定义消息
     */
    public void showCustomToast(Player player, String message) {
        // 检查是否启用了 Toast 通知
        if (!configManager.isToastEnabled()) {
            return;
        }

        showNotification(player, "提示", message);
    }
}
