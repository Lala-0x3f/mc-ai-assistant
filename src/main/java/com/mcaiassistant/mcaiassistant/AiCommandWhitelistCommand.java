package com.mcaiassistant.mcaiassistant;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AI 指令白名单管理指令
 */
public class AiCommandWhitelistCommand implements CommandExecutor, TabCompleter {

    private final McAiAssistant plugin;
    private final CommandWhitelistManager whitelistManager;

    public AiCommandWhitelistCommand(McAiAssistant plugin, CommandWhitelistManager whitelistManager) {
        this.plugin = plugin;
        this.whitelistManager = whitelistManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "仅 OP 可以管理 AI 指令白名单。");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String action = args[0].toLowerCase();
        switch (action) {
            case "list":
                List<String> list = whitelistManager.getWhitelist();
                sender.sendMessage(ChatColor.AQUA + "AI 指令白名单 (" + list.size() + "):");
                for (String item : list) {
                    sender.sendMessage(ChatColor.GRAY + "- " + item);
                }
                return true;
            case "add":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "用法: /aicmdwl add <command>");
                    return true;
                }
                String toAdd = args[1];
                if (whitelistManager.addCommand(toAdd)) {
                    sender.sendMessage(ChatColor.GREEN + "已添加白名单指令: " + toAdd);
                } else {
                    sender.sendMessage(ChatColor.RED + "添加失败，可能已存在或参数无效。");
                }
                return true;
            case "remove":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "用法: /aicmdwl remove <command>");
                    return true;
                }
                String toRemove = args[1];
                if (whitelistManager.removeCommand(toRemove)) {
                    sender.sendMessage(ChatColor.GREEN + "已移除白名单指令: " + toRemove);
                } else {
                    sender.sendMessage(ChatColor.RED + "移除失败，指令不存在。");
                }
                return true;
            case "reload":
                whitelistManager.reload();
                sender.sendMessage(ChatColor.GREEN + "已重新加载 command-whitelist.yml");
                return true;
            default:
                sendHelp(sender);
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("list", "add", "remove", "reload");
        }
        return new ArrayList<>();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "AI 指令白名单管理:");
        sender.sendMessage(ChatColor.GRAY + "/aicmdwl list - 查看白名单");
        sender.sendMessage(ChatColor.GRAY + "/aicmdwl add <command> - 添加指令");
        sender.sendMessage(ChatColor.GRAY + "/aicmdwl remove <command> - 删除指令");
        sender.sendMessage(ChatColor.GRAY + "/aicmdwl reload - 重新加载配置");
    }
}
