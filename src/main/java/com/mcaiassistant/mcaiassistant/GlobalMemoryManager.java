package com.mcaiassistant.mcaiassistant;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 全局记忆管理器：基于简单规则的自动摘要、去重与限量持久化。
 * 设计目标：不阻塞主线程、写入轻量、只记录高信息密度内容。
 */
public class GlobalMemoryManager {

    private static final List<String> DEFAULT_KEYWORDS = List.of(
        "规则", "教程", "指南", "坐标", "位置", "价格", "费用", "冷却", "cd", "指令", "命令", "配方", "步骤", "限制", "权限"
    );

    private final JavaPlugin plugin;
    private ConfigManager configManager;
    private final File memoryFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<MemoryEntry> entries = new ArrayList<>();
    private final Set<String> normalizedCache = new HashSet<>();

    public GlobalMemoryManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.memoryFile = new File(plugin.getDataFolder(), "global-memory.yml");
    }

    /**
     * 初始化：创建文件并加载已保存的记忆。
     */
    public void initialize() {
        if (!memoryFile.exists()) {
            try {
                if (memoryFile.getParentFile() != null) {
                    memoryFile.getParentFile().mkdirs();
                }
                memoryFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("[全局记忆] 创建存储文件失败: " + e.getMessage());
            }
        }
        loadFromDisk();
    }

    public void updateConfig(ConfigManager newConfig) {
        this.configManager = newConfig;
    }

    /**
     * 在异步线程尝试捕获高信息密度的记忆。
     */
    public void captureIfValuableAsync(String playerName, String message) {
        if (!configManager.isGlobalMemoryEnabled()) {
            return;
        }
        String normalizedMessage = normalizeForProcess(message);
        if (normalizedMessage.isEmpty()) {
            return;
        }
        if (!shouldCapture(normalizedMessage)) {
            return;
        }
        CompletableFuture.runAsync(() -> captureInternal(playerName, normalizedMessage));
    }

    /**
     * 直接按摘要写入（用于外部判定后的写入，跳过规则判定）
     */
    public void captureBySummaryAsync(String playerName, String summary) {
        if (!configManager.isGlobalMemoryEnabled()) {
            return;
        }
        if (summary == null || summary.trim().isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(() -> captureInternal(playerName, summary.trim()));
    }

    /**
     * 为当前查询返回有限的全局记忆片段，前置格式化为简短文本，避免挤占上下文。
     */
    public List<String> pickMemories(String query) {
        if (!configManager.isGlobalMemoryEnabled()) {
            return Collections.emptyList();
        }
        String normalizedQuery = normalizeForProcess(query);
        if (normalizedQuery.isEmpty()) {
            return Collections.emptyList();
        }

        int maxItems = configManager.getGlobalMemoryInjectCount();
        int maxChars = configManager.getGlobalMemoryInjectCharLimit();
        List<String> result = new ArrayList<>();

        lock.readLock().lock();
        try {
            List<ScoredEntry> scored = new ArrayList<>();
            for (MemoryEntry entry : entries) {
                int score = scoreRelevance(normalizedQuery, entry);
                if (score <= 0) {
                    continue;
                }
                scored.add(new ScoredEntry(entry, score));
            }

            scored.sort(Comparator
                .comparingInt(ScoredEntry::score).reversed()
                .thenComparingLong(se -> se.entry.createdAt));

            int remainingChars = maxChars;
            for (ScoredEntry se : scored) {
                if (result.size() >= maxItems) {
                    break;
                }
                String text = se.entry.summary;
                if (text.length() > remainingChars) {
                    continue;
                }
                result.add(text);
                remainingChars -= text.length();
            }
        } finally {
            lock.readLock().unlock();
        }

        return result;
    }

    private boolean shouldCapture(String message) {
        if (message.length() < configManager.getGlobalMemoryMinLength()) {
            return false;
        }
        int score = 0;
        boolean hasNumber = message.matches(".*\\d+.*");
        boolean keywordHit = false;
        if (message.matches(".*\\d+.*")) {
            score += 2;
        }
        for (String keyword : getDefaultKeywords()) {
            if (keyword.isEmpty()) {
                continue;
            }
            if (message.toLowerCase().contains(keyword.toLowerCase())) {
                score += 2;
                keywordHit = true;
                break;
            }
        }
        if (message.contains(":") || message.contains("：")) {
            score += 1;
        }
        // 只有命中关键词或数字，才允许写入，避免短期无关信息
        if (!hasNumber && !keywordHit) {
            return false;
        }
        return score >= configManager.getGlobalMemoryMinInfoScore();
    }

    private void captureInternal(String playerName, String message) {
        String summary = summarize(message, configManager.getGlobalMemoryMaxSummaryLength());
        if (summary.isEmpty()) {
            return;
        }

        String normalizedKey = normalizeKey(summary);
        lock.writeLock().lock();
        try {
            // 如果与现有高相似度，直接更新该条目而不是新增
            MemoryEntry similar = findSimilarEntry(summary);
            if (similar != null) {
                entries.remove(similar);
                normalizedCache.remove(similar.normalizedKey);
            } else if (normalizedCache.contains(normalizedKey)) {
                return;
            }
            if (entries.size() >= configManager.getGlobalMemoryMaxEntries()) {
                // 按时间戳移除最早的记录，保持列表精简。
                entries.sort(Comparator.comparingLong(e -> e.createdAt));
                MemoryEntry removed = entries.remove(0);
                normalizedCache.remove(removed.normalizedKey);
            }

            MemoryEntry entry = new MemoryEntry(
                UUID.randomUUID().toString(),
                summary,
                normalizeKey(summary),
                playerName,
                Instant.now().toEpochMilli()
            );
            entries.add(entry);
            normalizedCache.add(entry.normalizedKey);
            saveToDisk();

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[全局记忆] 已记录: " + summary);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadFromDisk() {
        lock.writeLock().lock();
        try {
            entries.clear();
            normalizedCache.clear();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(memoryFile);
            List<Map<?, ?>> list = yaml.getMapList("entries");
            if (list == null) {
                return;
            }
            for (Map<?, ?> item : list) {
                String id = safeToString(item.get("id"), UUID.randomUUID().toString());
                String summary = safeToString(item.get("summary"), "");
                String player = safeToString(item.get("player"), "unknown");
                long createdAt = item.get("created_at") instanceof Number ? ((Number) item.get("created_at")).longValue() : System.currentTimeMillis();
                if (summary.isEmpty()) {
                    continue;
                }
                MemoryEntry entry = new MemoryEntry(id, summary, normalizeKey(summary), player, createdAt);
                entries.add(entry);
                normalizedCache.add(entry.normalizedKey);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveToDisk() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            list.add(Map.of(
                "id", entry.id,
                "summary", entry.summary,
                "player", entry.player,
                "created_at", entry.createdAt
            ));
        }
        yaml.set("entries", list);
        try {
            yaml.save(memoryFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[全局记忆] 保存失败: " + e.getMessage());
        }
    }

    private int scoreRelevance(String query, MemoryEntry entry) {
        int score = 0;
        String qLower = query.toLowerCase();
        if (qLower.matches(".*\\d+.*") && entry.summary.matches(".*\\d+.*")) {
            score += 2;
        }
        for (String token : tokenize(qLower)) {
            if (token.isEmpty()) {
                continue;
            }
            if (entry.summary.toLowerCase().contains(token)) {
                score += 2;
                break;
            }
        }
        // 轻度倾向近期内容：越新加分越多，最多 +3
        long days = Math.max(0, (Instant.now().toEpochMilli() - entry.createdAt) / 1000 / 3600 / 24);
        return score;
    }

    /**
     * 查找与当前摘要高相似度的条目，用于更新而非新增。
     */
    private MemoryEntry findSimilarEntry(String rawSummary) {
        String normalized = normalizeForProcess(rawSummary);
        for (MemoryEntry entry : entries) {
            double sim = jaccardSimilarity(normalized, normalizeForProcess(entry.summary));
            if (sim >= 0.65) {
                return entry;
            }
        }
        return null;
    }

    private double jaccardSimilarity(String a, String b) {
        Set<String> setA = new HashSet<>(tokenize(a));
        Set<String> setB = new HashSet<>(tokenize(b));
        if (setA.isEmpty() || setB.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private List<String> tokenize(String text) {
        String[] parts = text.split("[\\s,，。:：;；]+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private List<String> getDefaultKeywords() {
        return DEFAULT_KEYWORDS;
    }

    private String normalizeForProcess(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private String summarize(String text, int maxLength) {
        String normalized = normalizeForProcess(text);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.min(normalized.length(), maxLength)).trim();
    }

    private String normalizeKey(String text) {
        return text.toLowerCase().replaceAll("\\s+", "");
    }

    private String safeToString(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
    }

    private record MemoryEntry(String id, String summary, String normalizedKey, String player, long createdAt) {}

    private record ScoredEntry(MemoryEntry entry, int score) {}
}
