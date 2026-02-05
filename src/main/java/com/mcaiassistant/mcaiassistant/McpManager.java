package com.mcaiassistant.mcaiassistant;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器管理器（配置加载 + 工具发现 + 工具调用）
 */
public class McpManager {

    private static final String CONFIG_FILE_NAME = "mcp.json";
    private static final String TOOL_NAME = "mcp_call";
    private static final int TOOL_NAME_LIMIT = 20;

    private final McAiAssistant plugin;
    private final Gson gson = new Gson();
    private final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
    private final File configFile;

    private Map<String, McpServerState> serverStates = new LinkedHashMap<>();

    public McpManager(McAiAssistant plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), CONFIG_FILE_NAME);
    }

    public void initialize() {
        loadConfig();
        refreshToolCache();
    }

    public void reload() {
        loadConfig();
        refreshToolCache();
    }

    public void shutdown() {
        // 当前实现为按需短连接，无需额外关闭动作
    }

    public boolean hasEnabledServers() {
        return getEnabledServerCount() > 0;
    }

    public int getEnabledServerCount() {
        int count = 0;
        for (McpServerState state : serverStates.values()) {
            if (state != null && state.config != null && state.config.enabled) {
                count++;
            }
        }
        return count;
    }

    public int getCachedToolCount() {
        int count = 0;
        for (McpServerState state : serverStates.values()) {
            if (state != null && state.tools != null) {
                count += state.tools.size();
            }
        }
        return count;
    }

    public String callTool(String serverName, String toolName, JsonObject arguments) {
        if (serverName == null || serverName.trim().isEmpty()) {
            return "缺少 server 参数。";
        }
        if (toolName == null || toolName.trim().isEmpty()) {
            return "缺少 tool 参数。";
        }
        McpServerState state = serverStates.get(serverName.trim());
        if (state == null || state.config == null || !state.config.enabled) {
            return "MCP 服务器未启用或不存在: " + serverName;
        }

        try (McpSyncClient client = createClient(state.config)) {
            client.initialize();
            Map<String, Object> args = convertArguments(arguments);
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName.trim(), args, null);
            McpSchema.CallToolResult result = client.callTool(request);
            return formatCallToolResult(result);
        } catch (Exception e) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().warning("[MCP] 工具调用失败: " + e.getMessage());
            }
            return "MCP 工具调用失败: " + e.getMessage();
        }
    }

    public JsonObject buildToolDefinition() {
        if (!hasEnabledServers()) {
            return null;
        }

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", TOOL_NAME);
        function.addProperty("description", buildToolDescription());

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        JsonObject properties = new JsonObject();

        JsonObject server = new JsonObject();
        server.addProperty("type", "string");
        server.addProperty("description", "MCP 服务器名称（mcp.json 中的键）");

        JsonObject toolName = new JsonObject();
        toolName.addProperty("type", "string");
        toolName.addProperty("description", "要调用的 MCP 工具名称");

        JsonObject arguments = new JsonObject();
        arguments.addProperty("type", "object");
        arguments.addProperty("description", "工具参数对象，字段需符合该工具的输入 schema");
        arguments.addProperty("additionalProperties", true);

        properties.add("server", server);
        properties.add("tool", toolName);
        properties.add("arguments", arguments);

        parameters.add("properties", properties);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("server");
        required.add("tool");
        parameters.add("required", required);
        function.add("parameters", parameters);
        tool.add("function", function);
        return tool;
    }

    private void loadConfig() {
        ensureConfigFile();
        McpConfigFile config = new McpConfigFile();

        try (Reader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
            McpConfigFile parsed = gson.fromJson(reader, McpConfigFile.class);
            if (parsed != null) {
                config = parsed;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[MCP] 读取 mcp.json 失败，将使用空配置: " + e.getMessage());
        }

        Map<String, McpServerState> nextStates = new LinkedHashMap<>();
        if (config.mcpServers != null) {
            for (Map.Entry<String, McpServerConfig> entry : config.mcpServers.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                McpServerConfig serverConfig = entry.getValue();
                serverConfig.normalize();
                nextStates.put(entry.getKey(), new McpServerState(serverConfig));
            }
        }
        this.serverStates = nextStates;
    }

    private void ensureConfigFile() {
        if (!plugin.getDataFolder().exists()) {
            if (!plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("[MCP] 创建数据目录失败: " + plugin.getDataFolder().getAbsolutePath());
            }
        }
        if (!configFile.exists()) {
            plugin.saveResource(CONFIG_FILE_NAME, false);
        }
    }

    private void refreshToolCache() {
        for (Map.Entry<String, McpServerState> entry : serverStates.entrySet()) {
            McpServerState state = entry.getValue();
            if (state == null || state.config == null || !state.config.enabled) {
                continue;
            }
            try (McpSyncClient client = createClient(state.config)) {
                client.initialize();
                McpSchema.ListToolsResult result = client.listTools();
                state.tools = extractToolNames(result);
                state.lastError = null;
            } catch (Exception e) {
                state.tools = Collections.emptyList();
                state.lastError = e.getMessage();
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().warning("[MCP] 列表获取失败: " + entry.getKey() + " - " + e.getMessage());
                }
            }
        }
    }

    private McpSyncClient createClient(McpServerConfig config) {
        McpClientTransport transport = createTransport(config);
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    private McpClientTransport createTransport(McpServerConfig config) {
        if (config.isStdio()) {
            if (config.command == null || config.command.isBlank()) {
                throw new IllegalArgumentException("stdio 模式缺少 command");
            }
            ServerParameters params = ServerParameters.builder(config.command)
                    .args(config.args == null ? Collections.emptyList() : config.args)
                    .env(config.env)
                    .build();
            return new StdioClientTransport(params, jsonMapper);
        }

        String url = config.url;
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("远程模式缺少 url");
        }
        java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder();
        if (config.headers != null) {
            for (Map.Entry<String, String> header : config.headers.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
            }
        }

        if (config.isSse()) {
            return HttpClientSseClientTransport.builder(url)
                    .jsonMapper(jsonMapper)
                    .requestBuilder(requestBuilder)
                    .build();
        }
        return HttpClientStreamableHttpTransport.builder(url)
                .jsonMapper(jsonMapper)
                .requestBuilder(requestBuilder)
                .build();
    }

    private List<String> extractToolNames(McpSchema.ListToolsResult result) {
        if (result == null) {
            return Collections.emptyList();
        }
        Object toolsObj = readProperty(result, "tools");
        if (!(toolsObj instanceof List<?> tools)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (Object tool : tools) {
            Object name = readProperty(tool, "name");
            if (name instanceof String str && !str.isBlank()) {
                names.add(str);
            }
        }
        return names;
    }

    private String buildToolDescription() {
        StringBuilder builder = new StringBuilder("调用已配置的 MCP 服务器工具。");
        if (!hasEnabledServers()) {
            return builder.toString();
        }
        builder.append("可用服务器: ");
        int serverIndex = 0;
        for (Map.Entry<String, McpServerState> entry : serverStates.entrySet()) {
            McpServerState state = entry.getValue();
            if (state == null || state.config == null || !state.config.enabled) {
                continue;
            }
            if (serverIndex > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey());
            serverIndex++;
        }

        for (Map.Entry<String, McpServerState> entry : serverStates.entrySet()) {
            McpServerState state = entry.getValue();
            if (state == null || state.config == null || !state.config.enabled) {
                continue;
            }
            if (state.tools == null || state.tools.isEmpty()) {
                continue;
            }
            builder.append("\n").append(entry.getKey()).append(" 工具: ");
            int count = 0;
            for (String toolName : state.tools) {
                if (count > 0) {
                    builder.append(", ");
                }
                builder.append(toolName);
                count++;
                if (count >= TOOL_NAME_LIMIT) {
                    builder.append(" ...");
                    break;
                }
            }
        }
        return builder.toString();
    }

    private String formatCallToolResult(McpSchema.CallToolResult result) {
        if (result == null) {
            return "MCP 工具返回空结果。";
        }
        return gson.toJson(result);
    }

    private Map<String, Object> convertArguments(JsonObject arguments) {
        if (arguments == null) {
            return Collections.emptyMap();
        }
        return gson.fromJson(arguments, new TypeToken<Map<String, Object>>() {}.getType());
    }

    private Object readProperty(Object target, String propertyName) {
        if (target == null || propertyName == null) {
            return null;
        }
        try {
            var method = target.getClass().getMethod(propertyName);
            return method.invoke(target);
        } catch (Exception ignored) {
            // 尝试 getter 形式
        }
        try {
            String getter = "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
            var method = target.getClass().getMethod(getter);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static class McpServerState {
        private final McpServerConfig config;
        private List<String> tools;
        private String lastError;

        private McpServerState(McpServerConfig config) {
            this.config = config;
            this.tools = Collections.emptyList();
        }
    }

    private static class McpConfigFile {
        private Map<String, McpServerConfig> mcpServers = new LinkedHashMap<>();
    }

    private static class McpServerConfig {
        private boolean enabled = true;
        private String transport;
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private String url;
        private Map<String, String> headers = new LinkedHashMap<>();

        private void normalize() {
            if (transport != null) {
                transport = transport.trim().toLowerCase();
            }
            if (transport == null || transport.isEmpty()) {
                if (command != null && !command.isBlank()) {
                    transport = "stdio";
                } else if (url != null && !url.isBlank()) {
                    transport = "streamable-http";
                }
            }
        }

        private boolean isStdio() {
            return "stdio".equals(transport);
        }

        private boolean isSse() {
            return "sse".equals(transport) || "http-sse".equals(transport);
        }
    }
}
