package com.mcaiassistant.mcaiassistant;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 搜索 API 客户端
 * 负责与 OpenAI 的 v1/responses 接口通信，实现网络搜索功能
 */
public class SearchApiClient {
    
    private final Gson gson;
    private OkHttpClient httpClient;
    private ConfigManager configManager;
    private final McAiAssistant plugin;
    
    public SearchApiClient(ConfigManager configManager) {
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
                .connectTimeout(configManager.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(configManager.getTimeout(), TimeUnit.SECONDS)
                .writeTimeout(configManager.getTimeout(), TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true);
        
        // 根据配置决定是否添加浏览器模拟拦截器
        if (configManager.isSimulateBrowser()) {
            clientBuilder.addInterceptor(chain -> {
                // 添加通用的浏览器请求头
                Request originalRequest = chain.request();
                Request.Builder requestBuilder = originalRequest.newBuilder()
                        .addHeader("DNT", "1")
                        .addHeader("Upgrade-Insecure-Requests", "1")
                        .addHeader("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
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
     * 发送搜索请求
     * 
     * @param query 搜索查询
     * @return 搜索结果
     */
    public SearchResult search(String query) throws IOException {
        // 构建请求体
        JsonObject requestBody = buildSearchRequestBody(query);
        
        // 创建 HTTP 请求
        // 构建正确的搜索 API URL
        String searchApiUrl = buildSearchApiUrl();
        Request.Builder requestBuilder = new Request.Builder()
                .url(searchApiUrl)
                .addHeader("Authorization", "Bearer " + configManager.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.get("application/json")));
        
        // 根据配置决定是否添加浏览器模拟请求头
        if (configManager.isSimulateBrowser()) {
            requestBuilder
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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
            plugin.getLogger().info("=== 搜索 API 请求调试信息 ===");
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
            plugin.getLogger().info("=== 开始发送搜索请求 ===");
        }
        
        // 发送请求并处理响应
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorDetails = getErrorDetails(response);
                throw new IOException("搜索 API 请求失败: " + response.code() + " " + response.message() + errorDetails);
            }
            
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("搜索 API 响应为空");
            }
            
            String responseString = responseBody.string();
            
            if (configManager.isDebugMode()) {
                plugin.getLogger().info("搜索 API 响应成功: " + response.code() + " " + response.message());
                plugin.getLogger().info("响应内容长度: " + responseString.length() + " 字符");
                if (responseString.length() < 1000) {
                    plugin.getLogger().info("完整响应内容: " + responseString);
                } else {
                    plugin.getLogger().info("响应内容预览: " + responseString.substring(0, 500) + "...");
                }
            }
            
            return parseSearchResponse(responseString);
        }
    }
    
    /**
     * 构建搜索 API URL
     */
    private String buildSearchApiUrl() {
        String baseUrl = configManager.getApiUrl();
        String searchUrl;

        // 如果 baseUrl 已经包含 /v1，则直接拼接 /responses
        if (baseUrl.endsWith("/v1")) {
            searchUrl = baseUrl + "/responses";
        }
        // 否则拼接 /v1/responses
        else {
            searchUrl = baseUrl + "/v1/responses";
        }

        // 调试模式下记录 URL 构建过程
        if (configManager.isDebugMode()) {
            plugin.getLogger().info("=== 搜索 API URL 构建 ===");
            plugin.getLogger().info("配置的基础 URL: " + baseUrl);
            plugin.getLogger().info("最终搜索 URL: " + searchUrl);
        }

        return searchUrl;
    }

    /**
     * 构建搜索请求体
     */
    private JsonObject buildSearchRequestBody(String query) {
        JsonObject requestBody = new JsonObject();
        
        // 设置模型
        requestBody.addProperty("model", configManager.getSearchModel());
        
        // 设置工具
        JsonArray tools = new JsonArray();
        JsonObject searchTool = new JsonObject();
        searchTool.addProperty("type", configManager.getSearchToolType());
        tools.add(searchTool);
        requestBody.add("tools", tools);
        
        // 设置输入（添加前缀）
        String enhancedQuery = configManager.getSearchPromptPrefix() + query;
        requestBody.addProperty("input", enhancedQuery);
        
        return requestBody;
    }
    
    /**
     * 解析搜索响应
     */
    private SearchResult parseSearchResponse(String responseJson) {
        try {
            JsonObject response = gson.fromJson(responseJson, JsonObject.class);
            
            // 初始化结果
            SearchResult result = new SearchResult();
            
            // 提取输出数组
            if (response.has("output") && response.get("output").isJsonArray()) {
                JsonArray output = response.getAsJsonArray("output");
                
                // 遍历输出数组
                for (JsonElement element : output) {
                    if (!element.isJsonObject()) continue;
                    JsonObject item = element.getAsJsonObject();
                    
                    // 提取搜索查询
                    if (item.has("type") && "web_search_call".equals(item.get("type").getAsString())) {
                        if (item.has("action") && item.getAsJsonObject("action").has("query")) {
                            result.setSearchQuery(item.getAsJsonObject("action").get("query").getAsString());
                        }
                    }
                    
                    // 提取搜索结果文本
                    if (item.has("type") && "message".equals(item.get("type").getAsString())) {
                        if (item.has("content") && item.getAsJsonArray("content").size() > 0) {
                            JsonObject content = item.getAsJsonArray("content").get(0).getAsJsonObject();
                            if (content.has("text")) {
                                String rawText = content.get("text").getAsString();
                                // 处理 markdown 链接，提取链接信息并清理文本
                                String processedText = processMarkdownLinks(rawText, result);
                                result.setResultText(processedText);
                            }
                        }
                    }
                }
            }
            
            return result;
        } catch (Exception e) {
            if (configManager.isDebugMode()) {
                plugin.getLogger().severe("解析搜索响应失败: " + e.getMessage());
                e.printStackTrace();
            }
            
            // 返回错误结果
            SearchResult errorResult = new SearchResult();
            errorResult.setSearchQuery("解析失败");
            errorResult.setResultText("解析搜索响应时出错: " + e.getMessage());
            return errorResult;
        }
    }
    
    /**
     * 处理 markdown 链接，提取链接信息并清理文本
     * 将 [标题](url) 格式的链接提取出来，并从文本中移除
     */
    private String processMarkdownLinks(String text, SearchResult result) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }

        // 正则表达式匹配 markdown 链接格式: [标题](url)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[([^\\]]+)\\]\\(([^\\)]+)\\)");
        java.util.regex.Matcher matcher = pattern.matcher(text);

        // 提取所有链接信息
        while (matcher.find()) {
            String title = matcher.group(1);
            String url = matcher.group(2);
            result.addLink(new LinkInfo(title, url));
        }

        // 从文本中移除所有 markdown 链接，只保留纯文本
        return text.replaceAll("\\s*\\([^\\)]+\\)", "");
    }

    /**
     * 获取详细的错误信息
     */
    private String getErrorDetails(Response response) {
        StringBuilder errorDetails = new StringBuilder();
        
        try {
            // 添加响应头信息
            if (configManager.isDebugMode()) {
                errorDetails.append("\n响应头信息:");
                response.headers().forEach(pair -> 
                    errorDetails.append("\n  ").append(pair.getFirst()).append(": ").append(pair.getSecond())
                );
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
            
        } catch (Exception e) {
            errorDetails.append("\n获取错误详情时出错: ").append(e.getMessage());
        }
        
        return errorDetails.toString();
    }
    
    /**
     * 链接信息类
     */
    public static class LinkInfo {
        private String title;
        private String url;

        public LinkInfo(String title, String url) {
            this.title = title;
            this.url = url;
        }

        public String getTitle() {
            return title;
        }

        public String getUrl() {
            return url;
        }
    }

    /**
     * 搜索结果类
     */
    public static class SearchResult {
        private String searchQuery;
        private String resultText;
        private List<LinkInfo> links;

        public SearchResult() {
            this.links = new ArrayList<>();
        }

        public String getSearchQuery() {
            return searchQuery;
        }

        public void setSearchQuery(String searchQuery) {
            this.searchQuery = searchQuery;
        }

        public String getResultText() {
            return resultText;
        }

        public void setResultText(String resultText) {
            this.resultText = resultText;
        }

        public List<LinkInfo> getLinks() {
            return links;
        }

        public void setLinks(List<LinkInfo> links) {
            this.links = links;
        }

        public void addLink(LinkInfo link) {
            this.links.add(link);
        }
    }
}
