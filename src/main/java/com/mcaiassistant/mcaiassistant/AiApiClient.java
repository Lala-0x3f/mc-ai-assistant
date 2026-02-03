package com.mcaiassistant.mcaiassistant;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * AI API 客户端
 * 负责与 OpenAI 格式的 API 进行通信
 */
public class AiApiClient {

    private final Gson gson;
    private OkHttpClient httpClient;
    private ConfigManager configManager;
    private final ModelManager modelManager;
    private final McAiAssistant plugin;

    public static class ToolCall {
        private final String id;
        private final String name;
        private final JsonObject arguments;

        public ToolCall(String id, String name, JsonObject arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments == null ? new JsonObject() : arguments;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public JsonObject getArguments() {
            return arguments;
        }
    }

    public static class AiResponse {
        private final String content;
        private final List<ToolCall> toolCalls;

        public AiResponse(String content, List<ToolCall> toolCalls) {
            this.content = content == null ? "" : content;
            this.toolCalls = toolCalls == null ? new ArrayList<>() : toolCalls;
        }

        public String getContent() {
            return content;
        }

        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    public AiApiClient(ConfigManager configManager, ModelManager modelManager) {
        this.configManager = configManager;
        this.modelManager = modelManager;
        this.plugin = McAiAssistant.getInstance();
        this.gson = new Gson();
        this.httpClient = createHttpClient();
    }

    /**
     * 创建 HTTP 客户端
     */
    private OkHttpClient createHttpClient() {
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(configManager.getConnectTimeout(), TimeUnit.SECONDS)
                .readTimeout(configManager.getReadTimeout(), TimeUnit.SECONDS)
                .writeTimeout(configManager.getWriteTimeout(), TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true);

        // 配置连接池
        ConnectionPool connectionPool = new ConnectionPool(
                configManager.getConnectionPoolMaxIdle(),
                configManager.getConnectionKeepAliveDuration(),
                TimeUnit.SECONDS);
        clientBuilder.connectionPool(connectionPool);

        // 配置调度器（控制并发请求数）
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(configManager.getMaxRequests());
        dispatcher.setMaxRequestsPerHost(configManager.getMaxRequestsPerHost());
        clientBuilder.dispatcher(dispatcher);

        // DNS 优化
        if (configManager.isDnsOptimizationEnabled()) {
            clientBuilder.dns(hostname -> {
                try {
                    // 使用系统DNS解析，并返回所有IP地址
                    InetAddress[] addresses = InetAddress.getAllByName(hostname);
                    return Arrays.asList(addresses);
                } catch (UnknownHostException e) {
                    // 如果解析失败，返回空列表让OkHttp使用默认DNS
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().warning("DNS解析失败: " + hostname + " - " + e.getMessage());
                    }
                    return Arrays.asList();
                }
            });
        }

        // 根据配置决定是否添加浏览器模拟拦截器
        if (configManager.isSimulateBrowser()) {
            clientBuilder.addInterceptor(chain -> {
                // 添加通用的浏览器请求头
                Request originalRequest = chain.request();
                Request.Builder requestBuilder = originalRequest.newBuilder()
                        .addHeader("DNT", "1")
                        .addHeader("Upgrade-Insecure-Requests", "1")
                        .addHeader("Sec-Ch-Ua",
                                "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                        .addHeader("Sec-Ch-Ua-Mobile", "?0")
                        .addHeader("Sec-Ch-Ua-Platform", "\"Windows\"");

                return chain.proceed(requestBuilder.build());
            });
        }

        return clientBuilder.build();
    }

    /**
     * 更新配置
     */
    public void updateConfig(ConfigManager newConfigManager) {
        this.configManager = newConfigManager;
        this.httpClient = createHttpClient();
    }

    /**
     * 发送消息到 AI API
     *
     * @param message 用户消息
     * @param context 上下文消息列表
     * @return AI 响应
     */
    public String sendMessage(String message, List<String> context) throws IOException {
        return sendMessage(message, context, false);
    }

    /**
     * 发送消息到 AI API（带知识库信息）
     *
     * @param message       用户消息
     * @param context       上下文消息列表
     * @param knowledgeInfo 知识库查询结果
     * @return AI 响应
     */
    public String sendMessageWithKnowledge(String message, List<String> context, String knowledgeInfo)
            throws IOException {
        AiResponse response = sendMessageInternal(message, context, false, knowledgeInfo, false);
        return response.getContent();
    }

    /**
     * 发送消息到 AI API
     *
     * @param message  用户消息
     * @param context  上下文消息列表
     * @param isSearch 是否为搜索请求
     * @return AI 响应
     */
    public String sendMessage(String message, List<String> context, boolean isSearch) throws IOException {
        return sendMessage(message, context, isSearch, null);
    }

    /**
     * 发送消息到 AI API
     *
     * @param message       用户消息
     * @param context       上下文消息列表
     * @param isSearch      是否为搜索请求
     * @param knowledgeInfo 知识库信息
     * @return AI 响应
     */
    public String sendMessage(String message, List<String> context, boolean isSearch, String knowledgeInfo)
            throws IOException {
        AiResponse response = sendMessageInternal(message, context, isSearch, knowledgeInfo, false);
        return response.getContent();
    }

    /**
     * 发送消息到 AI API（允许工具调用）
     *
     * @param message 用户消息
     * @param context 上下文消息列表
     * @return AI 响应（含工具调用）
     */
    public AiResponse sendMessageWithTools(String message, List<String> context) throws IOException {
        return sendMessageInternal(message, context, false, null, true);
    }

    /**
     * 发送消息到 AI API（内部实现）
     *
     * @param message       用户消息
     * @param context       上下文消息列表
     * @param isSearch      是否为搜索请求
     * @param knowledgeInfo 知识库信息
     * @param includeTools  是否启用工具调用
     * @return AI 响应（含工具调用）
     */
    private AiResponse sendMessageInternal(String message, List<String> context, boolean isSearch, String knowledgeInfo,
                                           boolean includeTools) throws IOException {
        // 构建请求体
        JsonObject requestBody = buildRequestBody(message, context, isSearch, knowledgeInfo, includeTools);

        // 创建 HTTP 请求
        Request.Builder requestBuilder = new Request.Builder()
                .url(configManager.getApiUrl() + "/chat/completions")
                .addHeader("Authorization", "Bearer " + configManager.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.get("application/json")));

        // 根据配置决定是否添加浏览器模拟请求头
        if (configManager.isSimulateBrowser()) {
            requestBuilder
                    .addHeader("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("Accept", "application/json, text/plain, */*")
                    .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    // 移除 Accept-Encoding 让 OkHttp 自动处理压缩和解压缩
                    .addHeader("Connection", "keep-alive")
                    .addHeader("Sec-Fetch-Dest", "empty")
                    .addHeader("Sec-Fetch-Mode", "cors")
                    .addHeader("Sec-Fetch-Site", "same-origin")
                    .addHeader("Cache-Control", "no-cache")
                    .addHeader("Pragma", "no-cache")
                    .addHeader("Origin", configManager.getApiUrl())
                    .addHeader("Referer", configManager.getApiUrl() + "/");
        }

        Request request = requestBuilder.build();

        // 调试信息：记录请求详情
        if (configManager.isDebugMode()) {
            plugin.getLogger().info("=== API 请求调试信息 ===");
            plugin.getLogger().info("请求类型: " + (isSearch ? "搜索请求" : "普通请求"));
            plugin.getLogger().info("请求 URL: " + request.url());
            plugin.getLogger().info("请求方法: " + request.method());

            // 记录请求头（隐藏敏感信息）
            plugin.getLogger().info("请求头信息:");
            request.headers().forEach(pair -> {
                String name = pair.getFirst();
                String value = pair.getSecond();
                if (name.toLowerCase().contains("authorization")) {
                    value = "Bearer ***" + value.substring(Math.max(0, value.length() - 8));
                }
                plugin.getLogger().info("  " + name + ": " + value);
            });

            // 记录请求体（截取部分内容）
            if (request.body() != null) {
                String requestBodyStr = requestBody.toString();
                if (requestBodyStr.length() > 500) {
                    plugin.getLogger().info("请求体预览: " + requestBodyStr.substring(0, 500) + "...");
                } else {
                    plugin.getLogger().info("完整请求体: " + requestBodyStr);
                }
            }
            plugin.getLogger().info("=== 开始发送请求 ===");
        }

        // 发送请求并处理响应
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorDetails = getErrorDetails(response, isSearch);
                throw new IOException("API 请求失败: " + response.code() + " " + response.message() + errorDetails);
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("API 响应为空");
            }

            String responseString = responseBody.string();

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("API 响应成功: " + response.code() + " " + response.message());
                plugin.getLogger().info("响应内容长度: " + responseString.length() + " 字符");
                if (responseString.length() < 1000) {
                    plugin.getLogger().info("完整响应内容: " + responseString);
                } else {
                    plugin.getLogger().info("响应内容预览: " + responseString.substring(0, 500) + "...");
                }
            }

            return parseResponseWithTools(responseString);
        }
    }

    /**
     * 发送工具结果后的追问请求（用于知识库等工具调用闭环）
     */
    public AiResponse sendFollowUpWithToolResult(String message, List<String> context, String assistantContent,
                                                 ToolCall toolCall, String toolResultContent) throws IOException {
        JsonObject requestBody = buildFollowUpRequestBody(message, context, assistantContent, toolCall, toolResultContent);
        return executeRequest(requestBody, false);
    }

    /**
     * 构建 Agent 初始 messages（system + context + user）
     */
    public JsonArray buildAgentInitialMessages(String message, List<String> context) {
        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", buildSystemPrompt(false, null));
        messages.add(systemMessage);

        if (context != null && !context.isEmpty()) {
            for (String contextMsg : context) {
                if (contextMsg != null && !contextMsg.trim().isEmpty()) {
                    JsonObject contextMessage = new JsonObject();
                    contextMessage.addProperty("role", "user");
                    contextMessage.addProperty("content", contextMsg);
                    messages.add(contextMessage);
                }
            }
        }

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", buildEnhancedMessage(message, false));
        messages.add(userMessage);

        return messages;
    }

    /**
     * 以给定 messages 发送一次 chat/completions（可选启用 tools）
     */
    public AiResponse sendChatCompletionWithMessages(JsonArray messages, boolean includeTools) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", modelManager.getEffectiveChatModel());
        requestBody.addProperty("max_tokens", configManager.getMaxTokens());
        requestBody.addProperty("temperature", configManager.getTemperature());
        requestBody.add("messages", messages);
        if (includeTools) {
            appendTools(requestBody);
        }
        return executeRequest(requestBody, false);
    }

    /**
     * 构建 API 请求体
     */
    private JsonObject buildRequestBody(String message, List<String> context) {
        return buildRequestBody(message, context, false, null, false);
    }

    /**
     * 构建 API 请求体
     */
    private JsonObject buildRequestBody(String message, List<String> context, boolean isSearch) {
        return buildRequestBody(message, context, isSearch, null, false);
    }

    /**
     * 构建 API 请求体
     */
    private JsonObject buildRequestBody(String message, List<String> context, boolean isSearch, String knowledgeInfo,
                                        boolean includeTools) {
        JsonObject requestBody = new JsonObject();
 
        // 根据是否为搜索请求选择模型
        String model = isSearch ? configManager.getSearchModel() : modelManager.getEffectiveChatModel();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", configManager.getMaxTokens());
        requestBody.addProperty("temperature", configManager.getTemperature());

        // 构建消息数组
        JsonArray messages = new JsonArray();

        // 添加系统提示词
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        String systemPrompt = buildSystemPrompt(isSearch, knowledgeInfo);
        systemMessage.addProperty("content", systemPrompt);
        messages.add(systemMessage);

        // 添加上下文消息
        if (context != null && !context.isEmpty()) {
            for (String contextMsg : context) {
                if (contextMsg != null && !contextMsg.trim().isEmpty()) {
                    JsonObject contextMessage = new JsonObject();
                    contextMessage.addProperty("role", "user");
                    contextMessage.addProperty("content", contextMsg);
                    messages.add(contextMessage);
                }
            }
        }

        // 添加当前用户消息（包含服务器信息）
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        String enhancedMessage = buildEnhancedMessage(message, isSearch);
        userMessage.addProperty("content", enhancedMessage);
        messages.add(userMessage);

        requestBody.add("messages", messages);

        if (includeTools) {
            appendTools(requestBody);
        }

        return requestBody;
    }

    /**
     * 构建工具追问的请求体（包含 assistant 工具调用与 tool 结果）
     */
    private JsonObject buildFollowUpRequestBody(String message, List<String> context, String assistantContent,
                                                ToolCall toolCall, String toolResultContent) {
        JsonObject requestBody = new JsonObject();

        String model = modelManager.getEffectiveChatModel();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", configManager.getMaxTokens());
        requestBody.addProperty("temperature", configManager.getTemperature());

        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", buildSystemPrompt(false, null));
        messages.add(systemMessage);

        if (context != null && !context.isEmpty()) {
            for (String contextMsg : context) {
                if (contextMsg != null && !contextMsg.trim().isEmpty()) {
                    JsonObject contextMessage = new JsonObject();
                    contextMessage.addProperty("role", "user");
                    contextMessage.addProperty("content", contextMsg);
                    messages.add(contextMessage);
                }
            }
        }

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", buildEnhancedMessage(message, false));
        messages.add(userMessage);

        JsonObject assistantMessage = new JsonObject();
        assistantMessage.addProperty("role", "assistant");
        assistantMessage.addProperty("content", assistantContent == null ? "" : assistantContent);

        if (toolCall != null && toolCall.getName() != null) {
            JsonArray toolCalls = new JsonArray();
            JsonObject call = new JsonObject();
            if (toolCall.getId() != null && !toolCall.getId().isEmpty()) {
                call.addProperty("id", toolCall.getId());
            }
            call.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", toolCall.getName());
            function.addProperty("arguments", gson.toJson(toolCall.getArguments()));
            call.add("function", function);
            toolCalls.add(call);
            assistantMessage.add("tool_calls", toolCalls);
        }
        messages.add(assistantMessage);

        if (toolCall != null && toolCall.getName() != null) {
            JsonObject toolMessage = new JsonObject();
            toolMessage.addProperty("role", "tool");
            if (toolCall.getId() != null && !toolCall.getId().isEmpty()) {
                toolMessage.addProperty("tool_call_id", toolCall.getId());
            }
            toolMessage.addProperty("content", toolResultContent == null ? "" : toolResultContent);
            messages.add(toolMessage);
        }

        requestBody.add("messages", messages);
        appendTools(requestBody);
        return requestBody;
    }

    /**
     * 发送请求并解析响应
     */
    private AiResponse executeRequest(JsonObject requestBody, boolean isSearch) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(configManager.getApiUrl() + "/chat/completions")
                .addHeader("Authorization", "Bearer " + configManager.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.get("application/json")));

        if (configManager.isSimulateBrowser()) {
            requestBuilder
                    .addHeader("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("Accept", "application/json, text/plain, */*")
                    .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .addHeader("Connection", "keep-alive")
                    .addHeader("Sec-Fetch-Dest", "empty")
                    .addHeader("Sec-Fetch-Mode", "cors")
                    .addHeader("Sec-Fetch-Site", "same-origin")
                    .addHeader("Cache-Control", "no-cache")
                    .addHeader("Pragma", "no-cache")
                    .addHeader("Origin", configManager.getApiUrl())
                    .addHeader("Referer", configManager.getApiUrl() + "/");
        }

        Request request = requestBuilder.build();

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("=== API 请求调试信息 ===");
            plugin.getLogger().info("请求类型: " + (isSearch ? "搜索请求" : "普通请求"));
            plugin.getLogger().info("请求 URL: " + request.url());
            plugin.getLogger().info("请求方法: " + request.method());
            plugin.getLogger().info("请求头信息:");
            request.headers().forEach(pair -> {
                String name = pair.getFirst();
                String value = pair.getSecond();
                if (name.toLowerCase().contains("authorization")) {
                    value = "Bearer ***" + value.substring(Math.max(0, value.length() - 8));
                }
                plugin.getLogger().info("  " + name + ": " + value);
            });
            if (request.body() != null) {
                String requestBodyStr = requestBody.toString();
                if (requestBodyStr.length() > 500) {
                    plugin.getLogger().info("请求体预览: " + requestBodyStr.substring(0, 500) + "...");
                } else {
                    plugin.getLogger().info("完整请求体: " + requestBodyStr);
                }
            }
            plugin.getLogger().info("=== 开始发送请求 ===");
        }

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorDetails = getErrorDetails(response, isSearch);
                throw new IOException("API 请求失败: " + response.code() + " " + response.message() + errorDetails);
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("API 响应为空");
            }

            String responseString = responseBody.string();

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("API 响应成功: " + response.code() + " " + response.message());
                plugin.getLogger().info("响应内容长度: " + responseString.length() + " 字符");
                if (responseString.length() < 1000) {
                    plugin.getLogger().info("完整响应内容: " + responseString);
                } else {
                    plugin.getLogger().info("响应内容预览: " + responseString.substring(0, 500) + "...");
                }
            }

            return parseResponseWithTools(responseString);
        }
    }

    /**
     * 添加工具定义到请求体
     */
    private void appendTools(JsonObject requestBody) {
        JsonArray tools = new JsonArray();

        if (configManager.isKnowledgeEnabled()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", "query_knowledge");
            function.addProperty("description", "查询本地知识库，获取与玩家问题相关的片段。");
            JsonObject parameters = new JsonObject();
            parameters.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            JsonObject query = new JsonObject();
            query.addProperty("type", "string");
            query.addProperty("description", "用于知识库检索的关键词或短语");
            properties.add("query", query);
            parameters.add("properties", properties);
            JsonArray required = new JsonArray();
            required.add("query");
            parameters.add("required", required);
            function.add("parameters", parameters);
            tool.add("function", function);
            tools.add(tool);
        }

        if (configManager.isImageGenerationEnabled()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", "create_image");
            function.addProperty("description", "生成图像参考图并返回生成结果路径。");
            JsonObject parameters = new JsonObject();
            parameters.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            JsonObject prompt = new JsonObject();
            prompt.addProperty("type", "string");
            prompt.addProperty("description", "英文图像提示词，必须是纯英文");
            JsonObject alt = new JsonObject();
            alt.addProperty("type", "string");
            alt.addProperty("description", "图像中文描述，用于游戏内展示");
            properties.add("prompt", prompt);
            properties.add("alt", alt);
            parameters.add("properties", properties);
            JsonArray required = new JsonArray();
            required.add("prompt");
            required.add("alt");
            parameters.add("required", required);
            function.add("parameters", parameters);
            tool.add("function", function);
            tools.add(tool);
        }

        if (commandWhitelistManagerEnabled()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", "execute_command");
            function.addProperty("description", "在服务器后台执行允许的指令（受白名单限制）。");
            JsonObject parameters = new JsonObject();
            parameters.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            JsonObject command = new JsonObject();
            command.addProperty("type", "string");
            // 在 tool schema 的 description 中附带实时白名单，便于模型做决策（避免盲猜被拒绝）。
            String commandDesc = "要执行的命令，不需要以 / 开头";
            CommandWhitelistManager manager = plugin.getCommandWhitelistManager();
            if (manager != null) {
                List<String> whitelist = manager.getWhitelist();
                if (whitelist != null && !whitelist.isEmpty()) {
                    int maxItems = 25;
                    int maxChars = 280;
                    StringBuilder sb = new StringBuilder(commandDesc);
                    sb.append("。当前白名单: ");
                    int count = 0;
                    for (String item : whitelist) {
                        if (item == null) continue;
                        String trimmed = item.trim();
                        if (trimmed.isEmpty()) continue;
                        if (count > 0) sb.append(", ");
                        sb.append(trimmed);
                        count++;
                        if (count >= maxItems || sb.length() >= maxChars) {
                            break;
                        }
                    }
                    if (whitelist.size() > count) {
                        sb.append(" ...");
                    }
                    sb.append(" (共").append(whitelist.size()).append("项)");
                    commandDesc = sb.toString();
                }
            }
            command.addProperty("description", commandDesc);
            properties.add("command", command);
            parameters.add("properties", properties);
            JsonArray required = new JsonArray();
            required.add("command");
            parameters.add("required", required);
            function.add("parameters", parameters);
            tool.add("function", function);
            tools.add(tool);
        }

        if (tools.size() > 0) {
            requestBody.add("tools", tools);
        }
    }

    private boolean commandWhitelistManagerEnabled() {
        CommandWhitelistManager manager = plugin.getCommandWhitelistManager();
        return manager != null && manager.isEnabled();
    }

    /**
     * 构建增强的系统提示词
     */
    private String buildSystemPrompt(boolean isSearch) {
        return buildSystemPrompt(isSearch, null);
    }

    /**
     * 构建增强的系统提示词（带知识库信息）
     */
    private String buildSystemPrompt(boolean isSearch, String knowledgeInfo) {
        String basePrompt = configManager.getSystemPrompt();

        // 场景一：已经获取了知识库信息，任务是直接回答。
        if (knowledgeInfo != null && !knowledgeInfo.trim().isEmpty()) {

            basePrompt += "\n\n" + knowledgeInfo + "\n\n请仅依据这些片段回答玩家问题，如仍无法解答请直接说明“暂未收录相关资料”。";

            return basePrompt;

        }



        // 场景二：没有知识库信息，AI 需要判断是否使用工具。
        // 在此场景下，才添加所有可用的工具指令。

        basePrompt += "\n\n# 工具使用原则\n"
                + "1) 只有在确实需要外部能力时才使用工具；能直接回答就直接回答。\n"
                + "2) 不要为了“看起来更聪明”而调用工具；避免重复调用同一工具。\n"
                + "3) 本地知识库查询工具 query_knowledge 最多调用 2 次；超过次数请改为直接回答或说明信息不足。\n"
                + "4) 后台指令 execute_command 仅用于必要的服务器操作，且必须符合白名单；不要尝试绕过限制。\n"
                + "5) 图像生成 create_image 仅在玩家明确要求“画图/生成图片/参考图”等时才调用。\n"
                + "6) 工具调用由系统处理，请勿输出任何 XML 标签格式的伪工具指令。\n";

        // 添加知识库查询工具指令
        if (configManager.isKnowledgeEnabled()) {
            String knowledgeInstructions = "\n\n# Local Knowledge Base \n 服务器会在 config/knowledge base 目录维护 Markdown 文档，凡是涉及 " + configManager.getKnowledgeContent() + " 的问题，都应该先发起一次 query_knowledge。\n" +
                    "当需要查询时，请调用工具 query_knowledge，参数 query 为关键词或短语。\n" +
                    "插件会并行执行 AI 搜索与关键字检索，并把整理后的 <knowledge_search> 片段返回给你。\n" +
                    "如果返回值为 null 表示知识库暂无对应内容，必须如实告知玩家，禁止编造答案。";
            basePrompt += knowledgeInstructions;
        }

        // 添加图像生成工具指令
        if (configManager.isImageGenerationEnabled()) {
            basePrompt += "\n\n# Image Creation Tool \n 如果玩家要求画图，你要使用 create_image 给玩家生成参考图，每次响应只能使用一次\n" +
                    "当需要生成图像时，请调用工具 create_image，并提供 prompt 与 alt 两个字段\n" +
                    "Prompt 必须是纯英语，否则无法生成，Prompt 不支持任何额外的配置参数\n" +
                    "Alt 是图像的中文描述，用于在游戏中显示，应该简洁美观\n" +
                    "使用工具并回复玩具一段话，建议这些画挂在哪或者怎么使用参考图，也可以单纯夸：\"这听起来太有创意了，我立刻帮你创作一张 XXX 的草稿，你可以把它...\" " +
                    "示例：create_image(prompt=\"beautiful sunset over mountains\", alt=\"美丽的山间日落\")";
        }

        // 添加搜索特定指令
        if (isSearch) {
            basePrompt += "\n\n特别注意：这是一个网络搜索请求。请使用你的搜索能力来获取最新的信息，并提供准确、及时的答案。";
        }
        
        return basePrompt;
    }

    /**
     * 构建包含服务器信息的增强消息
     */
    private String buildEnhancedMessage(String originalMessage, boolean isSearch) {
        StringBuilder enhancedMessage = new StringBuilder();

        // 添加当前时间信息
        LocalDateTime now = LocalDateTime.now();
        String currentTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        enhancedMessage.append("当前时间: ").append(currentTime).append("\n");

        // 添加在线玩家信息
        List<String> onlinePlayers = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());

        enhancedMessage.append("当前在线玩家数量: ").append(onlinePlayers.size()).append("\n");
        if (!onlinePlayers.isEmpty() && onlinePlayers.size() <= 10) {
            enhancedMessage.append("在线玩家: ").append(String.join(", ", onlinePlayers)).append("\n");
        } else if (onlinePlayers.size() > 10) {
            enhancedMessage.append("在线玩家: ").append(String.join(", ", onlinePlayers.subList(0, 10)))
                    .append(" 等 ").append(onlinePlayers.size()).append(" 人\n");
        }

        enhancedMessage.append("\n用户问题: ").append(originalMessage);

        return enhancedMessage.toString();
    }

    /**
     * 获取详细的错误信息
     */
    private String getErrorDetails(Response response, boolean isSearch) {
        StringBuilder errorDetails = new StringBuilder();

        try {
            // 添加请求类型信息
            errorDetails.append("\n请求类型: ").append(isSearch ? "搜索请求" : "普通请求");

            // 添加响应头信息
            if (configManager.isDebugMode()) {
                errorDetails.append("\n响应头信息:");
                response.headers().forEach(pair -> errorDetails.append("\n  ").append(pair.getFirst()).append(": ")
                        .append(pair.getSecond()));
            }

            // 获取错误响应体
            ResponseBody errorBody = response.body();
            if (errorBody != null) {
                String errorContent = errorBody.string();
                if (!errorContent.isEmpty()) {
                    errorDetails.append("\n错误响应内容: ").append(errorContent);

                    // 尝试解析 JSON 错误信息
                    try {
                        JsonObject errorJson = gson.fromJson(errorContent, JsonObject.class);
                        if (errorJson.has("error")) {
                            JsonObject error = errorJson.getAsJsonObject("error");
                            if (error.has("message")) {
                                errorDetails.append("\nAPI 错误消息: ").append(error.get("message").getAsString());
                            }
                            if (error.has("type")) {
                                errorDetails.append("\n错误类型: ").append(error.get("type").getAsString());
                            }
                            if (error.has("code")) {
                                errorDetails.append("\n错误代码: ").append(error.get("code").getAsString());
                            }
                        }
                    } catch (Exception e) {
                        errorDetails.append("\n无法解析错误 JSON: ").append(e.getMessage());
                    }
                }
            }

            // 添加请求信息（调试模式下）
            if (configManager.isDebugMode()) {
                errorDetails.append("\n请求 URL: ").append(response.request().url());
                errorDetails.append("\n请求方法: ").append(response.request().method());

                // 添加请求头信息（隐藏敏感信息）
                errorDetails.append("\n请求头信息:");
                response.request().headers().forEach(pair -> {
                    String name = pair.getFirst();
                    String value = pair.getSecond();
                    if (name.toLowerCase().contains("authorization")) {
                        value = "Bearer ***" + value.substring(Math.max(0, value.length() - 8));
                    }
                    errorDetails.append("\n  ").append(name).append(": ").append(value);
                });
            }

        } catch (Exception e) {
            errorDetails.append("\n获取错误详情时出错: ").append(e.getMessage());
        }

        return errorDetails.toString();
    }

    /**
     * 解析 API 响应
     */
    private String parseResponse(String responseJson) throws IOException {
        AiResponse response = parseResponseWithTools(responseJson);
        return response.getContent();
    }

    /**
     * 解析 API 响应（含工具调用）
     */
    private AiResponse parseResponseWithTools(String responseJson) throws IOException {
        try {
            JsonObject response = gson.fromJson(responseJson, JsonObject.class);

            // 检查是否有错误
            if (response.has("error")) {
                JsonObject error = response.getAsJsonObject("error");
                String errorMessage = error.get("message").getAsString();
                throw new IOException("API 错误: " + errorMessage);
            }

            // 提取响应内容
            if (response.has("choices")) {
                JsonArray choices = response.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    JsonObject firstChoice = choices.get(0).getAsJsonObject();
                    if (firstChoice.has("message")) {
                        JsonObject message = firstChoice.getAsJsonObject("message");
                        String content = "";
                        if (message.has("content") && !message.get("content").isJsonNull()) {
                            content = message.get("content").getAsString().trim();
                        }
                        List<ToolCall> toolCalls = new ArrayList<>();
                        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
                            JsonArray toolCallsArray = message.getAsJsonArray("tool_calls");
                            for (int i = 0; i < toolCallsArray.size(); i++) {
                                JsonObject call = toolCallsArray.get(i).getAsJsonObject();
                                if (!call.has("function")) {
                                    continue;
                                }
                                String id = call.has("id") && !call.get("id").isJsonNull()
                                        ? call.get("id").getAsString()
                                        : ("toolcall_" + i);
                                JsonObject function = call.getAsJsonObject("function");
                                if (function == null || !function.has("name")) {
                                    continue;
                                }
                                String name = function.get("name").getAsString();
                                JsonObject arguments = new JsonObject();
                                if (function.has("arguments") && !function.get("arguments").isJsonNull()) {
                                    try {
                                        if (function.get("arguments").isJsonObject()) {
                                            arguments = function.getAsJsonObject("arguments");
                                        } else {
                                            String argText = function.get("arguments").getAsString();
                                            if (argText != null && !argText.trim().isEmpty()) {
                                                arguments = gson.fromJson(argText, JsonObject.class);
                                            }
                                        }
                                    } catch (Exception e) {
                                        if (configManager.isDebugMode()) {
                                            plugin.getLogger().warning("解析工具参数失败: " + e.getMessage());
                                        }
                                    }
                                }
                                toolCalls.add(new ToolCall(id, name, arguments));
                            }
                        }
                        return new AiResponse(content, toolCalls);
                    }
                }
            }

            throw new IOException("无法解析 API 响应");

        } catch (Exception e) {
            throw new IOException("解析响应失败: " + e.getMessage());
        }
    }

    /**
     * 关闭客户端
     */
    public void shutdown() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }
}
