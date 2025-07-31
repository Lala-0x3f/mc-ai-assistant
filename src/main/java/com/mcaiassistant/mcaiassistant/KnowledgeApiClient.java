package com.mcaiassistant.mcaiassistant;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 知识库 API 客户端
 * 负责与 Dify 知识库 API 进行通信
 */
public class KnowledgeApiClient {
    
    private final Gson gson;
    private OkHttpClient httpClient;
    private ConfigManager configManager;
    private final McAiAssistant plugin;

    public KnowledgeApiClient(ConfigManager configManager) {
        this.configManager = configManager;
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
                TimeUnit.SECONDS
        );
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
     * 查询知识库
     *
     * @param query 查询问题
     * @return 知识库返回的文档片段，如果查询失败或没有结果则返回null
     */
    public String queryKnowledge(String query) throws IOException {
        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库API] 🔍 开始知识库查询");
            plugin.getLogger().info("[知识库API] 查询内容: " + query);
        }

        if (!configManager.isKnowledgeEnabled()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库API] ❌ 知识库功能未启用，跳过查询");
            }
            return null;
        }

        String apiKey = configManager.getKnowledgeApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[知识库API] ❌ 知识库 API Key 未配置，跳过查询");
            }
            return null;
        }

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库API] ✅ 配置检查通过，准备构建请求");
        }
        
        // 构建请求体
        JsonObject requestBody = new JsonObject();
        requestBody.add("inputs", new JsonObject()); // 空的 inputs 对象
        requestBody.addProperty("query", query);
        requestBody.addProperty("user", "server");
        
        // 创建 HTTP 请求
        String apiUrl = configManager.getKnowledgeApiUrl() + "/v1/chat-messages";
        Request.Builder requestBuilder = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.get("application/json")));

        Request request = requestBuilder.build();

        if (configManager.isDebugMode()) {
            plugin.getLogger().info("[知识库API] === 知识库 API 请求详情 ===");
            plugin.getLogger().info("[知识库API] 请求 URL: " + request.url());
            plugin.getLogger().info("[知识库API] 请求方法: " + request.method());

            // 记录请求头（隐藏敏感信息）
            plugin.getLogger().info("[知识库API] 请求头:");
            request.headers().forEach(pair -> {
                String headerName = pair.getFirst();
                String headerValue = pair.getSecond();
                if ("Authorization".equalsIgnoreCase(headerName)) {
                    headerValue = "Bearer " + maskApiKey(apiKey);
                }
                plugin.getLogger().info("[知识库API]   " + headerName + ": " + headerValue);
            });

            // 记录请求体
            String requestBodyStr = requestBody.toString();
            plugin.getLogger().info("[知识库API] 请求体长度: " + requestBodyStr.length() + " 字符");
            plugin.getLogger().info("[知识库API] 请求体内容: " + requestBodyStr);
            plugin.getLogger().info("[知识库API] === 🚀 开始发送知识库请求 ===");
        }

        // 发送请求并处理响应
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorDetails = getErrorDetails(response);
                if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[知识库API] ❌ 知识库 API 请求失败: " + response.code() + " " + response.message() + errorDetails);
                }
                return null; // 知识库查询失败时返回null，不影响正常AI对话
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                if (configManager.isDebugMode()) {
                    plugin.getLogger().warning("[知识库API] ❌ 知识库 API 响应为空");
                }
                return null;
            }

            String responseString = responseBody.string();

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库API] ✅ 知识库 API 响应成功: " + response.code() + " " + response.message());
                plugin.getLogger().info("[知识库API] 响应内容长度: " + responseString.length() + " 字符");
                plugin.getLogger().info("[知识库API] 响应头信息:");
                response.headers().forEach(pair -> {
                    plugin.getLogger().info("[知识库API]   " + pair.getFirst() + ": " + pair.getSecond());
                });
                if (responseString.length() < 1000) {
                    plugin.getLogger().info("[知识库API] 完整响应内容: " + responseString);
                } else {
                    plugin.getLogger().info("[知识库API] 响应内容预览: " + responseString.substring(0, 500) + "...");
                }
            }

            return parseKnowledgeResponse(responseString);
        }
    }
    
    /**
     * 解析知识库响应
     */
    private String parseKnowledgeResponse(String responseString) {
        try {
            JsonObject responseJson = JsonParser.parseString(responseString).getAsJsonObject();
            
            if (responseJson.has("answer")) {
                String answer = responseJson.get("answer").getAsString();
                if (answer != null && !answer.trim().isEmpty()) {
                    if (configManager.isDebugMode()) {
                        plugin.getLogger().info("[知识库API] ✅ 知识库查询成功，返回内容长度: " + answer.length() + " 字符");
                        if (answer.length() < 300) {
                            plugin.getLogger().info("[知识库API] 完整答案内容: " + answer);
                        } else {
                            plugin.getLogger().info("[知识库API] 答案内容预览: " + answer.substring(0, 300) + "...");
                        }
                    }
                    return answer;
                }
            }

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[知识库API] ❌ 知识库响应中没有找到有效的 answer 字段");
            }
            return null;

        } catch (Exception e) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().warning("[知识库API] ❌ 解析知识库响应失败: " + e.getMessage());
                plugin.getLogger().warning("[知识库API] 异常类型: " + e.getClass().getSimpleName());
                if (e.getCause() != null) {
                    plugin.getLogger().warning("[知识库API] 根本原因: " + e.getCause().getMessage());
                }
            }
            return null;
        }
    }
    
    /**
     * 获取错误详情
     */
    private String getErrorDetails(Response response) {
        try {
            ResponseBody errorBody = response.body();
            if (errorBody != null) {
                String errorContent = errorBody.string();
                if (configManager.isDebugMode()) {
                    return "\n错误响应内容: " + errorContent;
                }
            }
        } catch (IOException e) {
            // 忽略读取错误响应的异常
        }
        return "";
    }
    
    /**
     * 隐藏 API Key 的敏感部分
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
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
