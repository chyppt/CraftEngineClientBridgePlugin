package cc.cebridge.recipe;

import cc.cebridge.CraftEngineBridgePlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.util.function.Supplier;

/** Optional LeavesX SPI binding; no LeavesX class is linked at compile time. */
public final class LeavesXRecipeSpi {
    private static final String API = "org.leavesmc.leaves.api.recipeviewer.RecipeViewerBridge";
    private final CraftEngineBridgePlugin plugin;
    private Object bridge;
    private Class<?> api;
    private boolean registered;
    private volatile RecipeSnapshotBuilder.Result lastSnapshot;
    private volatile String lastError = "尚未生成快照";
    public LeavesXRecipeSpi(CraftEngineBridgePlugin plugin) { this.plugin = plugin; }
    public boolean discoverAndRegister() {
        try {
            api = Class.forName(API, false, plugin.getServer().getClass().getClassLoader());
            Object registration = Bukkit.getServicesManager().getRegistration(api);
            if (registration == null) return false;
            bridge = registration.getClass().getMethod("getProvider").invoke(registration);
            if ((int) api.getMethod("apiVersion").invoke(bridge) != 1) return false;
            Supplier<String> callback = () -> {
                RecipeSnapshotBuilder.Result result = RecipeSnapshotBuilder.buildForLeavesX();
                lastSnapshot = result;
                plugin.getLogger().info("CE 配方导出：items=" + result.items() + ", recipes=" + result.recipes()
                        + ", skipped=" + result.skipped() + ", failed=" + result.failed() + ", types=" + result.types() + ", snapshotChars=" + result.json().length());
                if (result.recipes() == 0 || result.failed() > 0 || result.skipped() > 0) {
                    plugin.getLogger().warning("CE 配方导出不完整：原因统计=" + result.problemCounts() + "；样本（最多12条）=" + result.problems());
                }
                return result.json();
            };
            api.getMethod("registerProvider", Plugin.class, String.class, Supplier.class)
                .invoke(bridge, plugin, "craftengine:content", callback);
            registered = true;
            lastError = "已注册，等待快照刷新";
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            lastError = error(ex);
            plugin.getLogger().fine("LeavesX recipe SPI unavailable: " + lastError);
            return false;
        }
    }
    public boolean refresh() {
        if (!registered) return false;
        try {
            api.getMethod("refresh", Plugin.class, String.class).invoke(bridge, plugin, "craftengine:content");
            lastError = "刷新调用已返回（不是客户端显示确认）";
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            lastError = error(ex);
            plugin.getLogger().warning("LeavesX recipe SPI refresh failed: " + lastError);
            return false;
        }
    }
    public void unregister() {
        if (!registered) return;
        try { api.getMethod("unregisterProvider", Plugin.class, String.class).invoke(bridge, plugin, "craftengine:content"); }
        catch (ReflectiveOperationException | RuntimeException ignored) {}
        registered = false;
    }
    private static String error(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.toString();
    }
    public RecipeSnapshotBuilder.Result lastSnapshot() { return lastSnapshot; }
    public String lastError() { return lastError; }
    public boolean registered() { return registered; }
}

