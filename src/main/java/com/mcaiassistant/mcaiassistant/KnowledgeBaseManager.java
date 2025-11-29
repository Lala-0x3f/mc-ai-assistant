package com.mcaiassistant.mcaiassistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 本地知识库管理器：负责扫描、缓存并热更新 knowledge base 目录下的 Markdown 文档，
 * 同时提供 AI 搜索与直接检索能力。
 */

public class KnowledgeBaseManager {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final JavaPlugin plugin;
    private final ExecutorService searchExecutor;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private ConfigManager configManager;
    private OkHttpClient aiHttpClient;
    private File knowledgeFolder;
    private File cacheFolder;
    private File mappingFile;
    private File combinedCacheFile;
    private BukkitTask hotReloadTask;
    private volatile String combinedPayload = "";
    private final Map<String, KnowledgeDocument> documents = new HashMap<>();

    public KnowledgeBaseManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.searchExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "knowledge-search-" + System.nanoTime());
            thread.setDaemon(true);
            return thread;
        });
        this.aiHttpClient = buildHttpClient();
    }

    /**

     * 初始化本地知识库：创建目录、首次扫描并启动热更新任务。

     */

    public void initialize() {

        ensureFolders();

        rescanKnowledgeBase(true);

        scheduleHotReload();

    }



    /**

     * 更新配置后重新创建 HTTP 客户端并刷新缓存。

     */

    public void updateConfig(ConfigManager newConfigManager) {

        this.configManager = newConfigManager;

        this.aiHttpClient = buildHttpClient();

        ensureFolders();

        rescanKnowledgeBase(true);

        scheduleHotReload();

    }



    /**

     * 根据查询词执行知识库检索，返回 AI 摘要与直接命中片段。

     */

    public KnowledgeSearchResult searchKnowledge(String query) {
        if (!configManager.isKnowledgeEnabled()) {
            return KnowledgeSearchResult.empty();
        }
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            return KnowledgeSearchResult.empty();
        }

        String snapshotKnowledge = combinedPayload;
        if (snapshotKnowledge.isEmpty()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库] 本地 knowledge base 为空，跳过此次检索");
            }
            return KnowledgeSearchResult.empty();
        }

        CompletableFuture<String> aiFuture = CompletableFuture.supplyAsync(() -> {
            if (!configManager.isKnowledgeAiSearchEnabled()) {
                return null;
            }
            return callAiSearch(normalized, snapshotKnowledge);
        }, searchExecutor);

        CompletableFuture<List<KnowledgeSnippet>> directFuture = CompletableFuture.supplyAsync(() -> {
            if (!configManager.isKnowledgeDirectSearchEnabled()) {
                return Collections.emptyList();
            }
            return runDirectSearch(normalized);
        }, searchExecutor);

        String aiAnswer = null;
        List<KnowledgeSnippet> snippets = Collections.emptyList();

        try {
            aiAnswer = aiFuture.get(configManager.getKnowledgeAiTimeoutSeconds() + 2L, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[知识库] 调用 AI 搜索失败: " + e.getMessage());
            }
            aiFuture.cancel(true);
        }

        try {
            snippets = directFuture.get(configManager.getKnowledgeAiTimeoutSeconds() + 2L, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[知识库] 直接检索失败: " + e.getMessage());
            }
            directFuture.cancel(true);
        }

        return new KnowledgeSearchResult(aiAnswer, snippets);
    }

    /**
     * 关闭并释放线程池与 HTTP 连接资源。
     */
    public void shutdown() {
        if (hotReloadTask != null) {
            hotReloadTask.cancel();
        }
        searchExecutor.shutdownNow();
        if (aiHttpClient != null) {
            aiHttpClient.dispatcher().executorService().shutdown();
            aiHttpClient.connectionPool().evictAll();
        }
    }

    private OkHttpClient buildHttpClient() {
        int timeout = Math.max(1, configManager.getKnowledgeAiTimeoutSeconds());
        return new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private void ensureFolders() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("[知识库] 无法创建插件数据目录，请检查文件权限");
        }
        knowledgeFolder = new File(plugin.getDataFolder(), configManager.getKnowledgeFolderName());
        cacheFolder = new File(plugin.getDataFolder(), configManager.getKnowledgeCacheDirName());
        mappingFile = new File(plugin.getDataFolder(), configManager.getKnowledgeMappingFileName());
        combinedCacheFile = new File(cacheFolder, "knowledge-base.txt");

        if (!knowledgeFolder.exists() && knowledgeFolder.mkdirs()) {
            plugin.getLogger().info("[知识库] 已创建 knowledge base 目录: " + knowledgeFolder.getAbsolutePath());
        }
        if (!cacheFolder.exists() && cacheFolder.mkdirs()) {
            plugin.getLogger().info("[知识库] 已创建 knowledge cache 目录: " + cacheFolder.getAbsolutePath());
        }
        if (!mappingFile.exists()) {
            try {
                if (mappingFile.createNewFile() && configManager.isDebugMode()) {
                    plugin.getLogger().info("[知识库] 已创建 knowledge mapping 文件: " + mappingFile.getAbsolutePath());
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[知识库] 无法创建 mapping 文件: " + e.getMessage());
            }
        }
    }

    private void rescanKnowledgeBase(boolean forceLog) {
        if (!knowledgeFolder.exists()) {
            return;
        }

        Map<String, KnowledgeDocument> latest = new HashMap<>();
        try {
            Files.walk(knowledgeFolder.toPath())
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .forEach(path -> {
                        KnowledgeDocument doc = buildDocument(path);
                        if (doc != null) {
                            latest.put(doc.getRelativePath(), doc);
                        }
                    });
        } catch (IOException e) {
            plugin.getLogger().warning("[知识库] 扫描 knowledge base 失败: " + e.getMessage());
            return;
        }

        boolean changed;
        lock.writeLock().lock();
        try {
            changed = hasKnowledgeChanged(latest);
            if (changed) {
                documents.clear();
                documents.putAll(latest);
                rebuildCombinedPayload();
                persistCacheMetadata();
            }
        } finally {
            lock.writeLock().unlock();
        }

        if ((changed || forceLog) && configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库] 已加载文档数量: " + documents.size());
        }
    }

    private boolean hasKnowledgeChanged(Map<String, KnowledgeDocument> latest) {
        if (documents.size() != latest.size()) {
            return true;
        }
        for (Map.Entry<String, KnowledgeDocument> entry : latest.entrySet()) {
            KnowledgeDocument existing = documents.get(entry.getKey());
            if (existing == null || !Objects.equals(existing.getHash(), entry.getValue().getHash())) {
                return true;
            }
        }
        return false;
    }

    private KnowledgeDocument buildDocument(Path path) {
        try {
            byte[] contentBytes = Files.readAllBytes(path);
            String content = new String(contentBytes, StandardCharsets.UTF_8);
            String md5 = calculateMd5(contentBytes);
            String relativePath = knowledgeFolder.toPath().relativize(path).toString().replace("\\", "/");

            File cachedFile = new File(cacheFolder, md5 + ".txt");
            Files.writeString(cachedFile.toPath(), content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            return new KnowledgeDocument(relativePath, md5, content, path.toFile().lastModified(), cachedFile.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("[知识库] 读取文档失败: " + path + " - " + e.getMessage());
            return null;
        }
    }

    private void rebuildCombinedPayload() {
        StringBuilder builder = new StringBuilder();
        documents.values().stream()
                .sorted(Comparator.comparing(KnowledgeDocument::getRelativePath))
                .forEach(doc -> {
                    builder.append("### ").append(doc.getRelativePath()).append("\n");
                    builder.append(doc.getContent()).append("\n\n");
                });

        int limit = Math.max(1000, configManager.getKnowledgeCombinedCharLimit());
        String combined = builder.toString();
        if (combined.length() > limit) {
            combined = combined.substring(0, limit);
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[知识库] 合并后的文本过长，已限制为 " + limit + " 字符");
            }
        }
        combinedPayload = combined.trim();

        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs();
        }
        try {
            Files.writeString(combinedCacheFile.toPath(), combinedPayload, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("[知识库] 写入 knowledge-base.txt 失败: " + e.getMessage());
        }
    }

    private void persistCacheMetadata() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (KnowledgeDocument doc : documents.values()) {
            String baseKey = "documents." + doc.getRelativePath();
            yaml.set(baseKey + ".hash", doc.getHash());
            yaml.set(baseKey + ".cache_file", doc.getCacheFileName());
            yaml.set(baseKey + ".last_modified", doc.getLastModified());
        }
        yaml.set("updated_at", Instant.now().toString());
        try {
            yaml.save(mappingFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[知识库] 写入 knowledge-cache.yml 失败: " + e.getMessage());
        }
    }

    private void scheduleHotReload() {
        if (hotReloadTask != null) {
            hotReloadTask.cancel();
        }
        int interval = Math.max(200, configManager.getKnowledgeRefreshIntervalTicks());
        hotReloadTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                rescanKnowledgeBase(false);
            } catch (Exception e) {
                plugin.getLogger().warning("[知识库] 热更新任务执行失败: " + e.getMessage());
            }
        }, interval, interval);
    }

    private String calculateMd5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not supported", e);
        }
    }

    private String callAiSearch(String query, String knowledge) {
        String apiKey = configManager.getKnowledgeAiApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库] knowledge.ai_search.api_key 未配置，将跳过 AI 搜索");
            }
            return null;
        }

        String apiUrl = configManager.getKnowledgeAiApiUrl();
        // 配置可能已写成 "models/xxx"，为避免重复拼接，这里去掉前缀后再组合。
        String model = configManager.getKnowledgeAiModel();
        String normalizedModel = model.startsWith("models/") ? model.substring("models/".length()) : model;
        String endpoint = apiUrl.endsWith("/") ? apiUrl + "v1beta/models/" + normalizedModel + ":generateContent" :
                apiUrl + "/v1beta/models/" + normalizedModel + ":generateContent";
        endpoint = endpoint + "?key=" + apiKey;

        JsonObject textPart = new JsonObject();
        textPart.addProperty("text",
                "请把 knowledge base 中的内容作为上下文来回答玩家，如果无法找到相关资料请返回 null。"
                        + "<knowledge_base>\n" + knowledge + "\n</knowledge_base>\n\n"
                        + "用户问题：" + query + "回答：");

        JsonArray parts = new JsonArray();
        parts.add(textPart);

        JsonObject content = new JsonObject();
        content.add("parts", parts);

        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject requestBody = new JsonObject();
        requestBody.add("contents", contents);

        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(requestBody.toString(), JSON))
                .build();

        try (Response response = aiHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[知识库] AI 搜索返回异常: " + response.code() + " " + response.message());
                }
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            String responseText = body.string();
            return extractAiAnswer(responseText);
        } catch (IOException e) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[知识库] 调用 AI 搜索出现异常: " + e.getMessage());
            }
            return null;
        }
    }

    private String extractAiAnswer(String responseText) {
        try {
            JsonObject json = JsonParser.parseString(responseText).getAsJsonObject();
            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) {
                return null;
            }
            JsonObject candidate = candidates.get(0).getAsJsonObject();
            JsonObject content = candidate.getAsJsonObject("content");
            if (content == null) {
                return null;
            }
            JsonArray parts = content.getAsJsonArray("parts");
            if (parts == null || parts.size() == 0) {
                return null;
            }
            JsonElement partText = parts.get(0).getAsJsonObject().get("text");
            if (partText == null) {
                return null;
            }
            String answer = partText.getAsString().trim();
            return answer.isEmpty() ? null : answer;
        } catch (Exception e) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[知识库] 解析 AI 响应失败: " + e.getMessage());
            }
            return null;
        }
    }

    private List<KnowledgeSnippet> runDirectSearch(String query) {
        List<KnowledgeSnippet> results = new ArrayList<>();
        int maxSnippets = Math.max(1, configManager.getKnowledgeDirectMaxSnippets());
        int radius = Math.max(80, configManager.getKnowledgeDirectContextRadius());
        String lowerQuery = query.toLowerCase(Locale.ROOT);

        lock.readLock().lock();
        try {
            for (KnowledgeDocument doc : documents.values()) {
                if (results.size() >= maxSnippets) {
                    break;
                }
                String content = doc.getContent();
                String lowerContent = content.toLowerCase(Locale.ROOT);
                int idx = lowerContent.indexOf(lowerQuery);
                if (idx < 0) {
                    continue;
                }
                int start = Math.max(0, idx - radius);
                int end = Math.min(content.length(), idx + lowerQuery.length() + radius);
                String snippet = content.substring(start, end).trim();
                results.add(new KnowledgeSnippet(doc.getRelativePath(), snippet));
            }
        } finally {
            lock.readLock().unlock();
        }
        return results;
    }

    private static class KnowledgeDocument {
        private final String relativePath;
        private final String hash;
        private final String content;
        private final long lastModified;
        private final String cacheFileName;

        KnowledgeDocument(String relativePath, String hash, String content, long lastModified, String cacheFileName) {
            this.relativePath = relativePath;
            this.hash = hash;
            this.content = content;
            this.lastModified = lastModified;
            this.cacheFileName = cacheFileName;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public String getHash() {
            return hash;
        }

        public String getContent() {
            return content;
        }

        public long getLastModified() {
            return lastModified;
        }

        public String getCacheFileName() {
            return cacheFileName;
        }
    }
}
