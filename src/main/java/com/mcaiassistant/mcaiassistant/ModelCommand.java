package com.mcaiassistant.mcaiassistant;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型管理指令
 * - /ai model           显示当前有效模型（覆盖或配置）
 * - /ai model <model>  切换运行时聊天模型（不写回配置）
 * - /model             兼容旧指令
 * 提供 Tab 补全：从 ModelManager 的 /models 缓存获取候选
 */
public class ModelCommand implements CommandExecutor, TabCompleter {

    private final McAiAssistant plugin;
    private final ConfigManager configManager;
    private final ModelManager modelManager;

    public ModelCommand(McAiAssistant plugin, ConfigManager configManager, ModelManager modelManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.modelManager = modelManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 权限：默认使用 mcaiassistant.admin
        if (!sender.hasPermission("mcaiassistant.admin")) {
            sender.sendMessage(ChatColor.RED + "没有权限执行该命令。");
            return true;
        }

        if (args.length == 0) {
            // 显示当前有效模型与来源
            String effective = modelManager.getEffectiveChatModel();
            boolean overridden = modelManager.getOverrideChatModelId() != null;
            String source = overridden ? (ChatColor.YELLOW + "覆盖") : (ChatColor.GRAY + "配置");
            String defaultModel = configManager.getModel();

            sender.sendMessage(ChatColor.AQUA + "[AI] 当前模型: "
                    + ChatColor.WHITE + effective
                    + ChatColor.DARK_GRAY + "  (来源: " + source + ChatColor.DARK_GRAY + ")");
            if (overridden) {
                sender.sendMessage(ChatColor.DARK_GRAY + "配置默认: " + ChatColor.GRAY + defaultModel);
            } else {
                sender.sendMessage(ChatColor.DARK_GRAY + "提示: 使用 /" + label + " <modelId> 可临时切换模型");
            }

            // 触发一次异步刷新，确保补全较新
            modelManager.refreshModelsAsync();
            return true;
        }

        // 设置覆盖模型
        String modelId = args[0];

        // 先获取一次快照并触发必要刷新（不阻塞）
        List<String> snapshot = modelManager.getModelsSnapshotAndRefreshIfNeeded();
        boolean inCache = modelManager.cachedListContainsIgnoreCase(modelId);

        modelManager.setOverrideChatModelId(modelId);

        if (inCache) {
            sender.sendMessage(ChatColor.GREEN + "已切换模型为: " + ChatColor.WHITE + modelId
                    + ChatColor.DARK_GRAY + "  (将用于后续对话请求)");
        } else {
            // 未在缓存列表中发现，也允许设置，但给出提示
            sender.sendMessage(ChatColor.GREEN + "已切换模型为: " + ChatColor.WHITE + modelId);
            sender.sendMessage(ChatColor.YELLOW + "注意: 未在 /models 列表中发现该模型，"
                    + "可能因为端点未完整返回或网络暂时不可用。已尝试刷新列表。");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mcaiassistant.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase();
            List<String> models = modelManager.getModelsSnapshotAndRefreshIfNeeded();
            if (models.isEmpty()) return Collections.emptyList();

            List<String> result = new ArrayList<>();
            int count = 0;
            for (String id : models) {
                if (prefix.isEmpty() || id.toLowerCase().startsWith(prefix)) {
                    result.add(id);
                    count++;
                    if (count >= 20) break; // 限制返回数量
                }
            }
            return result;
        }

        return Collections.emptyList();
    }
}
