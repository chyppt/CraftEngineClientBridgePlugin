package cc.cebridge.carrier;

import cc.cebridge.CraftEngineBridgePlugin;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Maintains Jade carriers and performs world/entity work on the owning Folia region. */
public final class CarrierManager {
    public static final String TAG = "cebridge_carrier";
    public static final String LEGACY_TAG = "jadebridge_carrier";

    private final CraftEngineBridgePlugin plugin;
    private final NamespacedKey blockIdKey;
    private final NamespacedKey legacyBlockIdKey = NamespacedKey.fromString("jadecraftenginebridge:block-id");
    private final Map<UUID, Object> playersUnderMaintenance = new ConcurrentHashMap<>();
    // token 防止传送/退出后的旧区域回调重新创建载体，或清掉新一轮任务。
    private final Map<UUID, Object> pendingLookChecks = new ConcurrentHashMap<>();
    private final ViewerTargets<UUID, WorldBlock> viewers = new ViewerTargets<>();
    private final Map<WorldBlock, ItemDisplay> activeCarriers = new ConcurrentHashMap<>();
    private final Set<WorldBlock> pendingRemovals = ConcurrentHashMap.newKeySet();
    private final RecentBlockChecks<WorldBlock, ImmutableBlockState> recentChecks =
            new RecentBlockChecks<>(4096, 5_000_000_000L);

    public CarrierManager(CraftEngineBridgePlugin plugin) {
        this.plugin = plugin;
        this.blockIdKey = new NamespacedKey(plugin, "block-id");

    }

    /** Must be called from the region owning {@code block}. */
    public void spawn(Block block, Key blockId) {
        if (!plugin.settings().carriers()) return;
        ItemDisplay existing = carrierAt(block.getLocation());
        if (existing == null) spawnNewCarrier(block, blockId);
        else updateCarrier(existing, blockId);
    }

    private void spawnNewCarrier(Block block, Key blockId) {
        spawnNewCarrier(block, blockId, CraftEngineBlocks.getCustomBlockState(block));
    }

    private void spawnNewCarrier(Block block, Key blockId, @Nullable ImmutableBlockState state) {
        ItemStack builtItem = state == null ? plugin.registry().buildCarrierItem(blockId)
                : plugin.registry().buildCarrierItem(state);
        // 有状态的作物若尚未解析成熟产物，不显示错误的种子载体；下一轮观察检查重试。
        if (builtItem == null) return;
        final ItemStack item = builtItem;
        Location center = block.getLocation().add(.5, .5, .5);
        float scale = plugin.settings().carrierScale();
        ItemDisplay carrier = block.getWorld().spawn(center, ItemDisplay.class, display -> {
            display.setItemStack(item);
            display.setInvisible(true);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            display.setPersistent(false);
            display.getPersistentDataContainer().set(blockIdKey, PersistentDataType.STRING, blockId.asString());
            display.addScoreboardTag(TAG);
        });
        WorldBlock key = key(block.getLocation());
        activeCarriers.put(key, carrier);
        // 观察者可能在实体创建过程中离开；再次核实，避免留下无人使用的实体。
        if (!viewers.isViewed(key)) scheduleRemoval(key);
    }

    /** 原位更新物品，保留实体 ID，避免客户端重新跟踪和短暂丢失 HUD。 */
    private void updateCarrier(ItemDisplay display, Key blockId) {
        updateCarrier(display, blockId, CraftEngineBlocks.getCustomBlockState(display.getLocation().getBlock()));
    }

    private void updateCarrier(ItemDisplay display, Key blockId, @Nullable ImmutableBlockState state) {
        ItemStack item = state == null ? plugin.registry().buildCarrierItem(blockId)
                : plugin.registry().buildCarrierItem(state);
        if (item == null) {
            // 解析失败时移除旧载体，避免保留上一阶段或种子图标。
            WorldBlock missingKey = key(display.getLocation());
            activeCarriers.remove(missingKey, display);
            if (display.isValid()) display.remove();
            return;
        }
        if (!item.equals(display.getItemStack())) display.setItemStack(item);
        migrateLegacy(display);
        migrateLegacyData(display);
        display.setPersistent(false);
        WorldBlock key = key(display.getLocation());
        activeCarriers.put(key, display);
        if (!viewers.isViewed(key)) scheduleRemoval(key);
        if (!blockId.asString().equals(display.getPersistentDataContainer().get(blockIdKey, PersistentDataType.STRING))) {
            display.getPersistentDataContainer().set(blockIdKey, PersistentDataType.STRING, blockId.asString());
        }
    }

    /** Must be called from the region owning {@code blockLocation}; never loads a chunk. */
    public void removeAt(Location blockLocation) {
        WorldBlock key = key(blockLocation);
        recentChecks.remove(key);
        ItemDisplay known = activeCarriers.remove(key);
        if (known != null && known.isValid()) known.remove();
        if (loadedChunkAt(blockLocation) == null) return;
        // 空间索引仅查询这一个方块，不再遍历整区块的全部实体。
        for (Entity entity : blockLocation.getWorld().getNearbyEntities(
                blockLocation.clone().add(.5, .5, .5), .49, .49, .49, CarrierManager::isCarrier)) {
            if (sameBlock(entity.getLocation(), blockLocation)) entity.remove();
        }
    }

    public boolean hasCarrierAt(Location blockLocation) {
        return carrierAt(blockLocation) != null;
    }

    /**
     * Reconciles one final block state. The caller must be on the block's owning region.
     * This is used after lifecycle events so replacements cannot retain an old carrier ID.
     */
    public void reconcileBlock(Block block) {
        Location location = block.getLocation();
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return;
        if (!plugin.settings().carriers() || !viewers.isViewed(key(location))) {
            removeAt(location);
            return;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null) {
            removeAt(location);
            return;
        }
        reconcileState(block, state);
    }
    /** Reconciles loaded chunk carriers; caller must be executing in that chunk's region. */
    public int reconcile(Chunk chunk) {
        int removed = 0;
        Set<BlockPosition> seen = new HashSet<>();
        for (Entity entity : chunk.getEntities()) {
            if (!isCarrier(entity)) continue;
            migrateLegacy(entity);
            migrateLegacyData(entity);
            BlockPosition position = BlockPosition.of(entity.getLocation());
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(entity.getLocation().getBlock());
            if (state == null || !plugin.settings().carriers() || !seen.add(position)) {
                entity.remove();
                removed++;
            } else {
                updateCarrier((ItemDisplay) entity, state.owner().value().id());
            }
        }
        return removed;
    }

    /**
     * Automatic maintenance is demand-driven: one ray of at most eight blocks per nearby viewer.
     * No loaded-chunk enumeration, whole-height scans, chunk loads or shared async world reads.
     */
    public void refreshViewedBlocks() {
        if (!plugin.settings().carriers()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            Object token = new Object();
            if (pendingLookChecks.putIfAbsent(playerId, token) != null) continue;
            var scheduled = player.getScheduler().run(plugin, task -> {
                boolean dispatched = false;
                try {
                    if (pendingLookChecks.get(playerId) != token) return;
                    if (plugin.clientBridge().hasNativeBlocks(playerId)) { moveViewer(playerId, null); return; }
                    Location eye = player.getEyeLocation();
                    Location end = eye.clone().add(eye.getDirection().multiply(8));
                    World world = eye.getWorld();
                    for (int x = Math.min(eye.getBlockX(), end.getBlockX()) >> 4;
                         x <= (Math.max(eye.getBlockX(), end.getBlockX()) >> 4); x++) {
                        for (int z = Math.min(eye.getBlockZ(), end.getBlockZ()) >> 4;
                             z <= (Math.max(eye.getBlockZ(), end.getBlockZ()) >> 4); z++) {
                            if (!world.isChunkLoaded(x, z) || !Bukkit.isOwnedByCurrentRegion(world, x, z)) {
                                moveViewer(playerId, null);
                                return;
                            }
                        }
                    }
                    Block target = player.getTargetBlockExact(8);
                    if (target == null) { moveViewer(playerId, null); return; }
                    Location location = target.getLocation();
                    WorldBlock key = key(location);
                    moveViewer(playerId, key);
                    Runnable reconcile = () -> {
                        try {
                            if (pendingLookChecks.get(playerId) != token
                                    || !key.equals(viewers.get(playerId)) || !plugin.settings().carriers()
                                    || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
                            Block block = location.getBlock();
                            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
                            if (state == null) {
                                if (activeCarriers.containsKey(key)) removeAt(location);
                                return;
                            }
                            long now = System.nanoTime();
                            ItemDisplay known = activeCarriers.get(key);
                            if (known != null && known.isValid() && recentChecks.isFresh(key, state, now)) return;
                            reconcileState(block, state);
                            if (viewers.isViewed(key) && activeCarriers.containsKey(key)) recentChecks.record(key, state, now);
                        } finally { pendingLookChecks.remove(playerId, token); }
                    };
                    if (Bukkit.isOwnedByCurrentRegion(world, target.getX() >> 4, target.getZ() >> 4)) {
                        reconcile.run();
                    } else {
                        plugin.getServer().getRegionScheduler().run(plugin, world,
                                target.getX() >> 4, target.getZ() >> 4, region -> reconcile.run());
                    }
                    dispatched = true;
                } finally {
                    if (!dispatched) pendingLookChecks.remove(playerId, token);
                }
            }, () -> {
                if (pendingLookChecks.remove(playerId, token)) moveViewer(playerId, null);
            });
            // 已 retired 的实体调度器可能直接返回 null，不会调用回调。
            if (scheduled == null && pendingLookChecks.remove(playerId, token)) moveViewer(playerId, null);
        }
    }

    private void reconcileState(Block block, ImmutableBlockState state) {
        Key blockId = state.owner().value().id();
        ItemDisplay carrier = carrierAt(block.getLocation());
        if (carrier == null) spawnNewCarrier(block, blockId, state);
        else updateCarrier(carrier, blockId, state);
    }

    private void moveViewer(UUID playerId, @Nullable WorldBlock target) {
        WorldBlock abandoned = viewers.move(playerId, target);
        if (abandoned != null) {
            recentChecks.remove(abandoned);
            if (activeCarriers.containsKey(abandoned)) scheduleRemoval(abandoned);
        }
    }

    /** 退出、传送、跨区块会取消旧回调；不会撤销其他玩家对同一目标的观察。 */
    public void releaseViewer(UUID playerId) {
        pendingLookChecks.remove(playerId);
        playersUnderMaintenance.remove(playerId);
        moveViewer(playerId, null);
    }

    public void releaseViewerChunk(UUID playerId, UUID world, int x, int z) {
        WorldBlock target = viewers.get(playerId);
        if (target != null && target.world().equals(world)
                && target.position().x() >> 4 == x && target.position().z() >> 4 == z) releaseViewer(playerId);
    }

    private void scheduleRemoval(WorldBlock key) {
        if (!pendingRemovals.add(key)) return;
        World world = Bukkit.getWorld(key.world());
        if (world == null) { activeCarriers.remove(key); pendingRemovals.remove(key); return; }
        BlockPosition pos = key.position();
        plugin.getServer().getRegionScheduler().run(plugin, world, pos.x() >> 4, pos.z() >> 4, task -> {
            pendingRemovals.remove(key);
            // 新观察者可能已经抵达，延迟清理必须重新验证引用计数。
            if (viewers.isViewed(key)) return;
            recentChecks.remove(key);
            ItemDisplay carrier = activeCarriers.remove(key);
            if (carrier != null && world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4) && carrier.isValid()) carrier.remove();
        });
    }

    /** 实体卸载事件提供的对象已经在所属区域；不通过坐标重新加载区块。 */
    public void onCarrierRemoved(Entity entity) {
        if (!isCarrier(entity)) return;
        WorldBlock key = key(entity.getLocation());
        activeCarriers.remove(key, entity);
        recentChecks.remove(key);
        pendingRemovals.remove(key);
    }

    /** 旧版本持久化载体只随实体区块加载迁移，不启动全图清扫。 */
    public void onEntitiesLoaded(List<Entity> entities) {
        for (Entity entity : entities) {
            if (!isCarrier(entity)) continue;
            WorldBlock key = key(entity.getLocation());
            if (!viewers.isViewed(key) || !plugin.settings().carriers()) { entity.remove(); continue; }
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(entity.getLocation().getBlock());
            if (state == null) { entity.remove(); continue; }
            ItemDisplay previous = activeCarriers.putIfAbsent(key, (ItemDisplay) entity);
            if (previous != null && previous != entity && previous.isValid()) entity.remove();
            else updateCarrier((ItemDisplay) entity, state.owner().value().id(), state);
        }
    }

    public void invalidateViewedBlocks() {
        recentChecks.clear();
        if (!plugin.settings().carriers()) {
            viewers.clear();
            for (WorldBlock key : activeCarriers.keySet()) scheduleRemoval(key);
        }
    }

    /** 禁用插件时只能在实体所属区域直接修改它。 */
    public void close() {
        viewers.clear();
        pendingLookChecks.clear();
        recentChecks.clear();
        pendingRemovals.clear();
        for (ItemDisplay carrier : activeCarriers.values()) {
            if (Bukkit.isOwnedByCurrentRegion(carrier) && carrier.isValid()) carrier.remove();
        }
        activeCarriers.clear();
        playersUnderMaintenance.clear();
    }

    private static WorldBlock key(Location location) {
        return new WorldBlock(location.getWorld().getUID(), BlockPosition.of(location));
    }

    private record WorldBlock(UUID world, BlockPosition position) {}

    /** 手动刷新只清理附近已加载的实体，不再逐块扫描或为无人观察的方块预建实体。 */
    public void rescanMissing(Player player, int radius, Consumer<Integer> onFinish) {
        recentChecks.clear();
        scanCarriersAround(player, radius, false, onFinish);
        // 缺失的观察目标由固定的 10 tick 检查补齐，不另建第二套扫描任务。
    }

    private void chunkFinished(Player player, UUID playerId, Object token, AtomicInteger remaining, AtomicInteger count,
                               Consumer<Integer> onFinish) {
        if (remaining.decrementAndGet() == 0) {
            finishForPlayer(player, () -> onFinish.accept(count.get()), playerId, token);
        }
    }

    private void finishForPlayer(Player player, Runnable callback, UUID playerId, Object token) {
        var scheduled = player.getScheduler().run(plugin, task -> {
            if (playersUnderMaintenance.remove(playerId, token)) callback.run();
        }, () -> playersUnderMaintenance.remove(playerId, token));
        if (scheduled == null) playersUnderMaintenance.remove(playerId, token);
    }

    public void purgeAround(Player player, int radius, Consumer<Integer> onFinish) {
        scanCarriersAround(player, radius, false, onFinish);
    }

    public void removeAllAround(Player player, int radius, Consumer<Integer> onFinish) {
        scanCarriersAround(player, radius, true, onFinish);
    }

    private void scanCarriersAround(Player player, int radius, boolean removeAll, Consumer<Integer> onFinish) {
        UUID playerId = player.getUniqueId();
        Object token = new Object();
        if (playersUnderMaintenance.putIfAbsent(playerId, token) != null) {
            onFinish.accept(0);
            return;
        }
        Location center = player.getLocation();
        World world = center.getWorld();
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        List<int[]> chunks = chunkCoordinates(centerX, centerZ, radius);
        AtomicInteger remaining = new AtomicInteger(chunks.size());
        AtomicInteger removed = new AtomicInteger();
        if (chunks.isEmpty()) {
            finishForPlayer(player, () -> onFinish.accept(0), playerId, token);
            return;
        }
        // 管理员维护也分散到不同 tick，避免一次刷新同时遍历数十个区块实体列表。
        long delay = 1L;
        for (int[] coords : chunks) {
            int chunkX = coords[0];
            int chunkZ = coords[1];
            plugin.getServer().getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, task -> {
                if (playersUnderMaintenance.get(playerId) != token) return;
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ, false);
                    for (Entity entity : chunk.getEntities()) {
                        if (!isCarrier(entity)) continue;
                        Location entityLocation = entity.getLocation();
                        if (Math.abs(entityLocation.getBlockX() - centerX) > radius
                                || Math.abs(entityLocation.getBlockY() - centerY) > radius
                                || Math.abs(entityLocation.getBlockZ() - centerZ) > radius) continue;
                        migrateLegacy(entity);
                        migrateLegacyData(entity);
                        if (removeAll || !plugin.settings().carriers()
                                || !viewers.isViewed(key(entityLocation)) || !isValidCarrier(entity)) {
                            entity.remove();
                            removed.incrementAndGet();
                        } else {
                            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(entityLocation.getBlock());
                            if (state != null) updateCarrier((ItemDisplay) entity, state.owner().value().id(), state);
                        }
                    }
                }
                chunkFinished(player, playerId, token, remaining, removed, onFinish);
            }, delay++);
        }
    }

    private boolean isValidCarrier(Entity entity) {
        String stored = entity.getPersistentDataContainer().get(blockIdKey, PersistentDataType.STRING);
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(entity.getLocation().getBlock());
        return stored != null && state != null && stored.equals(state.owner().value().id().asString());
    }

    private void migrateLegacyData(Entity entity) {
        if (legacyBlockIdKey == null) return;
        var pdc = entity.getPersistentDataContainer();
        String old = pdc.get(legacyBlockIdKey, PersistentDataType.STRING);
        if (old == null) return;
        if (!pdc.has(blockIdKey, PersistentDataType.STRING)) {
            pdc.set(blockIdKey, PersistentDataType.STRING, old);
        }
        pdc.remove(legacyBlockIdKey);
    }

    public @Nullable ItemDisplay carrierAt(Location blockLocation) {
        if (loadedChunkAt(blockLocation) == null) return null;
        WorldBlock key = key(blockLocation);
        ItemDisplay known = activeCarriers.get(key);
        if (known != null && known.isValid()) return known;
        activeCarriers.remove(key);
        ItemDisplay found = null;
        for (Entity entity : blockLocation.getWorld().getNearbyEntities(
                blockLocation.clone().add(.5, .5, .5), .49, .49, .49, CarrierManager::isCarrier)) {
            if (!sameBlock(entity.getLocation(), blockLocation)) continue;
            if (found == null) found = (ItemDisplay) entity;
            else entity.remove();
        }
        if (found != null) activeCarriers.put(key, found);
        return found;
    }

    private @Nullable Chunk loadedChunkAt(Location location) {
        World world = location.getWorld();
        if (world == null) return null;
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        return world.isChunkLoaded(chunkX, chunkZ) ? world.getChunkAt(chunkX, chunkZ, false) : null;
    }

    private static List<int[]> chunkCoordinates(int centerX, int centerZ, int radius) {
        int minChunkX = (centerX - radius) >> 4;
        int maxChunkX = (centerX + radius) >> 4;
        int minChunkZ = (centerZ - radius) >> 4;
        int maxChunkZ = (centerZ + radius) >> 4;
        List<int[]> chunks = new ArrayList<>((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new int[]{chunkX, chunkZ});
            }
        }
        return chunks;
    }

    public static boolean isCarrier(Entity entity) {
        return entity instanceof ItemDisplay
                && (entity.getScoreboardTags().contains(TAG) || entity.getScoreboardTags().contains(LEGACY_TAG));
    }

    public static void migrateLegacy(Entity entity) {
        if (entity.getScoreboardTags().contains(LEGACY_TAG)) {
            entity.removeScoreboardTag(LEGACY_TAG);
            entity.addScoreboardTag(TAG);
        }
    }

    public @Nullable Key carrierBlockId(Entity entity) {
        migrateLegacyData(entity);
        String stored = entity.getPersistentDataContainer().get(blockIdKey, PersistentDataType.STRING);
        return stored == null ? null : Key.of(stored);
    }

    private static boolean sameBlock(Location first, Location second) {
        return first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private record BlockPosition(int x, int y, int z) {
        private static BlockPosition of(Location location) {
            return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }
}

