package cc.cebridge;

import cc.cebridge.carrier.CarrierManager;
import cc.cebridge.command.BridgeCommand;
import cc.cebridge.listener.CEBlockListener;
import cc.cebridge.listener.WorldListener;
import cc.cebridge.patch.DroppedItemPatcher;
import cc.cebridge.recipe.LeavesXRecipeBridge;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * JadeCraftEngineBridge
 *
 * CraftEngine 的自定义方块在客户端只表现为原版视觉方块（绊线、音符盒等），
 * Jade 只能读到客户端注册表里的原版名。Jade 26.x 内置了"数据包方块"识别：
 * 瞄准方块时会查找与该方块重叠的 item_display/block_display 实体，并优先
 * 使用其物品的组件（item_name 名字、item_model 贴图）作为名字与图标。
 *
 * 本插件只为玩家正在观察的 CE 自定义方块维护隐形 item_display，最后一名观察者离开后回收。
 */
public final class CraftEngineBridgePlugin extends JavaPlugin {

    private volatile BridgeSettings settings;
    private CERegistry registry;
    private CarrierManager carriers;
    private LeavesXRecipeBridge recipeBridge;
    private cc.cebridge.client.OptionalClientBridge clientBridge;

    @Override
    public void onDisable() {
        if (clientBridge != null) clientBridge.close();
        if (recipeBridge != null) recipeBridge.close();
        if (carriers != null) carriers.close();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = BridgeSettings.load(getConfig());
        this.registry = new CERegistry(this);
        this.carriers = new CarrierManager(this);
        this.recipeBridge = new LeavesXRecipeBridge(this);

        this.registry.rebuild();
        this.clientBridge = new cc.cebridge.client.OptionalClientBridge(this);

        getServer().getPluginManager().registerEvents(new CEBlockListener(this), this);
        getServer().getPluginManager().registerEvents(new WorldListener(this), this);
        getServer().getPluginManager().registerEvents(recipeBridge, this);
        if (settings.patchDroppedItems()) {
            getServer().getPluginManager().registerEvents(new DroppedItemPatcher(this), this);
        }

        PluginCommand command = getCommand("cebridge");
        if (command != null) {
            BridgeCommand executor = new BridgeCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        recipeBridge.requestRefresh(false);
        // CE 首次加载可能早于本插件监听器注册；补两次有界重试，覆盖启动时序而不扫描世界。
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> recipeBridge.retryIfIncomplete(), 40L);
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> recipeBridge.retryIfIncomplete(), 100L);
        // Region-owned demand checks; automatic maintenance never scans unobserved chunks.
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> carriers.refreshViewedBlocks(), 10L, 10L);
        getLogger().info("Carriers: " + settings.carriers() + ", Jade marker: enabled, LeavesX recipe reload: " + settings.leavesXRecipeSync()
                + ", auto-name: " + settings.autoName());
    }

    public void reloadAll(Runnable onComplete) {
        reloadConfig();
        this.settings = BridgeSettings.load(getConfig());
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> registry.rebuild(() -> {
            carriers.invalidateViewedBlocks();
            recipeBridge.requestRefresh(false);
            clientBridge.requestRefresh();
            onComplete.run();
        }), 1L);
    }

    public cc.cebridge.client.OptionalClientBridge clientBridge() { return clientBridge; }

    public BridgeSettings settings() {
        return settings;
    }

    public CERegistry registry() {
        return registry;
    }

    public LeavesXRecipeBridge recipeBridge() {
        return recipeBridge;
    }

    public CarrierManager carriers() {
        return carriers;
    }
}
