package cc.cebridge;

import org.bukkit.configuration.file.FileConfiguration;

public record BridgeSettings(boolean autoName, boolean leavesXRecipeSync) {
    public static BridgeSettings load(FileConfiguration config) {
        return new BridgeSettings(config.getBoolean("settings.auto-name", true),
                config.getBoolean("settings.leavesx-recipe-sync", true));
    }
}
