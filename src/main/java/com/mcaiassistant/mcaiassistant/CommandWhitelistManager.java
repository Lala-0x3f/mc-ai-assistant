package com.mcaiassistant.mcaiassistant;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 后台指令白名单管理器
 */
public class CommandWhitelistManager {

    private static final String FILE_NAME = "command-whitelist.yml";
    private final McAiAssistant plugin;
    private File configFile;
    private FileConfiguration config;

    public CommandWhitelistManager(McAiAssistant plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        configFile = new File(dataFolder, FILE_NAME);
        if (!configFile.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        reload();
    }

    public void reload() {
        if (configFile == null) {
            return;
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    public boolean isEnabled() {
        return config != null && config.getBoolean("enabled", true);
    }

    public List<String> getWhitelist() {
        if (config == null) {
            return Collections.emptyList();
        }
        List<String> list = config.getStringList("whitelist");
        if (list == null) {
            return Collections.emptyList();
        }
        List<String> normalized = new ArrayList<>();
        for (String cmd : list) {
            if (cmd == null) {
                continue;
            }
            String trimmed = cmd.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    public boolean addCommand(String command) {
        if (config == null) {
            return false;
        }
        String normalized = normalizeCommand(command);
        if (normalized.isEmpty()) {
            return false;
        }
        List<String> whitelist = new ArrayList<>(getWhitelist());
        for (String item : whitelist) {
            if (item.equalsIgnoreCase(normalized)) {
                return false;
            }
        }
        whitelist.add(normalized);
        config.set("whitelist", whitelist);
        return save();
    }

    public boolean removeCommand(String command) {
        if (config == null) {
            return false;
        }
        String normalized = normalizeCommand(command);
        if (normalized.isEmpty()) {
            return false;
        }
        List<String> whitelist = new ArrayList<>(getWhitelist());
        boolean removed = whitelist.removeIf(item -> item.equalsIgnoreCase(normalized));
        if (!removed) {
            return false;
        }
        config.set("whitelist", whitelist);
        return save();
    }

    public boolean isCommandAllowed(String command) {
        if (!isEnabled()) {
            return false;
        }
        String normalized = normalizeCommand(command);
        if (normalized.isEmpty()) {
            return false;
        }
        String label = normalized.split("\\s+")[0].toLowerCase();
        for (String allowed : getWhitelist()) {
            String allowedLabel = normalizeCommand(allowed).split("\\s+")[0].toLowerCase();
            if (label.equals(allowedLabel)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.replaceAll("\\s+", " ");
    }

    private boolean save() {
        try {
            config.save(configFile);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("保存 command-whitelist.yml 失败: " + e.getMessage());
            return false;
        }
    }
}
