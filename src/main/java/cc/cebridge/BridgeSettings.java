package cc.cebridge;

import org.bukkit.configuration.file.FileConfiguration;

public record BridgeSettings(
        boolean carriers,
        float carrierScale,
        boolean autoName,
        boolean leavesXRecipeSync,
        int scanBudgetPerTick) {

    public static BridgeSettings load(FileConfiguration config) {
        return new BridgeSettings(
                config.getBoolean("settings.carriers", true),
                (float) config.getDouble("settings.carrier-scale", 0.001D),
                config.getBoolean("settings.auto-name", true),
                config.getBoolean("settings.leavesx-recipe-sync", true),
                Math.max(4096, config.getInt("settings.scan-budget-per-tick", 65536)));
    }
}
