package com.mcaiassistant.mcaiassistant;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * 截图管理器
 * 通过 Plugin Messaging Channel 向客户端附属 mod 请求截图，并等待回传结果。
 * 若客户端未安装附属 mod 或超时，则回退到无图片模式。
 *
 * 通道协议：
 *   S→C  mcai:vision/request  (无 payload，仅触发截图)
 *   C→S  mcai:vision/response (VarLong requestId + VarInt chunkIndex + VarInt chunkCount + int crc32 + UTF-8 base64 分片)
 *   C→S  mcai:vision/hello    (客户端能力声明)
 */
public class ScreenshotManager implements PluginMessageListener {

    public static final String CHANNEL_REQUEST = "mcai:vision/request";
    public static final String CHANNEL_RESPONSE = "mcai:vision/response";
    public static final String CHANNEL_HELLO = "mcai:vision/hello";

    private static final long SCREENSHOT_TIMEOUT_MS = 5000;
    private static final int MAX_HELLO_BYTES = 256;
    private static final String MOD_HELLO_PREFIX = "mcai-vision:";
    private static final int MAX_RESPONSE_CHUNK_BYTES = 29_990;
    private static final int MAX_RESPONSE_CHUNKS = 256;
    private static final int MAX_BASE64_TOTAL_CHARS = 4 * 1024 * 1024;

    private final Map<UUID, String> modInstalledPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

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

        pendingRequests.values().forEach(pending -> {
            if (pending != null) {
                pending.future.complete(null);
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
            handleScreenshotChunk(player, message);
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

    private void handleScreenshotChunk(Player player, byte[] message) {
        UUID playerUuid = player.getUniqueId();
        PendingRequest pending = pendingRequests.get(playerUuid);
        if (pending == null || pending.future.isDone()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[截图] 收到玩家 " + player.getName() + " 的截图分片，但当前无等待请求，已忽略");
            }
            return;
        }

        if (message == null || message.length == 0) {
            failPendingRequest(playerUuid, pending, "收到空截图分片");
            return;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(message);
            long requestId = readVarLong(buffer);
            int chunkIndex = readVarInt(buffer);
            int chunkCount = readVarInt(buffer);
            int crc32 = buffer.getInt();
            int chunkBytesLength = readVarInt(buffer);
            if (chunkBytesLength < 0 || chunkBytesLength > buffer.remaining()) {
                throw new IllegalArgumentException("字符串字节长度非法: " + chunkBytesLength + ", remaining=" + buffer.remaining());
            }

            byte[] chunkBytes = new byte[chunkBytesLength];
            buffer.get(chunkBytes);
            String chunk = new String(chunkBytes, StandardCharsets.UTF_8);
            receiveScreenshotChunk(playerUuid, requestId, chunkIndex, chunkCount, crc32, chunk);
        } catch (Exception e) {
            failPendingRequest(playerUuid, pending, "解析截图分片失败: " + e.getMessage());
        }
    }

    public void registerModPlayer(UUID playerUuid, String version) {
        modInstalledPlayers.put(playerUuid, version);
        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[截图] 玩家 " + playerUuid + " 已注册视觉附属 mod，版本=" + version);
        }
    }

    public void unregisterPlayer(UUID playerUuid) {
        modInstalledPlayers.remove(playerUuid);
        PendingRequest pending = pendingRequests.remove(playerUuid);
        if (pending != null) {
            pending.future.complete(null);
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

        PendingRequest previous = pendingRequests.remove(uuid);
        if (previous != null) {
            previous.future.complete(null);
        }

        PendingRequest pending = new PendingRequest(new CompletableFuture<>());
        pendingRequests.put(uuid, pending);

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
                    failPendingRequest(uuid, pending, "发送截图请求包失败: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            failPendingRequest(uuid, pending, "调度截图请求失败: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }

        pending.future.completeOnTimeout(null, SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((result, throwable) -> pendingRequests.remove(uuid, pending));

        return pending.future;
    }

    public void receiveScreenshotChunk(UUID playerUuid, long requestId, int chunkIndex, int chunkCount, int crc32, String chunk) {
        PendingRequest pending = pendingRequests.get(playerUuid);
        if (pending == null || pending.future.isDone()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[截图] 收到玩家 " + playerUuid + " 的截图分片，但已超时或无等待请求，丢弃");
            }
            return;
        }

        if (pending.requestId == null) {
            pending.requestId = requestId;
        } else if (pending.requestId.longValue() != requestId) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[截图] 丢弃玩家 " + playerUuid + " 的过期/串线截图分片，请求=" + requestId + "，当前请求=" + pending.requestId);
            }
            return;
        }

        if (chunkCount <= 0 || chunkCount > MAX_RESPONSE_CHUNKS || chunkIndex < 0 || chunkIndex >= chunkCount) {
            failPendingRequest(playerUuid, pending, "截图分片索引非法: index=" + chunkIndex + ", count=" + chunkCount);
            return;
        }

        if (chunk == null || chunk.length() > MAX_RESPONSE_CHUNK_BYTES) {
            failPendingRequest(playerUuid, pending, "截图分片长度非法: " + (chunk == null ? -1 : chunk.length()));
            return;
        }

        if (pending.assembly == null) {
            pending.assembly = new PendingScreenshotAssembly(chunkCount, crc32);
        } else if (pending.assembly.chunkCount != chunkCount || pending.assembly.crc32 != crc32) {
            failPendingRequest(playerUuid, pending, "截图分片元数据不一致，已中止本次传输");
            return;
        }

        PendingScreenshotAssembly.AddChunkResult addResult = pending.assembly.addChunk(chunkIndex, chunk, MAX_BASE64_TOTAL_CHARS);
        if (addResult == PendingScreenshotAssembly.AddChunkResult.TOO_LARGE) {
            failPendingRequest(playerUuid, pending, "截图分片累计过大，已丢弃");
            return;
        }
        if (addResult == PendingScreenshotAssembly.AddChunkResult.CONFLICT) {
            failPendingRequest(playerUuid, pending, "同一分片索引收到不一致内容，已中止本次传输");
            return;
        }

        if (pending.assembly.isComplete()) {
            String imageBase64 = pending.assembly.join();
            if (imageBase64 == null) {
                failPendingRequest(playerUuid, pending, "截图分片未完整拼接");
                return;
            }
            if (computeCrc32(imageBase64) != pending.assembly.crc32) {
                failPendingRequest(playerUuid, pending, "截图 CRC32 校验失败，已丢弃");
                return;
            }

            pending.future.complete(imageBase64);
            pendingRequests.remove(playerUuid, pending);
            if (configManager.isDebugMode()) {
                int sizeKb = imageBase64.length() * 3 / 4 / 1024;
                plugin.getLogger().info("[截图] 收到玩家 " + playerUuid + " 的截图，请求=" + requestId
                        + "，共 " + chunkCount + " 片，约 " + sizeKb + " KB，crc32=" + Integer.toUnsignedString(crc32));
            }
        }
    }

    private void failPendingRequest(UUID playerUuid, PendingRequest pending, String reason) {
        plugin.getLogger().warning("[截图] 玩家 " + playerUuid + " 的截图请求失败: " + reason);
        pendingRequests.remove(playerUuid, pending);
        pending.future.complete(null);
    }

    private static int computeCrc32(String base64) {
        CRC32 crc32 = new CRC32();
        crc32.update(base64.getBytes(StandardCharsets.UTF_8));
        return (int) crc32.getValue();
    }

    private static int readVarInt(ByteBuffer buffer) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            if (!buffer.hasRemaining()) {
                throw new IllegalArgumentException("VarInt 数据不完整");
            }
            read = buffer.get();
            int value = read & 0b01111111;
            result |= value << (7 * numRead);

            numRead++;
            if (numRead > 5) {
                throw new IllegalArgumentException("VarInt 过长");
            }
        } while ((read & 0b10000000) != 0);

        return result;
    }

    private static long readVarLong(ByteBuffer buffer) {
        int numRead = 0;
        long result = 0;
        byte read;
        do {
            if (!buffer.hasRemaining()) {
                throw new IllegalArgumentException("VarLong 数据不完整");
            }
            read = buffer.get();
            long value = read & 0b01111111L;
            result |= value << (7 * numRead);

            numRead++;
            if (numRead > 10) {
                throw new IllegalArgumentException("VarLong 过长");
            }
        } while ((read & 0b10000000) != 0);

        return result;
    }

    private static final class PendingRequest {
        private final CompletableFuture<String> future;
        private volatile Long requestId;
        private volatile PendingScreenshotAssembly assembly;

        private PendingRequest(CompletableFuture<String> future) {
            this.future = future;
        }
    }

    private static final class PendingScreenshotAssembly {
        private final String[] chunks;
        private final int chunkCount;
        private final int crc32;
        private int receivedCount;
        private int totalChars;

        private PendingScreenshotAssembly(int chunkCount, int crc32) {
            this.chunkCount = chunkCount;
            this.crc32 = crc32;
            this.chunks = new String[chunkCount];
        }

        private synchronized AddChunkResult addChunk(int chunkIndex, String chunk, int maxTotalChars) {
            String existing = chunks[chunkIndex];
            if (existing != null) {
                return existing.equals(chunk) ? AddChunkResult.DUPLICATE : AddChunkResult.CONFLICT;
            }
            chunks[chunkIndex] = chunk;
            receivedCount++;
            totalChars += chunk.length();
            return totalChars <= maxTotalChars ? AddChunkResult.ADDED : AddChunkResult.TOO_LARGE;
        }

        private synchronized boolean isComplete() {
            return receivedCount == chunkCount;
        }

        private synchronized String join() {
            StringBuilder builder = new StringBuilder(totalChars);
            for (String chunk : chunks) {
                if (chunk == null) {
                    return null;
                }
                builder.append(chunk);
            }
            return builder.toString();
        }

        private enum AddChunkResult {
            ADDED,
            DUPLICATE,
            CONFLICT,
            TOO_LARGE
        }
    }
}
