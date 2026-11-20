package cc.cebridge.recipe;

import cc.cebridge.CraftEngineBridgePlugin;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 把 CE 的重载生命周期连接到核心已有协议；保持核心的配置和物品验证规则。 */
public final class LeavesXRecipeBridge implements Listener {
    private final CraftEngineBridgePlugin plugin;
    private final LeavesXRecipeHooks hooks;
    private final LeavesXRecipeSpi spi;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final AtomicBoolean rebuildRegistry = new AtomicBoolean();

    public LeavesXRecipeBridge(CraftEngineBridgePlugin plugin) {
        this.plugin = plugin;
        this.hooks = LeavesXRecipeHooks.discover(plugin.getServer().getClass().getClassLoader(),
                plugin.getServer().getMinecraftVersion());
        this.spi = new LeavesXRecipeSpi(plugin);
        this.spi.discoverAndRegister();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        requestRefresh(true);
    }

    public List<LeavesXRecipeHooks.Status> status() {
        return hooks.inspect();
    }

    /** 合并同一刻的重载请求；不维护玩家轮询任务，最终同步由核心负责。 */
    public void requestRefresh(boolean rebuildItems) {
        if (!plugin.isEnabled()) return;
        if (rebuildItems) rebuildRegistry.set(true);
        if (!queued.compareAndSet(false, true)) return;
        cc.cebridge.compat.Schedulers.global().runDelayed(plugin, task -> {
            queued.set(false);
            if (rebuildRegistry.getAndSet(false)) {
                plugin.registry().rebuild();
            }
            if (!plugin.settings().leavesXRecipeSync()) { spi.unregister(); return; }
            if (spi.registered() || spi.discoverAndRegister()) {
                if (!spi.refresh()) plugin.getLogger().warning("LeavesX SPI 快照刷新失败，请检查核心日志。");
                return;
            }
            for (var result : hooks.invalidate()) {
                if (result.state() == LeavesXRecipeHooks.State.FAILED) {
                    plugin.getLogger().warning(result.protocol() + ": " + result.detail());
                }
            }
        }, 1L);
    }

    public void retryIfIncomplete() {
        var snapshot = spi.lastSnapshot();
        if (plugin.registry().blockCount() == 0 || !spi.registered() || snapshot == null || snapshot.recipes() == 0) {
            requestRefresh(true);
        }
    }

    public RecipeSnapshotBuilder.Result lastSnapshot() { return spi.lastSnapshot(); }
    public String lastError() { return spi.lastError(); }

    public boolean spiRegistered() { return spi.registered(); }

    public void close() { spi.unregister(); }
}
