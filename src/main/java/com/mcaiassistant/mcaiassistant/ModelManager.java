package com.mcaiassistant.mcaiassistant;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 模型管理器
 * - 运行时覆盖当前聊天使用的模型（不写回配置）
 * - 缓存 /models 列表，提供 Tab 补全候选
 */
public class ModelManager {

    private final JavaPlugin plugin;
    private ConfigManager configManager;
    private final Gson gson = new Gson();
    private OkHttpClient httpClient;

    // 模型列表缓存（只存模型 id）
    private final List<String> cachedModels = new ArrayList<>();
    private volatile long lastFetchAtMillis = 0L;
    private volatile long cacheTtlMillis = 10 * 60 * 1000L; // 默认 10 分钟

    // 运行时覆盖的聊天模型（null 表示未覆盖，使用配置默认）
    private volatile String overrideChatModelId = null;

    public ModelManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.httpClient = createHttpClient();
    }

    private OkHttpClient createHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(configManager.getConnectTimeout(), TimeUnit.SECONDS)
                .readTimeout(configManager.getReadTimeout(), TimeUnit.SECONDS)
                .writeTimeout(configManager.getWriteTimeout(), TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true);

        // 连接池
        ConnectionPool pool = new ConnectionPool(
                configManager.getConnectionPoolMaxIdle(),
                configManager.getConnectionKeepAliveDuration(),
                TimeUnit.SECONDS
        );
        builder.connectionPool(pool);

        // 调度器（并发限制与 AiApiClient 保持同样的来源配置）
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(configManager.getMaxRequests());
        dispatcher.setMaxRequestsPerHost(configManager.getMaxRequestsPerHost());
        builder.dispatcher(dispatcher);

        return builder.build();
    }

    /**
     * 更新配置（重建 HTTP 客户端）
     */
    public void updateConfig(ConfigManager newConfigManager) {
        this.configManager = newConfigManager;
        this.httpClient = createHttpClient();
    }

    /**
     * 获取当前“有效的聊天模型”
     * - 若存在运行时覆盖，优先返回覆盖
     * - 否则返回配置中的默认模型
     */
    public String getEffectiveChatModel() {
        String override = this.overrideChatModelId;
        return (override != null && !override.isEmpty()) ? override : configManager.getModel();
    }

    /**
     * 设置运行时覆盖的聊天模型（不校验是否存在于缓存列表）
     */
    public void setOverrideChatModelId(String modelId) {
        this.overrideChatModelId = (modelId == null || modelId.isEmpty()) ? null : modelId;
    }

    /**
     * 清除运行时覆盖（恢复为配置默认）
     */
    public void clearOverrideChatModel() {
        this.overrideChatModelId = null;
    }

    /**
     * 返回当前覆盖的聊天模型（可能为 null）
     */
    public String getOverrideChatModelId() {
        return this.overrideChatModelId;
    }

    /**
     * 获取一个模型列表快照（不会阻塞网络）
     * 若缓存过期，将异步刷新，但本次仍返回旧快照或空列表
     */
    public List<String> getModelsSnapshotAndRefreshIfNeeded() {
        if (!isCacheValid()) {
            refreshModelsAsync();
        }
        synchronized (cachedModels) {
            return new ArrayList<>(cachedModels);
        }
    }

    /**
     * 仅检查缓存中是否包含该模型（忽略大小写）
     */
    public boolean cachedListContainsIgnoreCase(String modelId) {
        if (modelId == null) return false;
        synchronized (cachedModels) {
            for (String id : cachedModels) {
                if (id.equalsIgnoreCase(modelId)) return true;
            }
        }
        return false;
    }

    /**
     * 异步刷新 /models 列表
     */
    public void refreshModelsAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> fresh = fetchModelsOnce();
                synchronized (cachedModels) {
                    cachedModels.clear();
                    // 排序：长度短优先，再字典序，提升常见短 id 的补全体验
                    fresh.sort(Comparator.<String>comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
                    cachedModels.addAll(fresh);
                }
                lastFetchAtMillis = System.currentTimeMillis();
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("[ModelManager] 模型列表刷新成功，共 " + fresh.size() + " 个");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[ModelManager] 刷新模型列表失败: " + e.getMessage());
                if (configManager.isDebugMode()) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 设置缓存 TTL（毫秒）
     */
    public void setCacheTtlMillis(long ttlMillis) {
        this.cacheTtlMillis = Math.max(30_000L, ttlMillis); // 下限 30 秒，防止过于频繁
    }

    /**
     * 判断缓存是否有效
     */
    public boolean isCacheValid() {
        long age = System.currentTimeMillis() - lastFetchAtMillis;
        return lastFetchAtMillis > 0 && age <= cacheTtlMillis;
    }

    /**
     * 立即同步拉取一次 /models
     */
    private List<String> fetchModelsOnce() throws IOException {
        String url = configManager.getApiUrl();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        url = url + "/models";

        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + configManager.getApiKey())
                .addHeader("Accept", "application/json");

        // 可选的“浏览器”风格首部，不强制
        if (configManager.isSimulateBrowser()) {
            builder.addHeader("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        }

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " - " + response.message());
            }
            String body = response.body() != null ? response.body().string() : "";
            if (body.isEmpty()) {
                return Collections.emptyList();
            }
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || !root.has("data")) {
                return Collections.emptyList();
            }
            JsonArray data = root.getAsJsonArray("data");
            List<String> result = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                try {
                    JsonObject obj = data.get(i).getAsJsonObject();
                    if (obj.has("id")) {
                        String id = obj.get("id").getAsString();
                        if (id != null && !id.isEmpty()) {
                            result.add(id);
                        }
                    }
                } catch (Exception ignore) {
                    // 跳过异常条目
                }
            }
            return result;
        }
    }
}