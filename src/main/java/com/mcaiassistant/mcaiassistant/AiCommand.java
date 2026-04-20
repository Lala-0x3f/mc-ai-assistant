package com.mcaiassistant.mcaiassistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /ai 统一管理指令入口
 */
public class AiCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_SUB_COMMANDS = Arrays.asList(
            "help", "info", "tools", "reload", "model", "test", "whitelist", "cmdwl"
    );

    private final McAiAssistant plugin;
    private final ConfigManager configManager;
    private final ModelManager modelManager;
    private final TestCommand testCommand;
    private final ModelCommand modelCommand;
    private final AiCommandWhitelistCommand whitelistCommand;
    private final CommandWhitelistManager commandWhitelistManager;
    private final McpManager mcpManager;

    public AiCommand(McAiAssistant plugin, ConfigManager configManager, ModelManager modelManager,
                     TestCommand testCommand, ModelCommand modelCommand,
                     AiCommandWhitelistCommand whitelistCommand, CommandWhitelistManager commandWhitelistManager,
                     McpManager mcpManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.modelManager = modelManager;
        this.testCommand = testCommand;
        this.modelCommand = modelCommand;
        this.whitelistCommand = whitelistCommand;
        this.commandWhitelistManager = commandWhitelistManager;
        this.mcpManager = mcpManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mcaiassistant.admin")) {
            sender.sendMessage(ChatColor.RED + "没有权限执行该命令。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "help":
            case "?":
                sendHelp(sender, label);
                return true;
            case "info":
                sendInfo(sender);
                return true;
            case "tools":
                sendTools(sender);
                return true;
            case "reload":
                return handleReload(sender, label, subArgs);
            case "model":
                return modelCommand.onCommand(sender, command, label + " model", subArgs);
            case "test":
                return testCommand.onCommand(sender, command, label + " test", subArgs);
            case "whitelist":
            case "cmdwl":
                return whitelistCommand.onCommand(sender, command, label + " whitelist", subArgs);
            default:
                sender.sendMessage(ChatColor.YELLOW + "未知子命令: " + sub);
                sendHelp(sender, label);
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mcaiassistant.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase();
            List<String> result = new ArrayList<>();
            for (String opt : ROOT_SUB_COMMANDS) {
                if (prefix.isEmpty() || opt.startsWith(prefix)) {
                    result.add(opt);
                }
            }
            return result;
        }

        String sub = args[0] == null ? "" : args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "test":
                return testCommand.onTabComplete(sender, command, "ai test", subArgs);
            case "model":
                return modelCommand.onTabComplete(sender, command, "ai model", subArgs);
            case "whitelist":
            case "cmdwl":
                return whitelistCommand.onTabComplete(sender, command, "ai whitelist", subArgs);
            default:
                return Collections.emptyList();
        }
    }

    private boolean handleReload(CommandSender sender, String label, String[] args) {
        if (args.length > 0) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /" + label + " reload");
            return true;
        }

        sender.sendMessage(ChatColor.GRAY + "[AI] 正在重新加载配置...");
        try {
            plugin.reloadPluginConfig();
            sender.sendMessage(ChatColor.GREEN + "[AI] 配置重载完成。");
        } catch (Exception e) {
            plugin.getLogger().warning("执行 /" + label + " reload 失败: " + e.getMessage());
            sender.sendMessage(ChatColor.RED + "[AI] 配置重载失败: " + e.getMessage());
        }
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "AI 管理指令：");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " info - 查看插件与模型信息");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " tools - 查看启用的工具");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " reload - 重新加载配置");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " model [modelId] - 查看或切换模型");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " test <all|model|kb> - 健康检查");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " whitelist <list|add|remove|reload> - 管理白名单");
        sender.sendMessage(ChatColor.DARK_GRAY + "兼容旧指令: /model  /aitest  /aicmdwl");
    }

    private void sendInfo(CommandSender sender) {
        String version = plugin.getDescription().getVersion();
        String effectiveModel = modelManager.getEffectiveChatModel();
        boolean overridden = modelManager.getOverrideChatModelId() != null;
        String source = overridden ? (ChatColor.YELLOW + "覆盖") : (ChatColor.GRAY + "配置");
        String defaultModel = configManager.getModel();

        sender.sendMessage(ChatColor.AQUA + "[AI] 插件信息");
        sender.sendMessage(ChatColor.GRAY + "版本: " + ChatColor.WHITE + version);
        sender.sendMessage(ChatColor.GRAY + "聊天模型: " + ChatColor.WHITE + effectiveModel
                + ChatColor.DARK_GRAY + "  (来源: " + source + ChatColor.DARK_GRAY + ")");
        if (overridden) {
            sender.sendMessage(ChatColor.DARK_GRAY + "配置默认: " + ChatColor.GRAY + defaultModel);
        }
        sender.sendMessage(ChatColor.GRAY + "搜索模型: " + ChatColor.WHITE + configManager.getSearchModel());
        sender.sendMessage(ChatColor.GRAY + "知识库: " + formatStatus(configManager.isKnowledgeEnabled()));
        sender.sendMessage(ChatColor.GRAY + "图像生成: " + formatStatus(configManager.isImageGenerationEnabled()));
        sender.sendMessage(ChatColor.GRAY + "后台指令: " + formatStatus(isCommandToolEnabled()));
    }

    private void sendTools(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "[AI] 工具状态");

        boolean knowledgeEnabled = configManager.isKnowledgeEnabled();
        String knowledgeDetail = knowledgeEnabled
                ? ("AI检索:" + onOff(configManager.isKnowledgeAiSearchEnabled())
                + " 直接检索:" + onOff(configManager.isKnowledgeDirectSearchEnabled()))
                : "未启用";
        sender.sendMessage(ChatColor.GRAY + "query_knowledge: " + formatStatus(knowledgeEnabled)
                + ChatColor.DARK_GRAY + " (" + knowledgeDetail + ")");

        boolean imageEnabled = configManager.isImageGenerationEnabled();
        sender.sendMessage(ChatColor.GRAY + "create_image: " + formatStatus(imageEnabled));

        boolean commandEnabled = isCommandToolEnabled();
        String whitelistDetail = commandEnabled
                ? ("白名单数: " + commandWhitelistManager.getWhitelist().size())
                : "未启用";
        sender.sendMessage(ChatColor.GRAY + "execute_command: " + formatStatus(commandEnabled)
                + ChatColor.DARK_GRAY + " (" + whitelistDetail + ")");

        boolean mcpConfigured = mcpManager != null && mcpManager.getEnabledServerCount() > 0;
        if (mcpConfigured) {
            boolean mcpEnabled = mcpManager.hasEnabledServers();
            String mcpDetail = "可用服务器: " + mcpManager.getAvailableServerCount()
                    + " 配置启用: " + mcpManager.getEnabledServerCount()
                    + " 熔断中: " + mcpManager.getCircuitOpenServerCount()
                    + " 工具缓存: " + mcpManager.getCachedToolCount();
            sender.sendMessage(ChatColor.GRAY + "mcp_call: " + formatStatus(mcpEnabled)
                    + ChatColor.DARK_GRAY + " (" + mcpDetail + ")");
            renderMcpTree(sender);
        }
    }

    private boolean isCommandToolEnabled() {
        return commandWhitelistManager != null && commandWhitelistManager.isEnabled();
    }

    private String formatStatus(boolean enabled) {
        return enabled ? (ChatColor.GREEN + "启用") : (ChatColor.RED + "关闭");
    }

    private String onOff(boolean enabled) {
        return enabled ? "开" : "关";
    }

    private void renderMcpTree(CommandSender sender) {
        if (mcpManager == null) {
            sender.sendMessage(ChatColor.DARK_GRAY + "└─ MCP: 未初始化");
            return;
        }
        List<McpManager.McpServerSnapshot> servers = mcpManager.getServerSnapshots();
        if (servers.isEmpty()) {
            sender.sendMessage(ChatColor.DARK_GRAY + "└─ MCP: 未配置服务器");
            return;
        }
        if (sender instanceof Player player) {
            sendMcpTreeToPlayer(player, servers);
        } else {
            sendMcpTreePlain(sender, servers);
        }
    }

    private void sendMcpTreeToPlayer(Player player, List<McpManager.McpServerSnapshot> servers) {
        player.sendMessage(Component.text("└─ MCP Servers", NamedTextColor.DARK_GRAY)
                .append(Component.text(" (悬停查看详情)", NamedTextColor.GRAY)));
        for (int i = 0; i < servers.size(); i++) {
            McpManager.McpServerSnapshot server = servers.get(i);
            boolean lastServer = i == servers.size() - 1;
            String serverPrefix = lastServer ? "   └─ " : "   ├─ ";
            Component serverLine = Component.text(serverPrefix, NamedTextColor.DARK_GRAY)
                    .append(Component.text(server.getServerName() + " ", NamedTextColor.WHITE))
                    .append(Component.text("[" + getServerStatusText(server) + "] ", getServerStatusColor(server)))
                    .append(Component.text("tools=" + server.getTools().size(), NamedTextColor.DARK_GRAY))
                    .hoverEvent(buildServerHover(server));
            player.sendMessage(serverLine);

            List<McpManager.McpToolSnapshot> tools = server.getTools();
            for (int j = 0; j < tools.size(); j++) {
                McpManager.McpToolSnapshot tool = tools.get(j);
                boolean lastTool = j == tools.size() - 1;
                String toolPrefix = (lastServer ? "      " : "   │  ") + (lastTool ? "└─ " : "├─ ");
                Component toolLine = Component.text(toolPrefix, NamedTextColor.DARK_GRAY)
                        .append(Component.text(tool.getName(), NamedTextColor.AQUA))
                        .append(Component.text("  ⓘ", NamedTextColor.GRAY))
                        .hoverEvent(buildToolHover(server, tool));
                player.sendMessage(toolLine);
            }
        }
    }

    private void sendMcpTreePlain(CommandSender sender, List<McpManager.McpServerSnapshot> servers) {
        sender.sendMessage(ChatColor.DARK_GRAY + "└─ MCP Servers");
        for (int i = 0; i < servers.size(); i++) {
            McpManager.McpServerSnapshot server = servers.get(i);
            boolean lastServer = i == servers.size() - 1;
            String serverPrefix = lastServer ? "   └─ " : "   ├─ ";
            sender.sendMessage(ChatColor.DARK_GRAY + serverPrefix + ChatColor.AQUA + server.getServerName() + " "
                    + getServerStatusTextBracket(server)
                    + ChatColor.GRAY + " (tools: " + server.getTools().size() + ")");
            List<McpManager.McpToolSnapshot> tools = server.getTools();
            for (int j = 0; j < tools.size(); j++) {
                McpManager.McpToolSnapshot tool = tools.get(j);
                boolean lastTool = j == tools.size() - 1;
                String toolPrefix = (lastServer ? "      " : "   │  ") + (lastTool ? "└─ " : "├─ ");
                sender.sendMessage(ChatColor.DARK_GRAY + toolPrefix + ChatColor.GREEN + tool.getName());
            }
        }
    }

    private Component buildServerHover(McpManager.McpServerSnapshot server) {
        TextComponent.Builder b = Component.text();
        b.append(Component.text("MCP Server", NamedTextColor.GOLD))
                .append(Component.newline())
                .append(labelValue("名称", server.getServerName(), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(labelValue("状态", getServerStatusText(server), getServerStatusColor(server)))
                .append(Component.newline())
                .append(labelValue("工具数", String.valueOf(server.getTools().size()), NamedTextColor.AQUA));
        if (server.getCircuitRemainingMillis() > 0) {
            b.append(Component.newline())
                    .append(labelValue("熔断剩余", ((server.getCircuitRemainingMillis() + 999L) / 1000L) + " 秒", NamedTextColor.GOLD));
        }
        if (server.getLastError() != null && !server.getLastError().isBlank()) {
            b.append(Component.newline())
                    .append(Component.text("最近错误: ", NamedTextColor.RED))
                    .append(Component.text(server.getLastError(), NamedTextColor.GRAY));
        }
        return b.build();
    }

    private Component buildToolHover(McpManager.McpServerSnapshot server, McpManager.McpToolSnapshot tool) {
        TextComponent.Builder b = Component.text();
        b.append(Component.text("MCP Tool", NamedTextColor.GOLD))
                .append(Component.newline())
                .append(labelValue("Server", server.getServerName(), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(labelValue("Tool", tool.getName(), NamedTextColor.AQUA))
                .append(Component.newline())
                .append(Component.text("描述: ", NamedTextColor.YELLOW))
                .append(Component.text(
                        tool.getDescription() == null || tool.getDescription().isBlank() ? "(无)" : tool.getDescription(),
                        NamedTextColor.GRAY
                ));

        if (tool.getInputSchema() != null && !tool.getInputSchema().isBlank()) {
            b.append(Component.newline())
                    .append(Component.text("inputSchema:", NamedTextColor.YELLOW))
                    .append(Component.newline())
                    .append(renderJsonWithHighlight(tool.getInputSchema()));
        }
        return b.build();
    }

    private Component labelValue(String label, String value, NamedTextColor valueColor) {
        return Component.text(label + ": ", NamedTextColor.YELLOW)
                .append(Component.text(value == null ? "" : value, valueColor));
    }

    private Component renderJsonWithHighlight(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Component.text("(empty)", NamedTextColor.DARK_GRAY);
        }
        try {
            JsonElement root = JsonParser.parseString(rawJson);
            TextComponent.Builder builder = Component.text();
            appendJsonElement(builder, root, 0);
            return builder.build();
        } catch (Exception e) {
            return Component.text(rawJson, NamedTextColor.GRAY);
        }
    }

    private void appendJsonElement(TextComponent.Builder builder, JsonElement element, int indent) {
        if (element == null || element.isJsonNull()) {
            builder.append(Component.text("null", NamedTextColor.RED));
            return;
        }
        if (element.isJsonObject()) {
            appendJsonObject(builder, element.getAsJsonObject(), indent);
            return;
        }
        if (element.isJsonArray()) {
            appendJsonArray(builder, element.getAsJsonArray(), indent);
            return;
        }
        appendJsonPrimitive(builder, element.getAsJsonPrimitive());
    }

    private void appendJsonObject(TextComponent.Builder builder, JsonObject obj, int indent) {
        builder.append(Component.text("{", NamedTextColor.GRAY));
        if (obj.isEmpty()) {
            builder.append(Component.text("}", NamedTextColor.GRAY));
            return;
        }
        int size = obj.entrySet().size();
        int idx = 0;
        for (var entry : obj.entrySet()) {
            idx++;
            builder.append(Component.newline());
            appendIndent(builder, indent + 1);
            builder.append(Component.text("\"" + entry.getKey() + "\"", NamedTextColor.AQUA));
            builder.append(Component.text(": ", NamedTextColor.GRAY));
            appendJsonElement(builder, entry.getValue(), indent + 1);
            if (idx < size) {
                builder.append(Component.text(",", NamedTextColor.GRAY));
            }
        }
        builder.append(Component.newline());
        appendIndent(builder, indent);
        builder.append(Component.text("}", NamedTextColor.GRAY));
    }

    private void appendJsonArray(TextComponent.Builder builder, JsonArray arr, int indent) {
        builder.append(Component.text("[", NamedTextColor.GRAY));
        if (arr.isEmpty()) {
            builder.append(Component.text("]", NamedTextColor.GRAY));
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            builder.append(Component.newline());
            appendIndent(builder, indent + 1);
            appendJsonElement(builder, arr.get(i), indent + 1);
            if (i < arr.size() - 1) {
                builder.append(Component.text(",", NamedTextColor.GRAY));
            }
        }
        builder.append(Component.newline());
        appendIndent(builder, indent);
        builder.append(Component.text("]", NamedTextColor.GRAY));
    }

    private void appendJsonPrimitive(TextComponent.Builder builder, JsonPrimitive primitive) {
        if (primitive.isBoolean()) {
            builder.append(Component.text(primitive.getAsBoolean() ? "true" : "false", NamedTextColor.LIGHT_PURPLE));
            return;
        }
        if (primitive.isNumber()) {
            builder.append(Component.text(primitive.getAsString(), NamedTextColor.GOLD));
            return;
        }
        builder.append(Component.text("\"" + primitive.getAsString() + "\"", NamedTextColor.GREEN));
    }

    private void appendIndent(TextComponent.Builder builder, int indent) {
        if (indent <= 0) {
            return;
        }
        builder.append(Component.text("  ".repeat(indent), NamedTextColor.DARK_GRAY));
    }

    private String getServerStatusText(McpManager.McpServerSnapshot server) {
        if (!server.isEnabled()) {
            return "禁用";
        }
        if (server.getCircuitRemainingMillis() > 0) {
            return "熔断";
        }
        if (server.isAvailable()) {
            return "可用";
        }
        return "不可用";
    }

    private NamedTextColor getServerStatusColor(McpManager.McpServerSnapshot server) {
        if (!server.isEnabled()) {
            return NamedTextColor.DARK_GRAY;
        }
        if (server.getCircuitRemainingMillis() > 0) {
            return NamedTextColor.GOLD;
        }
        if (server.isAvailable()) {
            return NamedTextColor.GREEN;
        }
        return NamedTextColor.RED;
    }

    private String getServerStatusTextBracket(McpManager.McpServerSnapshot server) {
        String status = getServerStatusText(server);
        ChatColor color;
        if (!server.isEnabled()) {
            color = ChatColor.DARK_GRAY;
        } else if (server.getCircuitRemainingMillis() > 0) {
            color = ChatColor.GOLD;
        } else if (server.isAvailable()) {
            color = ChatColor.GREEN;
        } else {
            color = ChatColor.RED;
        }
        return color + "[" + status + "]";
    }
}
