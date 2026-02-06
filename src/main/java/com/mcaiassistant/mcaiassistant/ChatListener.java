package com.mcaiassistant.mcaiassistant;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import com.mcaiassistant.mcaiassistant.EconomyManager;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 聊天事件监听器
 * 监听玩家聊天消息，检测 AI 触发关键词并处理 AI 响应
 */
public class ChatListener implements Listener {

    private final McAiAssistant plugin;
    private final ConfigManager configManager;
    private final ChatHistoryManager chatHistoryManager;
    private final AiApiClient aiApiClient;
    private final SearchApiClient searchApiClient;
    private final KnowledgeBaseManager knowledgeBaseManager;
    private final ImageApiClient imageApiClient;
    private final ToastNotification toastNotification;
    private final RateLimitManager rateLimitManager;
    private final EconomyManager economyManager;
    private final GlobalMemoryManager globalMemoryManager;
    private final CommandWhitelistManager commandWhitelistManager;
    private final McpManager mcpManager;
    
    // 智能匹配模式，避免匹配到 @airport 等词
    private static final Pattern SMART_AI_PATTERN = Pattern.compile("(?:^|\\s)@ai(?:\\s|$|[^a-zA-Z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SMART_SEARCH_PATTERN = Pattern.compile("(?:^|\\s)@search(?:\\s|$|[^a-zA-Z])", Pattern.CASE_INSENSITIVE);

    // 用于跟踪已处理的消息，避免重复处理（存储消息ID和时间戳）
    private final Map<String, Long> processedMessages = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 2000; // 2 秒内视为重复事件

    public ChatListener(McAiAssistant plugin, ConfigManager configManager,
                       ChatHistoryManager chatHistoryManager, AiApiClient aiApiClient,
                       SearchApiClient searchApiClient, KnowledgeBaseManager knowledgeBaseManager,
                       ImageApiClient imageApiClient, ToastNotification toastNotification,
                       RateLimitManager rateLimitManager, EconomyManager economyManager,
                       GlobalMemoryManager globalMemoryManager, CommandWhitelistManager commandWhitelistManager,
                       McpManager mcpManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.chatHistoryManager = chatHistoryManager;
        this.aiApiClient = aiApiClient;
        this.searchApiClient = searchApiClient;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.imageApiClient = imageApiClient;
        this.toastNotification = toastNotification;
        this.rateLimitManager = rateLimitManager;
        this.economyManager = economyManager;
        this.globalMemoryManager = globalMemoryManager;
        this.commandWhitelistManager = commandWhitelistManager;
        this.mcpManager = mcpManager;
    }
    
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("检测到玩家聊天消息: " + player.getName() + " -> " + message);
        }

        long now = System.currentTimeMillis();
        String messageKey = buildDedupKey(player, message);

        // 清理过期的消息记录（例如，超过60秒）
        cleanupProcessedMessages();

        // 检查是否已经处理过这条消息
        Long lastTs = processedMessages.get(messageKey);
        if (lastTs != null && now - lastTs < DEDUP_WINDOW_MS) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("消息疑似重复触发，已跳过: " + messageKey);
            }
            return;
        }

        // 记录聊天历史
        if (configManager.isChatLoggingEnabled()) {
            chatHistoryManager.addMessage(player.getName(), message);
        }

        // 检查是否包含搜索触发关键词（优先级更高）
        if (containsSearchTrigger(message)) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("玩家聊天消息包含搜索触发词: " + message);
            }

            // 标记消息为已处理
            processedMessages.put(messageKey, now);

            // 检查权限
            if (!hasPermission(player)) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("玩家 " + player.getName() + " 没有使用 AI 的权限");
                }
                return;
            }

            // 检查速率限制
            if (!rateLimitManager.canMakeRequest(player)) {
                String limitMessage = rateLimitManager.getRateLimitMessage();
                String formattedMessage = ChatColor.translateAlternateColorCodes('&', limitMessage);
                player.sendMessage(ChatColor.RED + formattedMessage);
                return;
            }

            // 经济预检：余额不足则直接提示并终止，不进入搜索请求
            EconomyManager.EconomyChargeResult preCheck = economyManager.preCheck(player);
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[经济扣费][搜索] 预检结果 -> success: " + preCheck.isSuccess() + ", skipped: " + preCheck.isSkipped()
                        + ", insufficient: " + preCheck.isInsufficientFunds() + ", oldBalance: " + formatCost(preCheck.getOldBalance()));
            }
            if (!preCheck.isSuccess() && !preCheck.isSkipped()) {
                String template = configManager.getEconomyInsufficientMessage();
                String msg = formatEconomyMessage(template, configManager.getEconomyCostPerUse(), preCheck.getErrorMessage());
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                return;
            }

            // 异步处理搜索请求
            handleSearchRequest(player, message);
            return;
        }

        // 检查是否包含 AI 触发关键词
        if (containsAiTrigger(message)) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("玩家聊天消息包含 AI 触发词: " + message);
            }

            // 标记消息为已处理
            processedMessages.put(messageKey, now);

            // 检查权限
            if (!hasPermission(player)) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("玩家 " + player.getName() + " 没有使用 AI 的权限");
                }
                return;
            }

            // 检查速率限制
            if (!rateLimitManager.canMakeRequest(player)) {
                String limitMessage = rateLimitManager.getRateLimitMessage();
                String formattedMessage = ChatColor.translateAlternateColorCodes('&', limitMessage);
                player.sendMessage(ChatColor.RED + formattedMessage);
                return;
            }

            // 经济预检：余额不足则直接提示并终止，不进入 AI 请求
            EconomyManager.EconomyChargeResult preCheck = economyManager.preCheck(player);
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[经济扣费] 预检结果 -> success: " + preCheck.isSuccess() + ", skipped: " + preCheck.isSkipped()
                        + ", insufficient: " + preCheck.isInsufficientFunds() + ", oldBalance: " + formatCost(preCheck.getOldBalance()));
            }
            if (!preCheck.isSuccess() && !preCheck.isSkipped()) {
                String template = configManager.getEconomyInsufficientMessage();
                String msg = formatEconomyMessage(template, configManager.getEconomyCostPerUse(), preCheck.getErrorMessage());
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                return;
            }

            // 异步处理 AI 请求
            handleAiRequest(player, message);
        }
    }
    
    /**
     * 检查消息是否包含 AI 触发关键词
     */
    public boolean containsAiTrigger(String message) {
        List<String> keywords = configManager.getTriggerKeywords();
        
        if (configManager.isSmartMatching()) {
            // 使用智能匹配，避免匹配到 @airport 等词
            return SMART_AI_PATTERN.matcher(message).find();
        } else {
            // 简单匹配
            String lowerMessage = message.toLowerCase();
            return keywords.stream().anyMatch(keyword -> 
                lowerMessage.contains(keyword.toLowerCase()));
        }
    }
    
    /**
     * 检查玩家是否有使用权限
     */
    public boolean hasPermission(Player player) {
        if (configManager.isAllowAllPlayers()) {
            return true;
        }

        String permission = configManager.getRequiredPermission();
        return player.hasPermission(permission);
    }

    /**
     * 标记消息为已处理（供RedisChatCompatibility使用）
     */
    public void markMessageAsProcessed(Player player, String message) {
        long now = System.currentTimeMillis();
        String messageKey = buildDedupKey(player, message);
        processedMessages.put(messageKey, now);

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("消息已标记为已处理: " + messageKey);
        }
    }
    
    /**
     * 处理 AI 请求
     */
    private void handleAiRequest(Player player, String message) {
        // 清理消息，移除 @ai 标记
        final String cleanMessage = cleanMessage(message);

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("处理 AI 请求: " + player.getName() + " -> " + cleanMessage);
        }

        // 显示通知
        showProcessingNotifications(player);
        
        EconomyManager.EconomyChargeResult chargeResult = null;
        if (economyManager.isActive()) {
            chargeResult = chargePlayerForAi(player);
            if (chargeResult != null && !chargeResult.isSkipped() && !chargeResult.isSuccess()) {
                // 扣费失败时已提示，计入频率后终止
                rateLimitManager.recordRequest(player);
                return;
            }
            if (chargeResult != null && chargeResult.isSuccess()) {
                rateLimitManager.recordRequest(player);
            } else if (chargeResult != null && chargeResult.isSkipped()) {
                rateLimitManager.recordRequest(player);
            }
        } else {
            rateLimitManager.recordRequest(player);
        }

        // 在进入异步调用前将扣费结果固化，便于在 Lambda 中安全使用
        final EconomyManager.EconomyChargeResult finalChargeResult = chargeResult;

        // 异步调用 AI API
        CompletableFuture.supplyAsync(() -> {
            ResponseWrapper wrapper = new ResponseWrapper();
            try {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("开始处理 AI 请求，清理后的消息: " + cleanMessage);
                }

                // 获取上下文消息
                List<String> context = configManager.isContextEnabled() ?
                    chatHistoryManager.getRecentMessages(configManager.getContextMessages()) : null;

                if (configManager.isDebugMode() && context != null) {
                    plugin.getLogger().info("AI 请求上下文消息数量: " + context.size());
                }

                // 调用 AI API（允许工具调用）
                wrapper.response = aiApiClient.sendMessageWithTools(cleanMessage, context);
                wrapper.failed = false;
                return wrapper;
            } catch (Exception e) {
                plugin.getLogger().severe("AI API 调用失败: " + e.getMessage());
                if (configManager.isDebugMode()) {
                    plugin.getLogger().severe("AI API 调用异常详情:");
                    e.printStackTrace();
                }
                wrapper.response = new AiApiClient.AiResponse("抱歉，AI 助手暂时无法响应，请稍后再试。技术详情: " + e.getMessage(), null);
                wrapper.failed = true;
                return wrapper;
            }
        }).thenAccept(result -> {
            // 在主线程中发送响应
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (configManager.isDebugMode()) {
                    String content = (result != null && result.response != null) ? result.response.getContent() : "";
                    plugin.getLogger().info("AI 请求处理完成，响应长度: " + (content == null ? 0 : content.length()));
                }

                if (result == null || result.response == null || result.failed) {
                    if (finalChargeResult != null && finalChargeResult.isSuccess()) {
                        economyManager.refund(player, finalChargeResult.getCost());
                        if (configManager.isDebugMode()) {
                            plugin.getLogger().info("[经济扣费] AI 未能响应，已为玩家退款: " + formatCost(finalChargeResult.getCost()));
                        }
                    }
                    // 返回简单提示给玩家
                    player.sendMessage(ChatColor.RED + "抱歉，AI 助手暂时无法响应，请稍后再试。");
                    return;
                }

                sendAiResponse(result.response, player, cleanMessage, finalChargeResult);
            });
        });
    }
    
    /**
     * 清理消息，移除 AI 触发关键词
     */
    public String cleanMessage(String message) {
        String cleaned = message;
        
        // 移除所有触发关键词
        for (String keyword : configManager.getTriggerKeywords()) {
            cleaned = cleaned.replaceAll("(?i)" + Pattern.quote(keyword), "").trim();
        }
        
        // 清理多余的空格
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        
        return cleaned;
    }
    
    /**
     * 发送 AI 响应到聊天
     */
    public void sendAiResponse(AiApiClient.AiResponse response) {
        sendAiResponse(response, null, null, null);
    }

    /**
     * 发送 AI 响应到聊天（带玩家信息用于图像生成）
     */
    public void sendAiResponse(AiApiClient.AiResponse response, Player requestPlayer, String originalMessage, EconomyManager.EconomyChargeResult chargeResult) {
        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[响应处理] 📥 收到 AI 响应，开始处理");
            if (response != null) {
                String content = response.getContent();
                plugin.getLogger().info("[响应处理] 响应长度: " + (content == null ? 0 : content.length()) + " 字符");
                plugin.getLogger().info("[响应处理] 工具调用数量: " + (response.getToolCalls() == null ? 0 : response.getToolCalls().size()));
                if (content != null && content.length() < 500) {
                    plugin.getLogger().info("[响应处理] 完整响应内容: " + content);
                } else {
                    String preview = content == null ? "" : content.substring(0, Math.min(500, content.length()));
                    plugin.getLogger().info("[响应处理] 响应内容预览: " + preview + "...");
                }
            } else {
                plugin.getLogger().warning("[响应处理] ❌ 收到空响应");
                return;
            }
        }

        if (response == null) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[响应处理] 收到空响应，跳过发送与扣费");
            }
            return;
        }

        String responseContent = response.getContent();
        boolean hasToolCalls = response.hasToolCalls();
        if ((responseContent == null || responseContent.trim().isEmpty()) && !hasToolCalls) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[响应处理] 收到空响应且无工具调用，跳过处理");
            }
            return;
        }

        String aiName = configManager.getAiName();
        String aiPrefix = configManager.getAiPrefix();

        // Agent 模式：当存在工具调用时，不立即回传玩家消息，直到满足停止条件。
        if (requestPlayer != null && hasToolCalls) {
            runAgentUntilDone(requestPlayer, originalMessage, response, chargeResult);
            return;
        }

        // 1. 移除工具标签与 mark，获取纯文本响应（兼容旧格式）
        String cleanResponse = removeMarkTags(removeImageTags(removeKnowledgeTags(responseContent == null ? "" : responseContent)));

        // 2. 如果有纯文本内容，立即发送
        if (!cleanResponse.trim().isEmpty()) {
            String formattedMessage = ChatColor.AQUA + aiPrefix + ChatColor.WHITE + cleanResponse;
            Bukkit.broadcastMessage(formattedMessage);
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[响应处理] ✅ 已发送纯文本内容: " + cleanResponse);
            }
        }

        // 3. 记录完整的原始响应到历史记录（如果启用）
        if (configManager.isChatLoggingEnabled()) {
            chatHistoryManager.addMessage(aiName, responseContent == null ? "" : responseContent);
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[响应处理] 📝 已将原始响应记录到历史");
            }
        }

        // 4. 在 AI 内容发送完毕后再提示扣费结果
        if (requestPlayer != null && chargeResult != null && !chargeResult.isSkipped() && chargeResult.isSuccess()) {
            sendBalanceMessage(requestPlayer, chargeResult);
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[经济扣费] 已在响应后提示余额，玩家: " + requestPlayer.getName());
            }
        }
        // AI 响应包含 mark 时，后台判定是否写入全局记忆
        if (hasMarkTag(responseContent == null ? "" : responseContent)) {
            processMemoryDecisionAsync(originalMessage, responseContent, requestPlayer != null ? requestPlayer.getName() : "AI");
        }
    }

    private void runAgentUntilDone(Player requestPlayer, String originalMessage, AiApiClient.AiResponse firstResponse,
                                   EconomyManager.EconomyChargeResult chargeResult) {
        final String cleanMessage = originalMessage;
        CompletableFuture.supplyAsync(() -> {
            try {
                List<String> context = configManager.isContextEnabled()
                        ? chatHistoryManager.getRecentMessages(configManager.getContextMessages())
                        : null;
                return runAgentLoop(requestPlayer, cleanMessage, context, firstResponse);
            } catch (Exception e) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[Agent] 执行失败: " + e.getMessage());
                }
                return null;
            }
        }).thenAccept(finalContent -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (finalContent == null || finalContent.trim().isEmpty()) {
                if (chargeResult != null && chargeResult.isSuccess()) {
                    economyManager.refund(requestPlayer, chargeResult.getCost());
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("[经济扣费][Agent] 最终响应为空，已为玩家退款: " + formatCost(chargeResult.getCost()));
                    }
                }
                requestPlayer.sendMessage(ChatColor.RED + "抱歉，AI 助手暂时无法响应，请稍后再试。");
                return;
            }

            String aiPrefix = configManager.getAiPrefix();
            String cleaned = removeMarkTags(removeImageTags(removeKnowledgeTags(finalContent)));
            if (!cleaned.trim().isEmpty()) {
                Bukkit.broadcastMessage(ChatColor.AQUA + aiPrefix + ChatColor.WHITE + cleaned);
            }

            // 记录聊天历史（以最终文本为准）
            if (configManager.isChatLoggingEnabled()) {
                chatHistoryManager.addMessage(configManager.getAiName(), finalContent);
            }

            if (chargeResult != null && !chargeResult.isSkipped() && chargeResult.isSuccess()) {
                sendBalanceMessage(requestPlayer, chargeResult);
            }

            // 若最终回复包含 mark，则触发后台记忆判定
            if (hasMarkTag(finalContent)) {
                processMemoryDecisionAsync(cleanMessage, finalContent, requestPlayer.getName());
            }
        }));
    }

    private String runAgentLoop(Player player, String cleanMessage, List<String> context, AiApiClient.AiResponse firstResponse) throws Exception {
        com.google.gson.JsonArray messages = aiApiClient.buildAgentInitialMessages(cleanMessage, context);
        AiApiClient.AiResponse current = firstResponse;

        int knowledgeCalls = 0;
        int maxSteps = 6;
        Set<String> failedMcpTargets = new HashSet<>();

        for (int step = 0; step < maxSteps; step++) {
            appendAssistantMessage(messages, current);

            if (current == null || !current.hasToolCalls()) {
                return current == null ? null : current.getContent();
            }

            List<AiApiClient.ToolCall> toolCalls = current.getToolCalls();
            String lastToolName = null;

            for (AiApiClient.ToolCall call : toolCalls) {
                if (call == null || call.getName() == null) {
                    continue;
                }
                lastToolName = call.getName();
                String toolResult;

                switch (call.getName()) {
                    case "query_knowledge":
                        knowledgeCalls++;
                        if (knowledgeCalls > 2) {
                            toolResult = "已达到 query_knowledge 最大调用次数(2)，本次查询被跳过。";
                        } else {
                            notifyToolCall(player, "知识库查询");
                            toolResult = runKnowledgeTool(call);
                        }
                        appendToolMessage(messages, call, toolResult);
                        break;
                    case "execute_command":
                        notifyToolCall(player, "后台指令");
                        toolResult = runCommandTool(call);
                        appendToolMessage(messages, call, toolResult);
                        break;
                    case "create_image":
                        toolResult = runImageTool(player, call);
                        appendToolMessage(messages, call, toolResult);
                        break;
                    case "mcp_call":
                        notifyToolCall(player, "MCP 工具");
                        String mcpServer = getToolArg(call, "server");
                        String mcpTool = getToolArg(call, "tool");
                        String mcpKey = (mcpServer == null ? "" : mcpServer.trim()) + "|" + (mcpTool == null ? "" : mcpTool.trim());
                        if (failedMcpTargets.contains(mcpKey)) {
                            toolResult = "已跳过重复失败的 MCP 调用: " + mcpKey;
                        } else {
                            toolResult = runMcpTool(call);
                            if (isMcpFailureResult(toolResult)) {
                                failedMcpTargets.add(mcpKey);
                            }
                        }
                        appendToolMessage(messages, call, toolResult);
                        break;
                    default:
                        appendToolMessage(messages, call, "未知工具: " + call.getName());
                        break;
                }
            }

            boolean stopAfterImage = "create_image".equals(lastToolName);
            current = aiApiClient.sendChatCompletionWithMessages(messages, !stopAfterImage);
            if (stopAfterImage) {
                // 图像生成作为最后一个工具时，强制收敛为最终文本后回传玩家
                return current == null ? null : current.getContent();
            }
        }

        if (current != null && current.hasToolCalls()) {
            try {
                AiApiClient.AiResponse fallback = aiApiClient.sendChatCompletionWithMessages(messages, false);
                if (fallback != null && fallback.getContent() != null && !fallback.getContent().trim().isEmpty()) {
                    return fallback.getContent();
                }
            } catch (Exception e) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[Agent] 工具过多后的无工具兜底失败: " + e.getMessage());
                }
            }
            return "抱歉，我在处理过程中触发了过多工具调用，为避免影响服务器性能，本次已中止。请换一种问法或减少需求复杂度。";
        }
        return current == null ? null : current.getContent();
    }

    private void appendAssistantMessage(com.google.gson.JsonArray messages, AiApiClient.AiResponse response) {
        com.google.gson.JsonObject assistantMessage = new com.google.gson.JsonObject();
        assistantMessage.addProperty("role", "assistant");
        assistantMessage.addProperty("content", response == null ? "" : (response.getContent() == null ? "" : response.getContent()));

        if (response != null && response.hasToolCalls()) {
            com.google.gson.JsonArray toolCalls = new com.google.gson.JsonArray();
            for (AiApiClient.ToolCall call : response.getToolCalls()) {
                if (call == null || call.getName() == null) {
                    continue;
                }
                com.google.gson.JsonObject item = new com.google.gson.JsonObject();
                item.addProperty("id", call.getId());
                item.addProperty("type", "function");
                com.google.gson.JsonObject function = new com.google.gson.JsonObject();
                function.addProperty("name", call.getName());
                function.addProperty("arguments", call.getArguments() == null ? "{}" : call.getArguments().toString());
                item.add("function", function);
                toolCalls.add(item);
            }
            assistantMessage.add("tool_calls", toolCalls);
        }
        messages.add(assistantMessage);
    }

    private void appendToolMessage(com.google.gson.JsonArray messages, AiApiClient.ToolCall toolCall, String toolResultContent) {
        com.google.gson.JsonObject toolMessage = new com.google.gson.JsonObject();
        toolMessage.addProperty("role", "tool");
        toolMessage.addProperty("tool_call_id", toolCall.getId());
        toolMessage.addProperty("content", toolResultContent == null ? "" : toolResultContent);
        messages.add(toolMessage);
    }

    private void notifyToolCall(Player player, String toolName) {
        if (player == null || toolName == null || toolName.trim().isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            String aiPrefix = configManager.getAiPrefix();
            player.sendMessage(ChatColor.AQUA + aiPrefix + ChatColor.WHITE + "已调用" + toolName);
        });
    }

    private String runKnowledgeTool(AiApiClient.ToolCall call) {
        if (!configManager.isKnowledgeEnabled()) {
            return "知识库功能未启用。";
        }
        String query = getToolArg(call, "query");
        if (query == null || query.trim().isEmpty()) {
            return "缺少 query 参数。";
        }
        KnowledgeSearchResult result = knowledgeBaseManager.searchKnowledge(query.trim());
        if (result == null || result.isEmpty()) {
            return "未在本地知识库中找到相关资料。";
        }
        String content = result.toToolContent();
        return content == null ? "未在本地知识库中找到相关资料。" : content;
    }

    private String runCommandTool(AiApiClient.ToolCall call) throws Exception {
        if (commandWhitelistManager == null || !commandWhitelistManager.isEnabled()) {
            return "指令白名单未启用。";
        }
        String command = getToolArg(call, "command");
        if (command == null || command.trim().isEmpty()) {
            return "缺少 command 参数。";
        }
        String normalized = normalizeCommand(command);
        if (!commandWhitelistManager.isCommandAllowed(normalized)) {
            return "指令被白名单拒绝: " + normalized;
        }
        return Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            boolean success = Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), normalized);
            return "执行指令: " + normalized + "\n结果: " + (success ? "成功" : "失败");
        }).get();
    }

    private String runImageTool(Player player, AiApiClient.ToolCall call) {
        if (!configManager.isImageGenerationEnabled()) {
            return "图像生成功能未启用。";
        }
        String prompt = getToolArg(call, "prompt");
        String alt = getToolArg(call, "alt");
        if (prompt == null || prompt.trim().isEmpty()) {
            return "缺少 prompt 参数。";
        }
        String safeAlt = (alt == null || alt.trim().isEmpty()) ? "图像生成结果" : alt.trim();

        long startTime = System.currentTimeMillis();
        ImageApiClient.ImageResponse imageResponse = imageApiClient.createImage(prompt.trim());
        long duration = System.currentTimeMillis() - startTime;

        // 广播图像结果（主线程）
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                // 复用原有展示效果：通过 Adventure 组件输出可点击链接
                if (imageResponse != null && imageResponse.isSuccess()
                        && imageResponse.getImages() != null && !imageResponse.getImages().isEmpty()) {
                    List<String> images = imageResponse.getImages();
                    String aiPrefix = configManager.getAiPrefix();

                    TextComponent.Builder messageBuilder = Component.text()
                            .append(Component.text(aiPrefix, NamedTextColor.AQUA))
                            .append(Component.text("🎨 ", NamedTextColor.GOLD))
                            .append(Component.text("图像生成完成！", NamedTextColor.GREEN))
                            .hoverEvent(HoverEvent.showText(Component.text(" (由 " + player.getName() + " 生成)", NamedTextColor.GRAY)))
                            .append(Component.text("\n📝 ", NamedTextColor.GRAY))
                            .append(Component.text(safeAlt, NamedTextColor.WHITE)
                                    .hoverEvent(HoverEvent.showText(Component.text("原始 Prompt: " + prompt, NamedTextColor.GRAY))))
                            .append(Component.text("\n⏱️ 用时: ", NamedTextColor.GRAY))
                            .append(Component.text(String.format("%.1f秒", duration / 1000.0), NamedTextColor.YELLOW))
                            .append(Component.text("\n🖼️ 生成了 ", NamedTextColor.GRAY))
                            .append(Component.text(images.size() + " 张图像", NamedTextColor.AQUA));

                    for (int i = 0; i < images.size(); i++) {
                        String imagePath = images.get(i);
                        String command = "/image create " + imagePath;
                        messageBuilder.append(Component.text("\n🖱️ ", NamedTextColor.BLUE))
                                .append(Component.text("图像 " + (i + 1), NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                                        .clickEvent(ClickEvent.suggestCommand(command))
                                        .hoverEvent(HoverEvent.showText(Component.text()
                                                .append(Component.text("点击获取图像 " + (i + 1), NamedTextColor.GREEN))
                                                .append(Component.text("\n执行命令: ", NamedTextColor.GRAY))
                                                .append(Component.text(command, NamedTextColor.WHITE))
                                                .append(Component.text("\n路径: ", NamedTextColor.GRAY))
                                                .append(Component.text(imagePath, NamedTextColor.YELLOW))
                                                .build())));
                    }
                    Bukkit.broadcast(messageBuilder.build());
                }
            } catch (Exception e) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[图像生成][Agent] 广播失败: " + e.getMessage());
                }
            }
        });

        if (imageResponse == null) {
            return "图像生成失败: 响应为空";
        }
        if (!imageResponse.isSuccess() || imageResponse.getImages() == null || imageResponse.getImages().isEmpty()) {
            String err = imageResponse.getErrorMessage() != null ? imageResponse.getErrorMessage() : "未知错误";
            return "图像生成失败: " + err;
        }
        return "图像生成成功: " + safeAlt + "\nimages: " + imageResponse.getImages();
    }

    private String runMcpTool(AiApiClient.ToolCall call) {
        if (mcpManager == null || !mcpManager.hasEnabledServers()) {
            return "MCP 未启用或当前不可用（可能全部熔断）。";
        }
        String server = getToolArg(call, "server");
        String toolName = getToolArg(call, "tool");
        JsonObject args = getToolArgObject(call, "arguments");
        return mcpManager.callTool(server, toolName, args);
    }

    private boolean isMcpFailureResult(String result) {
        if (result == null || result.trim().isEmpty()) {
            return true;
        }
        return result.startsWith("MCP 工具调用失败:")
                || result.startsWith("MCP 熔断中:")
                || result.startsWith("MCP 未启用或当前不可用")
                || result.startsWith("MCP 服务器未启用或不存在:")
                || result.startsWith("缺少 server 参数。")
                || result.startsWith("缺少 tool 参数。")
                || result.startsWith("MCP 工具返回空结果。");
    }

    private String buildDedupKey(Player player, String message) {
        String playerKey = player == null ? "unknown" : player.getUniqueId().toString();
        String msg = message == null ? "" : message.trim();
        return playerKey + ":" + msg;
    }

    /**
     * 显示 AI 处理中的通知
     */
    private void showProcessingNotifications(Player player) {
        // 立即显示 Toast 通知
        if (configManager.isToastEnabled()) {
            toastNotification.showAiProcessingToast(player);
        }

        // 延迟 0.3 秒显示聊天状态提示
        if (configManager.isChatStatusEnabled()) {
            String processingMessage = configManager.getProcessingMessage();
            // 转换颜色代码
            String formattedMessage = ChatColor.translateAlternateColorCodes('&', processingMessage);

            // 延迟 6 ticks (0.3 秒) 发送消息
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(formattedMessage);

                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("发送延迟聊天状态提示给玩家 " + player.getName() + ": " + processingMessage);
                    }
                }
            }, 6L); // 6 ticks = 0.3 秒
        }
    }

    /**
     * 检查消息是否包含搜索触发关键词
     */
    public boolean containsSearchTrigger(String message) {
        if (configManager.isSmartMatching()) {
            return SMART_SEARCH_PATTERN.matcher(message).find();
        } else {
            String lowerMessage = message.toLowerCase();
            for (String keyword : configManager.getSearchKeywords()) {
                if (lowerMessage.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }



    /**
     * 处理搜索请求
     */
    private void handleSearchRequest(Player player, String message) {
        // 清理消息，移除 @search 标记
        String cleanMessage = cleanSearchMessage(message);

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("处理搜索请求: " + player.getName() + " -> " + cleanMessage);
        }

        // 显示通知
        showProcessingNotifications(player);

        EconomyManager.EconomyChargeResult chargeResult = null;
        if (economyManager.isActive()) {
            chargeResult = chargePlayerForAi(player);
            if (chargeResult != null && !chargeResult.isSkipped() && !chargeResult.isSuccess()) {
                // 扣费失败时已提示，计入频率后终止
                rateLimitManager.recordRequest(player);
                return;
            }
            if (chargeResult != null && chargeResult.isSuccess()) {
                rateLimitManager.recordRequest(player);
            } else if (chargeResult != null && chargeResult.isSkipped()) {
                rateLimitManager.recordRequest(player);
            }
        } else {
            rateLimitManager.recordRequest(player);
        }

        // 将扣费结果固化，保证异步处理时可安全访问
        final EconomyManager.EconomyChargeResult finalChargeResult = chargeResult;

        // 异步调用搜索 API
        CompletableFuture.supplyAsync(() -> {
            SearchWrapper wrapper = new SearchWrapper();
            try {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("开始处理搜索请求，清理后的消息: " + cleanMessage);
                }

                // 调用新的搜索 API
                wrapper.result = searchApiClient.search(cleanMessage);
                wrapper.failed = false;
                return wrapper;
            } catch (Exception e) {
                plugin.getLogger().severe("搜索 API 调用失败: " + e.getMessage());
                if (configManager.isDebugMode()) {
                    plugin.getLogger().severe("搜索 API 调用异常详情:");
                    e.printStackTrace();
                }

                // 返回错误结果
                SearchApiClient.SearchResult errorResult = new SearchApiClient.SearchResult();
                errorResult.setSearchQuery("搜索失败");
                errorResult.setResultText("抱歉，搜索功能暂时无法使用，请稍后再试。技术详情: " + e.getMessage());
                wrapper.result = errorResult;
                wrapper.failed = true;
                return wrapper;
            }
        }).thenAccept(wrapper -> {
            // 在主线程中发送响应
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("搜索请求处理完成");
                    if (wrapper != null && wrapper.result != null) {
                        plugin.getLogger().info("搜索查询: " + wrapper.result.getSearchQuery());
                        plugin.getLogger().info("搜索结果长度: " + (wrapper.result.getResultText() != null ? wrapper.result.getResultText().length() : 0));
                    }
                }

                boolean needRefund = wrapper == null || wrapper.result == null || wrapper.failed
                        || wrapper.result.getResultText() == null || wrapper.result.getResultText().trim().isEmpty();
                if (needRefund && finalChargeResult != null && finalChargeResult.isSuccess()) {
                    economyManager.refund(player, finalChargeResult.getCost());
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("[经济扣费][搜索] 搜索失败或结果为空，已为玩家退款: " + formatCost(finalChargeResult.getCost()));
                    }
                }

                if (wrapper == null || wrapper.result == null) {
                    player.sendMessage(ChatColor.RED + "抱歉，搜索功能暂时无法使用，请稍后再试。");
                    return;
                }
                sendSearchResponse(wrapper.result);
            });
        });
    }

    /**
     * 清理搜索消息，移除触发关键词
     */
    private String cleanSearchMessage(String message) {
        String cleanedMessage = message;

        // 移除搜索关键词
        for (String keyword : configManager.getSearchKeywords()) {
            cleanedMessage = cleanedMessage.replaceAll("(?i)" + Pattern.quote(keyword), "").trim();
        }

        // 清理多余的空格
        cleanedMessage = cleanedMessage.replaceAll("\\s+", " ").trim();

        return cleanedMessage;
    }

    /**
     * 发送搜索响应
     */
    public void sendSearchResponse(SearchApiClient.SearchResult searchResult) {
        if (searchResult == null || searchResult.getResultText() == null || searchResult.getResultText().trim().isEmpty()) {
            return;
        }

        String aiName = "🔍 " + configManager.getAiName();
        String aiPrefix = configManager.getAiPrefix();

        // 构建搜索响应组件
        Component messageComponent = Component.empty();

        // 添加搜索查询信息
        if (searchResult.getSearchQuery() != null && !searchResult.getSearchQuery().trim().isEmpty()) {
            messageComponent = messageComponent
                .append(Component.text("🔍 搜索: ", NamedTextColor.GRAY))
                .append(Component.text(searchResult.getSearchQuery(), NamedTextColor.GRAY))
                .append(Component.newline());
        }

        // 添加搜索结果前缀
        messageComponent = messageComponent
            .append(Component.text(aiPrefix, NamedTextColor.GREEN))
            .append(Component.text(searchResult.getResultText(), NamedTextColor.WHITE));

        // 如果有链接，添加链接部分
        if (searchResult.getLinks() != null && !searchResult.getLinks().isEmpty()) {
            messageComponent = messageComponent
                .append(Component.newline())
                .append(Component.text("📎 相关链接: ", NamedTextColor.AQUA));

            for (int i = 0; i < searchResult.getLinks().size(); i++) {
                SearchApiClient.LinkInfo link = searchResult.getLinks().get(i);
                if (i > 0) {
                    messageComponent = messageComponent.append(Component.text(" | ", NamedTextColor.GRAY));
                }

                // 创建可点击的链接
                Component linkComponent = Component.text(link.getTitle(), NamedTextColor.BLUE)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(link.getUrl()))
                    .hoverEvent(HoverEvent.showText(Component.text("点击打开: " + link.getUrl(), NamedTextColor.YELLOW)));

                messageComponent = messageComponent.append(linkComponent);
            }
        }

        // 广播消息给所有在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(messageComponent);
        }

        // 同时在控制台显示搜索结果（使用传统格式）
        StringBuilder consoleMessage = new StringBuilder();
        if (searchResult.getSearchQuery() != null && !searchResult.getSearchQuery().trim().isEmpty()) {
            consoleMessage.append("🔍 搜索: ").append(searchResult.getSearchQuery()).append("\n");
        }
        consoleMessage.append(aiPrefix).append(searchResult.getResultText());

        // 添加链接信息到控制台消息
        if (searchResult.getLinks() != null && !searchResult.getLinks().isEmpty()) {
            consoleMessage.append("\n📎 相关链接: ");
            for (int i = 0; i < searchResult.getLinks().size(); i++) {
                SearchApiClient.LinkInfo link = searchResult.getLinks().get(i);
                if (i > 0) {
                    consoleMessage.append(" | ");
                }
                consoleMessage.append(link.getTitle()).append(" (").append(link.getUrl()).append(")");
            }
        }

        // 仅在控制台显示搜索结果（不广播给玩家）
        plugin.getLogger().info(consoleMessage.toString());

        // 记录搜索响应到聊天历史
        if (configManager.isChatLoggingEnabled()) {
            String logMessage = "搜索: " + searchResult.getSearchQuery() + " | 结果: " + searchResult.getResultText();
            chatHistoryManager.addMessage(aiName, logMessage);
        }

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("搜索查询: " + searchResult.getSearchQuery());
            plugin.getLogger().info("搜索结果: " + searchResult.getResultText());
            plugin.getLogger().info("提取的链接数量: " + (searchResult.getLinks() != null ? searchResult.getLinks().size() : 0));
        }
    }

    /**
     * 处理图像生成工具调用
     */
    private void processImageGeneration(Player player, String prompt, String alt) {
        if (!configManager.isImageGenerationEnabled()) {
            return;
        }

        if (prompt == null || prompt.trim().isEmpty()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[图像生成] 工具调用缺少 prompt，已忽略");
            }
            return;
        }

        String safeAlt = (alt == null || alt.trim().isEmpty()) ? "图像生成结果" : alt.trim();

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[图像生成] 检测到图像生成请求，prompt: " + prompt + ", alt: " + safeAlt);
        }

        // 显示生成开始通知
        if (configManager.isToastEnabled()) {
            toastNotification.showToast(player, "正在生成图像...", "请稍候，图像生成中");
        }

        // 异步生成图像
        CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            ImageApiClient.ImageResponse imageResponse = imageApiClient.createImage(prompt);
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            return new Object[]{imageResponse, duration, safeAlt};
        }).thenAccept(result -> {
            ImageApiClient.ImageResponse imageResponse = (ImageApiClient.ImageResponse) result[0];
            long duration = (Long) result[1];
            String imageAlt = (String) result[2];

            // 在主线程中处理结果
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (imageResponse.isSuccess() && imageResponse.getImages() != null && !imageResponse.getImages().isEmpty()) {
                    // 成功生成图像
                    List<String> images = imageResponse.getImages();

                    // 获取 AI 前缀
                    String aiPrefix = configManager.getAiPrefix();

                    // 创建主消息
                    TextComponent.Builder messageBuilder = Component.text()
                        .append(Component.text(aiPrefix, NamedTextColor.AQUA))
                        .append(Component.text("🎨 ", NamedTextColor.GOLD))
                        .append(Component.text("图像生成完成！", NamedTextColor.GREEN))
                            .hoverEvent(HoverEvent.showText(Component.text(" (由 " + player.getName() + " 生成)", NamedTextColor.GRAY)))
                        .append(Component.text("\n📝 ", NamedTextColor.GRAY))
                        .append(Component.text(imageAlt, NamedTextColor.WHITE)
                            .hoverEvent(HoverEvent.showText(Component.text("原始 Prompt: " + prompt, NamedTextColor.GRAY))))
                        .append(Component.text("\n⏱️ 用时: ", NamedTextColor.GRAY))
                        .append(Component.text(String.format("%.1f秒", duration / 1000.0), NamedTextColor.YELLOW))
                        .append(Component.text("\n🖼️ 生成了 ", NamedTextColor.GRAY))
                        .append(Component.text(images.size() + " 张图像", NamedTextColor.AQUA));

                    // 为每张图像添加可点击链接
                    for (int i = 0; i < images.size(); i++) {
                        String imagePath = images.get(i);
                        String command = "/image create " + imagePath;

                        messageBuilder.append(Component.text("\n🖱️ ", NamedTextColor.BLUE))
                            .append(Component.text("图像 " + (i + 1), NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.suggestCommand(command))
                                .hoverEvent(HoverEvent.showText(Component.text()
                                    .append(Component.text("点击获取图像 " + (i + 1), NamedTextColor.GREEN))
                                    .append(Component.text("\n执行命令: ", NamedTextColor.GRAY))
                                    .append(Component.text(command, NamedTextColor.WHITE))
                                    .append(Component.text("\n路径: ", NamedTextColor.GRAY))
                                    .append(Component.text(imagePath, NamedTextColor.YELLOW))
                                    .build())));
                    }

                    Component imageMessage = messageBuilder.build();
                    Bukkit.broadcast(imageMessage);

                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("[图像生成] 成功生成 " + images.size() + " 张图像，用时: " + duration + "ms");
                        for (int i = 0; i < images.size(); i++) {
                            plugin.getLogger().info("[图像生成] 图像 " + (i + 1) + ": " + images.get(i));
                        }
                    }
                } else {
                    // 生成失败
                    String errorMsg = imageResponse.getErrorMessage() != null ?
                        imageResponse.getErrorMessage() : "未知错误";

                    String aiPrefix = configManager.getAiPrefix();

                    Component errorMessage = Component.text()
                        .append(Component.text(aiPrefix, NamedTextColor.AQUA))
                        .append(Component.text("❌ ", NamedTextColor.RED))
                        .append(Component.text("图像生成失败 (请求者: " + player.getName() + "): ", NamedTextColor.RED))
                        .append(Component.text(errorMsg, NamedTextColor.GRAY))
                        .build();

                    Bukkit.broadcast(errorMessage);

                    if (configManager.isDebugMode()) {
                        plugin.getLogger().warning("[图像生成] 生成失败: " + errorMsg);
                    }
                }
            });
        }).exceptionally(throwable -> {
            // 处理异常
            Bukkit.getScheduler().runTask(plugin, () -> {
                String aiPrefix = configManager.getAiPrefix();

                Component errorMessage = Component.text()
                    .append(Component.text(aiPrefix, NamedTextColor.AQUA))
                    .append(Component.text("❌ ", NamedTextColor.RED))
                    .append(Component.text("图像生成异常 (请求者: " + player.getName() + "): ", NamedTextColor.RED))
                    .append(Component.text(throwable.getMessage(), NamedTextColor.GRAY))
                    .build();

                Bukkit.broadcast(errorMessage);
            });

            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[图像生成] 处理异常: " + throwable.getMessage());
                throwable.printStackTrace();
            }
            return null;
        });
    }

    /**
     * 移除响应中的图像生成标签
     */
    private String removeImageTags(String response) {
        if (!configManager.isImageGenerationEnabled()) {
            return response;
        }

        // 支持新的 alt 属性格式和旧格式
        Pattern newImagePattern = Pattern.compile("<create_image\\s+prompt=\"[^\"]+\"\\s+alt=\"[^\"]+\"\\s*/>");
        Pattern oldImagePattern = Pattern.compile("<create_image\\s+prompt=\"[^\"]+\"\\s*/>");

        String result = newImagePattern.matcher(response).replaceAll("").trim();
        result = oldImagePattern.matcher(result).replaceAll("").trim();

        return result;
    }

    /**
     * 处理知识库查询工具调用
     */
    private void processKnowledgeQuery(Player player, AiApiClient.ToolCall toolCall, String assistantContent,
                                       final String cleanMessage) {
        if (!configManager.isKnowledgeEnabled()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库查询] 知识库功能未启用，跳过处理");
            }
            return;
        }

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] 开始处理工具调用");
        }
        final String trimmedQuery = getToolArg(toolCall, "query");
        final String normalizedQuery = trimmedQuery == null ? "" : trimmedQuery.trim();
        if (normalizedQuery.isEmpty()) {
            return;
        }

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] ✅ 检测到知识库查询请求");
            plugin.getLogger().info("[知识库查询] 查询内容: " + normalizedQuery);
            plugin.getLogger().info("[知识库查询] 请求玩家: " + player.getName());
            plugin.getLogger().info("[知识库查询] 知识库配置内容: " + configManager.getKnowledgeContent());
        }

        CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            KnowledgeSearchResult result = knowledgeBaseManager.searchKnowledge(normalizedQuery);
            long duration = System.currentTimeMillis() - startTime;

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库查询] 本地检索耗时: " + duration + "ms");
                if (result != null && !result.isEmpty()) {
                    plugin.getLogger().info("[知识库查询] AI 摘要长度: " + (result.getAiAnswer() == null ? 0 : result.getAiAnswer().length()));
                    plugin.getLogger().info("[知识库查询] 直接命中数量: " + result.getSnippets().size());
                } else {
                    plugin.getLogger().info("[知识库查询] 未命中任何知识片段");
                }
            }
            return result;
        }).thenAccept(result -> {
            if (result == null || result.isEmpty()) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("[知识库查询] 未找到任何可用片段，直接反馈暂无资料");
                }
                Bukkit.getScheduler().runTask(plugin, () -> sendFinalAiResponse("抱歉，本地知识库暂未收录相关资料，我会持续关注更新。"));
                return;
            }
            CompletableFuture.supplyAsync(() -> {
                try {
                    List<String> context = configManager.isContextEnabled()
                            ? chatHistoryManager.getRecentMessages(configManager.getContextMessages())
                            : null;
                    String knowledgePayload = result == null ? null : result.toToolContent();
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("[知识库查询] 传入知识片段是否为空: " + (knowledgePayload == null ? "是" : "否"));
                    }
                    return aiApiClient.sendFollowUpWithToolResult(cleanMessage, context, assistantContent, toolCall, knowledgePayload);
                } catch (Exception e) {
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().warning("[知识库查询] 生成知识库回复失败: " + e.getMessage());
                    }
                    return null;
                }
            }).thenAccept(finalResponse -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (finalResponse != null && !finalResponse.getContent().trim().isEmpty()) {
                    sendFinalAiResponse(finalResponse.getContent());
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("[知识库查询] ✅ 知识库查询流程完毕");
                    }
                } else if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[知识库查询] ⚠ 最终响应为空，跳过发送");
                }
            }));
        });
    }

    /**
     * 发送最终 AI 响应（不处理标签）
     */
    private void sendFinalAiResponse(String response) {
        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] 📤 准备发送最终 AI 响应");
        }

        if (response == null || response.trim().isEmpty()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[知识库查询] ❌ 响应为空，取消发送");
            }
            return;
        }

        // 移除知识库查询标签
        String cleanResponse = removeKnowledgeTags(response);

        String aiName = configManager.getAiName();
        String aiPrefix = configManager.getAiPrefix();

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] AI 名称: " + aiName);
            plugin.getLogger().info("[知识库查询] AI 前缀: " + aiPrefix);
            plugin.getLogger().info("[知识库查询] 原始响应长度: " + response.length() + " 字符");
            plugin.getLogger().info("[知识库查询] 清理后响应长度: " + cleanResponse.length() + " 字符");
        }

        // 格式化 AI 响应消息
        String formattedMessage = ChatColor.AQUA + aiPrefix + ChatColor.WHITE + cleanResponse;

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] 格式化消息长度: " + formattedMessage.length());
        }

        // 广播信息给所有玩家
        Bukkit.broadcastMessage(formattedMessage);

        // 记录聊天历史
        if (configManager.isChatLoggingEnabled()) {
            chatHistoryManager.addMessage(aiName, cleanResponse);
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库查询] ✅ 已记录聊天历史");
            }
        } else {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库查询] 日志未启用，跳过记录");
            }
        }

        // 知识库查询的回复即便含有 mark，也不触发全局记忆判定，避免误将知识库摘要写入长期记忆

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] ✅ 完成 AI 响应发送");
            plugin.getLogger().info("[知识库查询] 最终响应内容: " + cleanResponse);
        }

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] ✅ 最终 AI 响应发送完成");
            plugin.getLogger().info("[知识库查询] 清理后响应内容: " + cleanResponse);
        }
    }

    /**
     * 移除响应中的知识库查询标签
     */
    private String removeKnowledgeTags(String response) {
        if (!configManager.isKnowledgeEnabled()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库查询] 知识库功能未启用，跳过标签移除");
            }
            return response;
        }

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库查询] 🏷️ 开始移除知识库查询标签");
            plugin.getLogger().info("[知识库查询] 原始响应长度: " + response.length() + " 字符");
        }

        // 移除知识库查询标签
        Pattern knowledgePattern = Pattern.compile("<query_knowledge\\s+query=\"[^\"]+\"\\s*/>");
        Matcher matcher = knowledgePattern.matcher(response);

        boolean foundTags = matcher.find();
        String result = knowledgePattern.matcher(response).replaceAll("").trim();

        if (configManager.isDebugMode()) {
            if (foundTags) {
                plugin.getLogger().info("[知识库查询] ✅ 发现并移除了知识库查询标签");
                plugin.getLogger().info("[知识库查询] 移除后响应长度: " + result.length() + " 字符");
                plugin.getLogger().info("[知识库查询] 长度变化: " + (response.length() - result.length()) + " 字符");
            } else {
                plugin.getLogger().info("[知识库查询] ❌ 未发现知识库查询标签");
            }
        }

        return result;
    }

    /**
     * 扣费并提示结果
     */
    private EconomyManager.EconomyChargeResult chargePlayerForAi(Player player) {
        EconomyManager.EconomyChargeResult chargeResult = economyManager.chargePlayer(player);

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[经济扣费] 触发扣费流程，玩家: " + player.getName());
            plugin.getLogger().info("[经济扣费] 结果: " + (chargeResult.isSuccess() ? "成功" : (chargeResult.isSkipped() ? "跳过" : "失败"))
                    + " | 原余额: " + formatCost(chargeResult.getOldBalance())
                    + " | 新余额: " + formatCost(chargeResult.getNewBalance()));
        }

        if (chargeResult.isSkipped()) {
            return chargeResult;
        }

        if (chargeResult.isSuccess()) {
            return chargeResult;
        }

        String template = chargeResult.isInsufficientFunds()
            ? configManager.getEconomyInsufficientMessage()
            : configManager.getEconomyChargeFailedMessage();
        String message = formatEconomyMessage(template, configManager.getEconomyCostPerUse(), chargeResult.getErrorMessage());
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));

        return chargeResult;
    }

    /**
     * 发送扣费后的余额提示（仅请求者可见）
     */
    private void sendBalanceMessage(Player player, EconomyManager.EconomyChargeResult chargeResult) {
        if (chargeResult == null || chargeResult.isSkipped() || !chargeResult.isSuccess()) {
            return;
        }

        String messageTemplate = configManager.getEconomyBalanceMessage();
        String hoverTemplate = configManager.getEconomyBalanceHoverMessage();

        String messageText = replaceBalancePlaceholders(messageTemplate, chargeResult);
        String hoverText = replaceBalancePlaceholders(hoverTemplate, chargeResult);

        Component base = LegacyComponentSerializer.legacyAmpersand().deserialize(messageText);
        Component hover = LegacyComponentSerializer.legacyAmpersand().deserialize(hoverText);

        Component withHover = base.hoverEvent(HoverEvent.showText(hover));
        player.sendMessage(withHover);

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[经济扣费] 已向玩家 " + player.getName() + " 发送余额提示: " + messageText + " (hover: " + hoverText + ")");
        }
    }

    /**
     * 构造扣费提示消息
     */
    private String formatEconomyMessage(String template, double cost, String reason) {
        String message = template == null ? "" : template;
        message = message.replace("{cost}", formatCost(cost));
        if (reason != null) {
            message = message.replace("{reason}", reason);
        }
        return message;
    }

    /**
     * 替换余额提示中的占位符
     */
    private String replaceBalancePlaceholders(String template, EconomyManager.EconomyChargeResult result) {
        String message = template == null ? "" : template;
        message = message.replace("{balance}", formatCost(result.getNewBalance()));
        message = message.replace("{new}", formatCost(result.getNewBalance()));
        message = message.replace("{new_balance}", formatCost(result.getNewBalance()));
        message = message.replace("{old}", formatCost(result.getOldBalance()));
        message = message.replace("{old_balance}", formatCost(result.getOldBalance()));
        message = message.replace("{cost}", formatCost(result.getCost()));
        return message;
    }

    /**
     * 格式化金额显示
     */
    private String formatCost(double cost) {
        return String.format("%.2f", cost);
    }

    /**
     * 检查是否包含 mark 标签
     */
    private boolean hasMarkTag(String response) {
        if (response == null) {
            return false;
        }
        return Pattern.compile("<mark\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(response).find();
    }

    /**
     * 移除 mark 标签（展示用）
     */
    private String removeMarkTags(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }
        String withoutPair = response.replaceAll("(?s)<mark>(.*?)</mark>", "$1");
        return withoutPair.replaceAll("<mark\\s+content=\"([^\"]*)\"\\s*/>", "$1").replaceAll("<mark\\s*/>", "");
    }

    /**
     * 后台调用 AI 判定是否写入/更新记忆，提供上下文与现有记忆
     */
    private void processMemoryDecisionAsync(String userMessage, String aiResponse, String source) {
        CompletableFuture.runAsync(() -> {
            try {
                List<String> context = new ArrayList<>();
                if (configManager.isContextEnabled()) {
                    List<String> history = chatHistoryManager.getRecentMessages(configManager.getContextMessages());
                    if (history != null) {
                        context.addAll(history);
                    }
                }
                List<String> memories = globalMemoryManager.pickMemories(userMessage != null ? userMessage : aiResponse);
                List<String> actions = decideMemoryWithContext(userMessage, aiResponse, context, memories);
                if (actions != null) {
                    for (String summary : actions) {
                        globalMemoryManager.captureBySummaryAsync(source, summary);
                    }
                }
            } catch (Exception e) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[全局记忆] 后台判定失败: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 构造判定提示，要求 AI 输出 ADD/UPDATE 行
     */
    private List<String> decideMemoryWithContext(String userMessage, String aiResponse, List<String> context, List<String> memories) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是“长期记忆决策器”，决定是否新增/更新全局记忆。只保留长期有用的事实、规则、数值、坐标、指令。\n");
        sb.append("输出格式：每行 `ADD: <摘要>` 或 `UPDATE: <摘要>`；无记忆时输出 `NONE`。摘要要简短并保留关键数字/坐标/条件。\n");
        sb.append("\n[用户消息]\n").append(userMessage == null ? "" : userMessage);
        sb.append("\n[AI 回复]\n").append(aiResponse == null ? "" : aiResponse);
        sb.append("\n[近期上下文]\n");
        if (context != null) {
            for (String c : context) {
                sb.append("- ").append(c).append("\n");
            }
        }
        sb.append("[已有相关记忆]\n");
        if (memories != null) {
            for (String m : memories) {
                sb.append("- ").append(m).append("\n");
            }
        }
        try {
            String decisionText = aiApiClient.sendMessage(sb.toString(), null);
            return parseMemoryActions(decisionText);
        } catch (Exception e) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[全局记忆] 判定调用失败: " + e.getMessage());
            }
            return new ArrayList<>();
        }
    }

    /**
     * 解析 AI 返回的 ADD/UPDATE 行
     */
    private List<String> parseMemoryActions(String text) {
        List<String> summaries = new ArrayList<>();
        if (text == null) {
            return summaries;
        }
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("NONE")) {
                continue;
            }
            if (trimmed.toUpperCase().startsWith("ADD:")) {
                String summary = trimmed.substring(4).trim();
                if (!summary.isEmpty()) {
                    summaries.add(summary);
                }
            } else if (trimmed.toUpperCase().startsWith("UPDATE:")) {
                String summary = trimmed.substring(7).trim();
                if (!summary.isEmpty()) {
                    summaries.add(summary);
                }
            }
        }
        return summaries;
    }

    /**
     * 处理后台指令执行工具调用
     */
    private void processCommandToolCall(AiApiClient.ToolCall toolCall, String assistantContent, String cleanMessage) {
        if (commandWhitelistManager == null || !commandWhitelistManager.isEnabled()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[后台指令] 白名单未启用，跳过执行");
            }
            return;
        }
        String command = getToolArg(toolCall, "command");
        if (command == null || command.trim().isEmpty()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[后台指令] 工具调用缺少 command 参数");
            }
            return;
        }
        String normalized = normalizeCommand(command);
        if (!commandWhitelistManager.isCommandAllowed(normalized)) {
            String deniedResult = "指令被白名单拒绝: " + normalized;
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[后台指令] " + deniedResult);
            }
            sendToolFollowUp(cleanMessage, assistantContent, toolCall, deniedResult);
            return;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    boolean success = Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), normalized);
                    return "执行指令: " + normalized + "\n结果: " + (success ? "成功" : "失败");
                }).get();
            } catch (Exception e) {
                return "执行指令异常: " + normalized + "\n错误: " + e.getMessage();
            }
        }).thenAccept(result -> sendToolFollowUp(cleanMessage, assistantContent, toolCall, result));
    }

    private void sendToolFollowUp(String cleanMessage, String assistantContent, AiApiClient.ToolCall toolCall, String toolResult) {
        CompletableFuture.supplyAsync(() -> {
            try {
                List<String> context = configManager.isContextEnabled()
                        ? chatHistoryManager.getRecentMessages(configManager.getContextMessages())
                        : null;
                return aiApiClient.sendFollowUpWithToolResult(cleanMessage, context, assistantContent, toolCall, toolResult);
            } catch (Exception e) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[后台指令] 工具结果回传失败: " + e.getMessage());
                }
                return null;
            }
        }).thenAccept(finalResponse -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (finalResponse != null && !finalResponse.getContent().trim().isEmpty()) {
                sendFinalAiResponse(finalResponse.getContent());
            }
        }));
    }

    private String normalizeCommand(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.replaceAll("\\s+", " ");
    }

    /**
     * 获取工具参数（字符串）
     */
    private String getToolArg(AiApiClient.ToolCall toolCall, String key) {
        if (toolCall == null || key == null) {
            return null;
        }
        JsonObject args = toolCall.getArguments();
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取工具参数（对象）
     */
    private JsonObject getToolArgObject(AiApiClient.ToolCall toolCall, String key) {
        if (toolCall == null || key == null) {
            return null;
        }
        JsonObject args = toolCall.getArguments();
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            if (args.get(key).isJsonObject()) {
                return args.getAsJsonObject(key);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static class ResponseWrapper {
        private AiApiClient.AiResponse response;
        private boolean failed;
    }
    private static class SearchWrapper {
        private SearchApiClient.SearchResult result;
        private boolean failed;
    }

    /**
     * 清理过期的已处理消息记录
     */
    private void cleanupProcessedMessages() {
        long expirationTime = System.currentTimeMillis() - 60000; // 60秒前
        processedMessages.entrySet().removeIf(entry -> entry.getValue() < expirationTime);
    }
}
