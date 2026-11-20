package cc.cebridge.listener;

import cc.cebridge.CraftEngineBridgePlugin;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayList;
import java.util.List;

/** World-side reconciliation, always dispatched to the owning Folia regions. */
public final class WorldListener implements Listener {
    private final CraftEngineBridgePlugin plugin;

    public WorldListener(CraftEngineBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!from.getWorld().equals(to.getWorld()) || from.getBlockX() >> 4 != to.getBlockX() >> 4
                || from.getBlockZ() >> 4 != to.getBlockZ() >> 4) {
            plugin.carriers().releaseViewer(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        plugin.carriers().releaseViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.carriers().releaseViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkHidden(PlayerChunkUnloadEvent event) {
        plugin.carriers().releaseViewerChunk(event.getPlayer().getUniqueId(), event.getWorld().getUID(),
                event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        plugin.carriers().onEntitiesLoaded(event.getEntities());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveFromWorldEvent event) {
        plugin.carriers().onCarrierRemoved(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        schedulePostExplosionCheck(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        schedulePostExplosionCheck(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        schedulePistonFix(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        schedulePistonFix(event.getBlocks(), event.getDirection());
    }

    private void schedulePostExplosionCheck(List<Block> blocks) {
        scheduleLocations(blocks.stream().map(Block::getLocation).toList());
    }

    private void scheduleLocations(List<Location> locations) {
        for (Location location : locations) {
            if (location.getWorld() == null) continue;
            scheduleBlockReconcile(location);
        }
    }

    private void scheduleBlockReconcile(Location location) {
        var world = location.getWorld();
        if (world == null) return;
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        plugin.getServer().getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, task -> {
            if (!world.isChunkLoaded(chunkX, chunkZ)) return;
            plugin.carriers().reconcileBlock(location.getBlock());
        }, 1L);
    }

    private void schedulePistonFix(List<Block> blocks, BlockFace direction) {
        if (blocks.isEmpty()) return;
        List<Location> locations = new ArrayList<>(blocks.size() * 2);
        for (Block block : blocks) {
            locations.add(block.getLocation());
            locations.add(block.getRelative(direction).getLocation());
        }
        scheduleLocations(locations);
    }
}
