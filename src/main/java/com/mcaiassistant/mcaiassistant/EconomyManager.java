package com.mcaiassistant.mcaiassistant;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 经济扣费管理器
 * 负责对接 Vault/CMI 等经济插件，并在玩家调用 AI 时扣费
 */
public class EconomyManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private Economy economy;
    private boolean active = false;

    public EconomyManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * 初始化经济系统对接
     */
    public void initialize() {
        economy = null;
        active = false;

        if (!configManager.isEconomyEnabled()) {
            return;
        }

        double cost = configManager.getEconomyCostPerUse();
        if (cost <= 0) {
            plugin.getLogger().warning("经济扣费已启用，但 cost_per_use <= 0，已自动关闭经济功能。");
            return;
        }

        RegisteredServiceProvider<Economy> provider = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            plugin.getLogger().warning("已启用经济扣费，但未检测到 Vault/CMI 等兼容经济插件，已关闭经济功能。");
            return;
        }

        economy = provider.getProvider();
        if (economy != null) {
            plugin.getLogger().info("已接入经济系统: " + economy.getName() + "，AI 调用将按照配置扣费。");
            active = true;
        } else {
            plugin.getLogger().warning("经济插件注册失败，AI 扣费功能无法使用，已关闭经济功能。");
        }
    }

    /**
     * 重载配置后重新尝试对接经济系统
     */
    public void reload() {
        initialize();
    }

    /**
     * 当前经济功能是否可用
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 尝试对玩家进行扣费
     */
    public EconomyChargeResult chargePlayer(Player player) {
        if (!configManager.isEconomyEnabled() || !active) {
            return EconomyChargeResult.skipped("未启用经济扣费");
        }

        try {
            if (Bukkit.isPrimaryThread()) {
                return doCharge(player);
            }

            return Bukkit.getScheduler()
                .callSyncMethod(plugin, () -> doCharge(player))
                .get(3, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            plugin.getLogger().warning("经济扣费调用失败: " + e.getMessage());
            return EconomyChargeResult.failure("经济系统调用失败: " + e.getMessage());
        }
    }

    /**
     * 在主线程执行扣费逻辑
     */
    private EconomyChargeResult doCharge(Player player) {
        double cost = Math.max(0, configManager.getEconomyCostPerUse());
        if (cost <= 0) {
            return EconomyChargeResult.skipped("扣费金额未设置或小于等于 0");
        }

        double before = economy.getBalance(player);
        if (!economy.has(player, cost)) {
            return EconomyChargeResult.insufficient(cost, before, before, false);
        }

        EconomyResponse response = economy.withdrawPlayer(player, cost);
        if (!response.transactionSuccess()) {
            return EconomyChargeResult.failure(response.errorMessage);
        }

        double after = economy.getBalance(player);
        return EconomyChargeResult.success(cost, before, after, false);
    }

    /**
     * 预检余额，避免余额不足仍然触发 AI 生成
     */
    public EconomyChargeResult preCheck(Player player) {
        if (!configManager.isEconomyEnabled() || !active) {
            return EconomyChargeResult.skipped("未启用经济扣费");
        }

        double cost = Math.max(0, configManager.getEconomyCostPerUse());
        double before = economy.getBalance(player);
        if (cost <= 0) {
            return EconomyChargeResult.skipped("扣费金额未设置或小于等于 0");
        }
        if (!economy.has(player, cost)) {
            return EconomyChargeResult.insufficient(cost, before, before, true);
        }
        return EconomyChargeResult.success(cost, before, before, true);
    }

    /**
     * 退还扣款
     */
    public void refund(Player player, double amount) {
        if (!active || economy == null || amount <= 0) {
            return;
        }
        try {
            if (Bukkit.isPrimaryThread()) {
                economy.depositPlayer(player, amount);
                return;
            }
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                economy.depositPlayer(player, amount);
                return null;
            }).get(3, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            plugin.getLogger().warning("退款失败: " + e.getMessage());
        }
    }

    /**
     * 扣费结果
     */
    public static class EconomyChargeResult {
        private final boolean success;
        private final boolean skipped;
        private final boolean insufficientFunds;
        private final double cost;
        private final double oldBalance;
        private final double newBalance;
        private final boolean preCheckOnly;
        private final String errorMessage;

        private EconomyChargeResult(boolean success, boolean skipped, boolean insufficientFunds, double cost, double oldBalance, double newBalance, boolean preCheckOnly, String errorMessage) {
            this.success = success;
            this.skipped = skipped;
            this.insufficientFunds = insufficientFunds;
            this.cost = cost;
            this.oldBalance = oldBalance;
            this.newBalance = newBalance;
            this.preCheckOnly = preCheckOnly;
            this.errorMessage = errorMessage;
        }

        public static EconomyChargeResult success(double cost, double oldBalance, double newBalance, boolean preCheckOnly) {
            return new EconomyChargeResult(true, false, false, cost, oldBalance, newBalance, preCheckOnly, null);
        }

        public static EconomyChargeResult insufficient(double cost, double oldBalance, double newBalance, boolean preCheckOnly) {
            return new EconomyChargeResult(false, false, true, cost, oldBalance, newBalance, preCheckOnly, "余额不足");
        }

        public static EconomyChargeResult failure(String message) {
            return new EconomyChargeResult(false, false, false, 0, 0, 0, false, message);
        }

        public static EconomyChargeResult skipped(String reason) {
            return new EconomyChargeResult(true, true, false, 0, 0, 0, false, reason);
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isSkipped() {
            return skipped;
        }

        public boolean isInsufficientFunds() {
            return insufficientFunds;
        }

        public double getCost() {
            return cost;
        }

        public double getOldBalance() {
            return oldBalance;
        }

        public double getNewBalance() {
            return newBalance;
        }

        public boolean isPreCheckOnly() {
            return preCheckOnly;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
