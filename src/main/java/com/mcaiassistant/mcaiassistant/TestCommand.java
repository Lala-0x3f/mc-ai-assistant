package com.mcaiassistant.mcaiassistant;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * /aitest 指令
 * - /aitest all   同时测试模型与知识库
 * - /aitest model 仅测试模型可用性
 * - /aitest kb    仅测试知识库可用性
 * 仅 OP（mcaiassistant.test）可执行
 */
public class TestCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = Arrays.asList("all", "model", "kb");
    private static final int PREVIEW_LIMIT = 160;

    private final McAiAssistant plugin;
    private final ConfigManager configManager;
    private final AiApiClient aiApiClient;
    private final KnowledgeBaseManager knowledgeBaseManager;

    public TestCommand(McAiAssistant plugin, ConfigManager configManager,
                       AiApiClient aiApiClient, KnowledgeBaseManager knowledgeBaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.aiApiClient = aiApiClient;
        this.knowledgeBaseManager = knowledgeBaseManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 权限检查：默认仅 OP（mcaiassistant.test）
        if (!sender.hasPermission("mcaiassistant.test")) {
            sender.sendMessage(ChatColor.RED + "没有权限执行该命令。");
            return true;
        }

        String mode = args.length > 0 ? args[0].toLowerCase() : "all";
        if (!SUB_COMMANDS.contains(mode)) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /" + label + " <all|model|kb>");
            return true;
        }

        if ("all".equals(mode) || "model".equals(mode)) {
            runModelTest(sender);
        }
        if ("all".equals(mode) || "kb".equals(mode)) {
            runKnowledgeTest(sender);
        }
        return true;
    }

    /**
     * 模型可用性测试：异步调用一次聊天接口
     */
    private void runModelTest(CommandSender sender) {
        sender.sendMessage(ChatColor.GRAY + "[AI测试] 正在测试模型可用性...");

        CompletableFuture
                .supplyAsync(() -> {
                    long start = System.currentTimeMillis();
                    try {
                        String response = aiApiClient.sendMessage("健康检查：请回复任意内容。", null);
                        long duration = System.currentTimeMillis() - start;
                        return new ModelTestResult(true, response, duration, null);
                    } catch (Exception ex) {
                        return new ModelTestResult(false, null, 0, ex);
                    }
                })
                .exceptionally(ex -> new ModelTestResult(false, null, 0, ex))
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!result.success) {
                        sender.sendMessage(ChatColor.RED + "[AI测试] 模型可用性失败: " + result.getErrorMessage());
                        return;
                    }
                    String preview = toPreview(result.response);
                    sender.sendMessage(ChatColor.GREEN + "[AI测试] 模型可用 ✅  耗时 "
                            + ChatColor.WHITE + result.duration + " ms"
                            + ChatColor.DARK_GRAY + " | 响应预览: " + ChatColor.GRAY + preview);
                }));
    }

    /**
     * 知识库可用性测试：触发一次本地知识库检索
     */
    private void runKnowledgeTest(CommandSender sender) {
        sender.sendMessage(ChatColor.GRAY + "[AI测试] 正在测试知识库可用性...");

        CompletableFuture
                .supplyAsync(() -> {
                    if (knowledgeBaseManager == null) {
                        throw new IllegalStateException("知识库管理器未初始化");
                    }
                    long start = System.currentTimeMillis();
                    KnowledgeSearchResult result = knowledgeBaseManager.searchKnowledge("知识库健康检查");
                    long duration = System.currentTimeMillis() - start;
                    return new KnowledgeTestResult(result, duration, null);
                })
                .exceptionally(ex -> new KnowledgeTestResult(null, 0, ex))
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.error != null) {
                        sender.sendMessage(ChatColor.RED + "[AI测试] 知识库可用性失败: " + result.error.getMessage());
                        return;
                    }
                    KnowledgeSearchResult searchResult = result.result;
                    if (searchResult == null || searchResult.isEmpty()) {
                        sender.sendMessage(ChatColor.YELLOW + "[AI测试] 知识库未返回结果，请检查 knowledge.enabled 或数据是否存在。");
                        return;
                    }
                    int snippetCount = searchResult.getSnippets().size();
                    String aiAnswerPreview = searchResult.getAiAnswer() == null ? "无 AI 搜索结果" : toPreview(searchResult.getAiAnswer());

                    sender.sendMessage(ChatColor.GREEN + "[AI测试] 知识库可用 ✅  耗时 "
                            + ChatColor.WHITE + result.duration + " ms"
                            + ChatColor.DARK_GRAY + " | 片段数: " + ChatColor.WHITE + snippetCount);
                    sender.sendMessage(ChatColor.DARK_GRAY + "[AI测试] AI 搜索预览: " + ChatColor.GRAY + aiAnswerPreview);
                }));
    }

    private String toPreview(String text) {
        if (text == null) return "无响应";
        String trimmed = text.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= PREVIEW_LIMIT) {
            return trimmed;
        }
        return trimmed.substring(0, PREVIEW_LIMIT) + "...";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mcaiassistant.test")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase();
            return SUB_COMMANDS.stream()
                    .filter(opt -> opt.startsWith(prefix))
                    .toList();
        }
        return Collections.emptyList();
    }

    private static class ModelTestResult {
        final boolean success;
        final String response;
        final long duration;
        final Throwable error;

        ModelTestResult(boolean success, String response, long duration, Throwable error) {
            this.success = success;
            this.response = response;
            this.duration = duration;
            this.error = error;
        }

        String getErrorMessage() {
            return error == null ? "未知错误" : error.getMessage();
        }
    }

    private static class KnowledgeTestResult {
        final KnowledgeSearchResult result;
        final long duration;
        final Throwable error;

        KnowledgeTestResult(KnowledgeSearchResult result, long duration, Throwable error) {
            this.result = result;
            this.duration = duration;
            this.error = error;
        }
    }
}
