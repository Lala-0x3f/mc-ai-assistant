package com.mcaiassistant.mcaiassistant;

import net.kyori.adventure.text.Component;
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
            "help", "info", "tools", "model", "test", "whitelist", "cmdwl"
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

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "AI 管理指令：");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " info - 查看插件与模型信息");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " tools - 查看启用的工具");
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
        boolean mcpEnabled = mcpManager != null && mcpManager.hasEnabledServers();
        String mcpDetail;
        if (!mcpConfigured) {
            mcpDetail = "未启用";
        } else {
            mcpDetail = "可用服务器: " + mcpManager.getAvailableServerCount()
                    + " 配置启用: " + mcpManager.getEnabledServerCount()
                    + " 熔断中: " + mcpManager.getCircuitOpenServerCount()
                    + " 工具缓存: " + mcpManager.getCachedToolCount();
        }
        sender.sendMessage(ChatColor.GRAY + "mcp_call: " + formatStatus(mcpEnabled)
                + ChatColor.DARK_GRAY + " (" + mcpDetail + ")");

        renderMcpTree(sender);
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
        player.sendMessage(Component.text("└─ MCP Servers", NamedTextColor.DARK_GRAY));
        for (int i = 0; i < servers.size(); i++) {
            McpManager.McpServerSnapshot server = servers.get(i);
            boolean lastServer = i == servers.size() - 1;
            String serverPrefix = lastServer ? "   └─ " : "   ├─ ";
            Component serverLine = Component.text(serverPrefix, NamedTextColor.DARK_GRAY)
                    .append(Component.text(server.getServerName() + " ", NamedTextColor.AQUA))
                    .append(Component.text("[" + getServerStatusText(server) + "] ", getServerStatusColor(server)))
                    .append(Component.text("(tools: " + server.getTools().size() + ")", NamedTextColor.GRAY))
                    .hoverEvent(buildServerHover(server));
            player.sendMessage(serverLine);

            List<McpManager.McpToolSnapshot> tools = server.getTools();
            for (int j = 0; j < tools.size(); j++) {
                McpManager.McpToolSnapshot tool = tools.get(j);
                boolean lastTool = j == tools.size() - 1;
                String toolPrefix = (lastServer ? "      " : "   │  ") + (lastTool ? "└─ " : "├─ ");
                Component toolLine = Component.text(toolPrefix, NamedTextColor.DARK_GRAY)
                        .append(Component.text(tool.getName(), NamedTextColor.GREEN))
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
        StringBuilder sb = new StringBuilder();
        sb.append("服务器: ").append(server.getServerName())
                .append("\n状态: ").append(getServerStatusText(server))
                .append("\n工具数量: ").append(server.getTools().size());
        if (server.getCircuitRemainingMillis() > 0) {
            sb.append("\n熔断剩余: ").append((server.getCircuitRemainingMillis() + 999L) / 1000L).append(" 秒");
        }
        if (server.getLastError() != null && !server.getLastError().isBlank()) {
            sb.append("\n最近错误: ").append(server.getLastError());
        }
        return Component.text(sb.toString(), NamedTextColor.GRAY);
    }

    private Component buildToolHover(McpManager.McpServerSnapshot server, McpManager.McpToolSnapshot tool) {
        StringBuilder sb = new StringBuilder();
        sb.append("server: ").append(server.getServerName())
                .append("\ntool: ").append(tool.getName());
        if (tool.getDescription() != null && !tool.getDescription().isBlank()) {
            sb.append("\n描述: ").append(tool.getDescription());
        } else {
            sb.append("\n描述: (无)");
        }
        if (tool.getInputSchema() != null && !tool.getInputSchema().isBlank()) {
            sb.append("\ninputSchema:\n").append(tool.getInputSchema());
        }
        return Component.text(sb.toString(), NamedTextColor.GRAY);
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
