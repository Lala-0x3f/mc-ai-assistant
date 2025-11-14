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
        return sendMessage(message, context, false, knowledgeInfo);
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
        // 构建请求体
        JsonObject requestBody = buildRequestBody(message, context, isSearch, knowledgeInfo);

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

            return parseResponse(responseString);
        }
    }

    /**
     * 构建 API 请求体
     */
    private JsonObject buildRequestBody(String message, List<String> context) {
        return buildRequestBody(message, context, false, null);
    }

    /**
     * 构建 API 请求体
     */
    private JsonObject buildRequestBody(String message, List<String> context, boolean isSearch) {
        return buildRequestBody(message, context, isSearch, null);
    }

    /**
     * 构建 API 请求体
     */
    private JsonObject buildRequestBody(String message, List<String> context, boolean isSearch, String knowledgeInfo) {
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

        return requestBody;
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
            basePrompt += "\n\n<useful_info>\n" + knowledgeInfo + "\n</useful_info>\n\n" +
                    "重要：请你只根据以上提供的 <useful_info> 信息来回答用户的问题，不要再使用任何工具（如 query_knowledge 或 create_image）。";
            return basePrompt;
        }

        // 场景二：没有知识库信息，AI 需要判断是否使用工具。
        // 在此场景下，才添加所有可用的工具指令。

        // 添加知识库查询工具指令
        if (configManager.isKnowledgeEnabled()) {
            String knowledgeInstructions = "\n\n # Document Search Tool \n 这个工具非常有用，可以搜索文档，当用户询问需要解答问题、询问到专业知识的时候，你必须在回复中立刻使用、禁止未查询就试图解答问题，系统会自动查询并根据文档向用户提供专业又详细的答案。\n" +
                    "请分析用户真正的需求，哪怕和文档有一丝关联性也要查询。文档包括以下内容：" + configManager.getKnowledgeContent() + "\n\n" +
                    "要使用 query_knowledge 工具，你需要在回复中添加一行：\n <query_knowledge query=\"用户问题关键词\" />\n\n" +
                    "使用工具并回复用户：\"为了向你准确的答复，我需要查询XXX的文档，请稍等一会\" 结束！" +
                    "示例：<query_knowledge query=\"中世纪教堂的结构以及mc中的做法\" />" +
                    "可以搜索多个内容：<query_knowledge query=\"Axiom 有关曲线的工具和 Arceon 中制作拱门的指令\" />";
            basePrompt += knowledgeInstructions;
        }

        // 添加图像生成工具指令
        if (configManager.isImageGenerationEnabled()) {
            basePrompt += "\n\n# Image Creation Tool \n 如果玩家要求画图，你要使用 create_image 给玩家生成参考图，每次响应只能使用一次\n" +
                    "如果你要生成一张图像，请在响应中添加一行\n\n" +
                    "<create_image prompt=\"\" alt=\"\" />\n\n" +
                    "Prompt 必须是纯英语，否则无法生成，Prompt 不支持任何额外的配置参数\n" +
                    "Alt 是图像的中文描述，用于在游戏中显示，应该简洁美观\n" +
                    "需要格式完全准确，才能生成图像\n" +
                    "使用工具并回复玩具一段话，建议这些画挂在哪或者怎么使用参考图，也可以单纯夸：\"这听起来太有创意了，我立刻帮你创作一张 XXX 的草稿，你可以把它...\" " +
                    "示例：<create_image prompt=\"beautiful sunset over mountains\" alt=\"美丽的山间日落\" />";
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
                        if (message.has("content")) {
                            return message.get("content").getAsString().trim();
                        }
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
