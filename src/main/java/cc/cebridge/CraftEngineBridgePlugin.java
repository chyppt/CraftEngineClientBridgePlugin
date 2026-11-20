package cc.cebridge;

import cc.cebridge.command.BridgeCommand;
import cc.cebridge.recipe.LeavesXRecipeBridge;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** 服务端内容索引与可选客户端协议入口，不创建识别实体。 */
public final class CraftEngineBridgePlugin extends JavaPlugin {

    private volatile BridgeSettings settings;
    private CERegistry registry;
    private LeavesXRecipeBridge recipeBridge;
    private cc.cebridge.client.OptionalClientBridge clientBridge;

    @Override
    public void onDisable() {
        if (clientBridge != null) clientBridge.close();
        if (recipeBridge != null) recipeBridge.close();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = BridgeSettings.load(getConfig());
        this.registry = new CERegistry(this);
        this.recipeBridge = new LeavesXRecipeBridge(this);

        this.registry.rebuild();
        this.clientBridge = new cc.cebridge.client.OptionalClientBridge(this);

        getServer().getPluginManager().registerEvents(recipeBridge, this);

        PluginCommand command = getCommand("cebridge");
        if (command != null) {
            BridgeCommand executor = new BridgeCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        recipeBridge.requestRefresh(false);
        // CE 首次加载可能早于本插件监听器注册；补两次有界重试，覆盖启动时序而不扫描世界。
        cc.cebridge.compat.Schedulers.global().runDelayed(this, task -> recipeBridge.retryIfIncomplete(), 40L);
        cc.cebridge.compat.Schedulers.global().runDelayed(this, task -> recipeBridge.retryIfIncomplete(), 100L);
        getLogger().info("客户端内容桥接已启用；LeavesX 配方联动: " + settings.leavesXRecipeSync());
    }

    public void reloadAll(Runnable onComplete) {
        reloadConfig();
        this.settings = BridgeSettings.load(getConfig());
        cc.cebridge.compat.Schedulers.global().runDelayed(this, task -> registry.rebuild(() -> {
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

}
