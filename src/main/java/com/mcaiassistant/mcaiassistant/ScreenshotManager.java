package com.mcaiassistant.mcaiassistant;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 截图管理器
 * 通过 Plugin Messaging Channel 向客户端附属 mod 请求截图，并等待回传结果。
 * 若客户端未安装附属 mod 或超时，则回退到无图片模式。
 *
 * 通道协议：
 *   S→C  mcai:vision/request  (无 payload，仅触发截图)
 *   C→S  mcai:vision/response (UTF-8 base64 JPEG 字符串)
 *   C→S  mcai:vision/hello    (客户端能力声明)
 */
public class ScreenshotManager implements PluginMessageListener {

    public static final String CHANNEL_REQUEST = "mcai:vision/request";
    public static final String CHANNEL_RESPONSE = "mcai:vision/response";
    public static final String CHANNEL_HELLO = "mcai:vision/hello";

    private static final long SCREENSHOT_TIMEOUT_MS = 5000;
    private static final int MAX_HELLO_BYTES = 256;
    private static final String MOD_HELLO_PREFIX = "mcai-vision:";

    private final Map<UUID, String> modInstalledPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    private final McAiAssistant plugin;
    private final ConfigManager configManager;

    public ScreenshotManager(McAiAssistant plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_REQUEST);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_RESPONSE, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_HELLO, this);
    }

    public void shutdown() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_REQUEST);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL_RESPONSE, this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL_HELLO, this);

        pendingRequests.values().forEach(future -> {
            if (future != null && !future.isDone()) {
                future.complete(null);
            }
        });
        pendingRequests.clear();
        modInstalledPlayers.clear();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (CHANNEL_HELLO.equals(channel)) {
            handleHello(player, message);
            return;
        }
        if (CHANNEL_RESPONSE.equals(channel)) {
            String imageBase64 = new String(message, StandardCharsets.UTF_8);
            receiveScreenshot(player.getUniqueId(), imageBase64);
        }
    }

    private void handleHello(Player player, byte[] message) {
        if (message == null || message.length == 0 || message.length > MAX_HELLO_BYTES) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[截图] 忽略玩家 " + player.getName() + " 的无效 hello 包");
            }
            return;
        }

        String hello = new String(message, StandardCharsets.UTF_8).trim();
        if (!hello.startsWith(MOD_HELLO_PREFIX)) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[截图] 忽略玩家 " + player.getName() + " 的未知 hello 标识: " + hello);
            }
            return;
        }

        String modVersion = hello.substring(MOD_HELLO_PREFIX.length()).trim();
        if (modVersion.isEmpty()) {
            modVersion = "unknown";
        }
        registerModPlayer(player.getUniqueId(), modVersion);
    }

    public void registerModPlayer(UUID playerUuid, String version) {
        modInstalledPlayers.put(playerUuid, version);
        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[截图] 玩家 " + playerUuid + " 已注册视觉附属 mod，版本=" + version);
        }
    }

    public void unregisterPlayer(UUID playerUuid) {
        modInstalledPlayers.remove(playerUuid);
        CompletableFuture<String> pending = pendingRequests.remove(playerUuid);
        if (pending != null && !pending.isDone()) {
            pending.complete(null);
        }
    }

    public boolean hasModInstalled(Player player) {
        return modInstalledPlayers.containsKey(player.getUniqueId());
    }

    public String getInstalledModVersion(Player player) {
        return modInstalledPlayers.get(player.getUniqueId());
    }

    public CompletableFuture<String> requestScreenshot(Player player) {
        UUID uuid = player.getUniqueId();

        if (!hasModInstalled(player)) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[截图] 玩家 " + player.getName() + " 未安装附属 mod，跳过截图请求");
            }
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<String> existing = pendingRequests.get(uuid);
        if (existing != null && !existing.isDone()) {
            existing.complete(null);
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(uuid, future);

        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    player.sendPluginMessage(plugin, CHANNEL_REQUEST, new byte[0]);
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("[截图] 已向玩家 " + player.getName()
                                + " 发送截图请求，modVersion=" + getInstalledModVersion(player)
                                + "，等待回传（超时 " + SCREENSHOT_TIMEOUT_MS + "ms）");
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[截图] 发送截图请求包失败: " + e.getMessage());
                    pendingRequests.remove(uuid);
                    future.complete(null);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("[截图] 调度截图请求失败: " + e.getMessage());
            pendingRequests.remove(uuid);
            return CompletableFuture.completedFuture(null);
        }

        future.completeOnTimeout(null, SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((result, throwable) -> pendingRequests.remove(uuid, future));

        return future;
    }

    public void receiveScreenshot(UUID playerUuid, String imageBase64) {
        CompletableFuture<String> future = pendingRequests.remove(playerUuid);
        if (future != null && !future.isDone()) {
            if (imageBase64 != null && imageBase64.length() > 4 * 1024 * 1024) {
                plugin.getLogger().warning("[截图] 玩家 " + playerUuid + " 回传的截图数据过大（" + imageBase64.length() + " chars），已丢弃");
                future.complete(null);
                return;
            }
            future.complete(imageBase64);
            if (configManager.isDebugMode()) {
                int sizeKb = imageBase64 == null ? 0 : imageBase64.length() * 3 / 4 / 1024;
                plugin.getLogger().info("[截图] 收到玩家 " + playerUuid + " 的截图，约 " + sizeKb + " KB");
            }
        } else if (configManager.isDebugMode()) {
            plugin.getLogger().info("[截图] 收到玩家 " + playerUuid + " 的截图，但已超时或无等待请求，丢弃");
        }
    }
}
