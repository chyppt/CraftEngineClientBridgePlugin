package cc.cebridge.listener;

import cc.cebridge.CraftEngineBridgePlugin;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** CE block lifecycle hooks with final-state validation on the owning region. */
public final class CEBlockListener implements Listener {
    private final CraftEngineBridgePlugin plugin;

    public CEBlockListener(CraftEngineBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCustomBlockPlace(CustomBlockPlaceEvent event) {
        Block block = event.bukkitBlock();
        plugin.getServer().getRegionScheduler().runDelayed(plugin, block.getWorld(), block.getX() >> 4, block.getZ() >> 4,
                task -> plugin.carriers().reconcileBlock(block), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        Block block = event.bukkitBlock();
        plugin.getServer().getRegionScheduler().runDelayed(plugin, block.getWorld(), block.getX() >> 4, block.getZ() >> 4,
                task -> plugin.carriers().reconcileBlock(block), 1L);
    }

}
