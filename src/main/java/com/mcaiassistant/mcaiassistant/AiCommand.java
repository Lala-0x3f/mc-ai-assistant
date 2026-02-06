package com.mcaiassistant.mcaiassistant;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

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
}
