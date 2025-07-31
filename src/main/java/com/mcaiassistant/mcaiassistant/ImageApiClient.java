package com.mcaiassistant.mcaiassistant;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import okhttp3.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 图像生成 API 客户端
 * 负责与图像生成服务进行通信
 */
public class ImageApiClient {
    private final JavaPlugin plugin;
    private ConfigManager configManager;
    private OkHttpClient httpClient;
    private final Gson gson;

    public ImageApiClient(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.gson = new Gson();
        
        // 创建 HTTP 客户端，设置较长的超时时间（图像生成可能需要更长时间）
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS) // 图像生成可能需要更长时间
                .build();
    }

    /**
     * 图像生成响应类
     */
    public static class ImageResponse {
        private final boolean success;
        private final String jobId;
        private final List<String> images;
        private final String errorMessage;

        public ImageResponse(boolean success, String jobId, List<String> images, String errorMessage) {
            this.success = success;
            this.jobId = jobId;
            this.images = images;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public String getJobId() { return jobId; }
        public List<String> getImages() { return images; }
        public String getErrorMessage() { return errorMessage; }
    }

    /**
     * 生成图像
     * @param prompt 图像描述（必须是英文）
     * @return 图像生成响应
     */
    public ImageResponse createImage(String prompt) {
        try {
            String imageApiUrl = configManager.getImageApiUrl();
            if (imageApiUrl == null || imageApiUrl.trim().isEmpty()) {
                return new ImageResponse(false, null, null, "图像生成 API URL 未配置");
            }

            // 构建请求 JSON
            JsonObject requestJson = new JsonObject();
            requestJson.addProperty("prompt", prompt);
            requestJson.addProperty("ar", "1:1"); // 强制 1:1 比例

            RequestBody requestBody = RequestBody.create(
                requestJson.toString(),
                MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(imageApiUrl + "/create")
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .build();

            if (configManager.isDebugMode()) {
                plugin.getLogger().info("[图像生成] 发送请求到: " + imageApiUrl + "/create");
                plugin.getLogger().info("[图像生成] 请求内容: " + requestJson.toString());
            }

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (configManager.isDebugMode()) {
                    plugin.getLogger().info("[图像生成] 响应状态码: " + response.code());
                    plugin.getLogger().info("[图像生成] 响应内容: " + responseBody);
                }

                if (response.isSuccessful()) {
                    return parseSuccessResponse(responseBody);
                } else {
                    return parseErrorResponse(response.code(), responseBody);
                }
            }

        } catch (IOException e) {
            plugin.getLogger().warning("[图像生成] 网络请求失败: " + e.getMessage());
            if (configManager.isDebugMode()) {
                e.printStackTrace();
            }
            return new ImageResponse(false, null, null, "网络请求失败: " + e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().warning("[图像生成] 请求处理失败: " + e.getMessage());
            if (configManager.isDebugMode()) {
                e.printStackTrace();
            }
            return new ImageResponse(false, null, null, "请求处理失败: " + e.getMessage());
        }
    }

    /**
     * 解析成功响应
     */
    private ImageResponse parseSuccessResponse(String responseBody) {
        try {
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            
            String jobId = jsonResponse.has("job_id") ? jsonResponse.get("job_id").getAsString() : null;
            List<String> images = new ArrayList<>();
            
            if (jsonResponse.has("images") && jsonResponse.get("images").isJsonArray()) {
                JsonArray imagesArray = jsonResponse.getAsJsonArray("images");
                for (int i = 0; i < imagesArray.size(); i++) {
                    images.add(imagesArray.get(i).getAsString());
                }
            }

            return new ImageResponse(true, jobId, images, null);
            
        } catch (Exception e) {
            plugin.getLogger().warning("[图像生成] 解析成功响应失败: " + e.getMessage());
            return new ImageResponse(false, null, null, "响应解析失败");
        }
    }

    /**
     * 解析错误响应
     */
    private ImageResponse parseErrorResponse(int statusCode, String responseBody) {
        String errorMessage;
        
        try {
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            if (jsonResponse.has("detail")) {
                errorMessage = jsonResponse.get("detail").getAsString();
            } else {
                errorMessage = getDefaultErrorMessage(statusCode);
            }
        } catch (Exception e) {
            errorMessage = getDefaultErrorMessage(statusCode);
        }

        return new ImageResponse(false, null, null, errorMessage);
    }

    /**
     * 获取默认错误消息
     */
    private String getDefaultErrorMessage(int statusCode) {
        switch (statusCode) {
            case 429:
                return "请求过于频繁，请稍后再试";
            case 500:
                return "服务器内部错误";
            case 504:
                return "请求超时，请稍后再试";
            default:
                return "图像生成失败 (状态码: " + statusCode + ")";
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
    /**
     * 更新配置
     */
    public void updateConfig(ConfigManager newConfigManager) {
        this.configManager = newConfigManager;
        // 注意：如果 httpClient 的配置也依赖于 configManager，这里也需要重新创建 httpClient
    }
}
