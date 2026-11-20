package cc.cebridge;

import cc.cebridge.util.Names;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.behavior.BlockItem;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 与 CraftEngine 的桥接层：
 * 1. 启动/重载时扫描全部 CE 物品，建立 方块ID -> 物品定义 反查表；
 * 2. 为客户端目录构建展示图标，保留 CE 物品模型。
 */
public final class CERegistry {

    private final CraftEngineBridgePlugin plugin;
    private volatile Map<Key, BukkitItemDefinition> blockToItem = Map.of();
    private volatile Map<Key, Key> cropDisplayItems = Map.of();
    // 仅缓存模板，对外始终 clone，避免不同实体共享可变 ItemStack。
    private final Map<Key, ItemStack> iconTemplates = new HashMap<>();
    private final Map<Key, ItemStack> cropTemplates = new HashMap<>();

    public CERegistry(CraftEngineBridgePlugin plugin) {
        this.plugin = plugin;
    }

    public void rebuild() {
        rebuild(() -> { });
    }

    public synchronized void rebuild(Runnable onComplete) {
        Map<Key, BukkitItemDefinition> map = new HashMap<>();
        try {
            for (Map.Entry<Key, ItemDefinition> entry : CraftEngineItems.loadedItems().entrySet()) {
                if (!(entry.getValue() instanceof BukkitItemDefinition def)) continue;
                BlockItem blockItem = def.behavior().getFirst(BlockItem.class);
                if (blockItem == null) continue;
                map.putIfAbsent(blockItem.block(), def);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to index CraftEngine items: " + t);
        }
        this.blockToItem = Collections.unmodifiableMap(map);
        Map<Key, Key> crops = new HashMap<>();
        boolean warned = false;
        for (var block : CraftEngineBlocks.loadedBlocks().values()) {
            if (!isCrop(block.defaultState())) continue;
            BukkitItemDefinition planting = map.get(block.id());
            try {
                Key display = CropDisplayResolver.resolve(block.loot(), planting == null ? null : planting.id(),
                        id -> CraftEngineItems.byId(id) != null || vanillaCropMaterial(id) != null);
                if (display != null) crops.put(block.id(), display);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                if (!warned) {
                    plugin.getLogger().warning("CE 作物掉落定义读取失败，回退显式配置：" + ex);
                    warned = true;
                }
            }
        }
        this.cropDisplayItems = Map.copyOf(crops);
        iconTemplates.clear();
        cropTemplates.clear();
        plugin.getLogger().info("Indexed " + map.size() + " CraftEngine blocks; resolved " + crops.size() + " crop display items from loot definitions.");
        onComplete.run();
    }

    public Map<Key, Key> cropDisplayItems() { return cropDisplayItems; }

    public int blockCount() {
        return blockToItem.size();
    }

    public @Nullable BukkitItemDefinition itemForBlock(Key blockId) {
        return blockToItem.get(blockId);
    }

    /**
     * 构建客户端目录图标：直接使用 CE 物品并保留模型，
     * 缺失名称时按配置表或 ID 美化补齐。
     */
    public synchronized @Nullable ItemStack buildDisplayItem(ImmutableBlockState state) {
        if (state == null) return null;
        Key blockId = state.owner().value().id();
        if (!isCrop(state)) return buildDisplayItem(blockId);
        ItemStack template = cropTemplates.get(blockId);
        if (template == null) {
            template = buildCropDisplayItem(blockId);
            // 作物不能回退到 settings.item 的种子，否则 Jade 会稳定地显示错误名称。
            // 解析失败时不返回错误的种子图标，后续重载时重新索引。
            if (template == null) return null;
            // 名字只描述作物本身，不用后缀伪装客户端原生作物状态栏。
            cropTemplates.put(blockId, template);
        }
        return template.clone();
    }

    public synchronized @Nullable ItemStack buildDisplayItem(Key blockId) {
        var block = CraftEngineBlocks.byId(blockId);
        // ID 入口也走同一个作物模板，禁止在无状态路径先创建种子图标。
        if (block != null && isCrop(block.defaultState())) return buildDisplayItem(block.defaultState());
        ItemStack template = iconTemplates.get(blockId);
        if (template != null) return template.clone();
        BukkitItemDefinition def = itemForBlock(blockId);
        if (def == null) return null;
        ItemStack stack;
        try {
            stack = def.buildBukkitItem();
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to build CE display item " + blockId.asString() + ": " + t);
            return null;
        }
        Key itemId = def.id();
        applyDisplayName(stack, blockId, itemId);
        ItemStack prepared = stack;
        iconTemplates.put(blockId, prepared);
        return prepared.clone();
    }

    private @Nullable ItemStack buildCropDisplayItem(Key blockId) {
        String configured = plugin.getConfig().getString("jade-display-items." + blockId.asString());
        if (configured != null && !configured.isBlank()) {
            ItemStack item = tryCropItem(blockId, configured);
            if (item != null) return item;
        }
        Key resolved = cropDisplayItems.get(blockId);
        if (resolved != null) {
            ItemStack item = tryCropItem(blockId, resolved.asString());
            if (item != null) return item;
        }
        String path = blockId.value();
        if (path.endsWith("_crop")) path = path.substring(0, path.length() - 5);
        String[] candidates = {
                path,
                path.endsWith("ies") ? path.substring(0, path.length() - 3) + "y" : path,
                path.endsWith("oes") ? path.substring(0, path.length() - 2) : path,
                path.endsWith("s") ? path.substring(0, path.length() - 1) : path
        };
        for (String candidate : candidates) {
            if (candidate.isBlank()) continue;
            ItemStack item = tryCropItem(blockId, blockId.namespace() + ":" + candidate);
            if (item != null) return item;
        }
        return null;
    }

    private @Nullable ItemStack tryCropItem(Key blockId, String itemId) {
        try {
            BukkitItemDefinition definition = CraftEngineItems.byId(itemId);
            if (definition == null) {
                // CE 的增强原版作物会掉落 minecraft:*，并不一定另建 CE 物品定义。
                // 只接受原版命名空间，避免把缺失的 custom:wheat 误认成原版小麦。
                Material material = vanillaCropMaterial(Key.of(itemId));
                return material == null ? null : new ItemStack(material);
            }
            ItemStack item = definition.buildBukkitItem();
            if (item == null || item.getType().isAir()) return null;
            applyDisplayName(item, blockId, definition.id());
            return item;
        } catch (RuntimeException failure) {
            // 重载期间可能暂时不可构建；不要把失败或种子写入模板缓存，下一次观察可以重试。
            return null;
        }
    }

    private static @Nullable Material vanillaCropMaterial(Key id) {
        if (!id.namespace().equals("minecraft")) return null;
        Material material = Material.matchMaterial(id.asString());
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }
    private static boolean isCrop(ImmutableBlockState state) {
        // AdvancedCrop is often inside a composite behavior; tags survive that wrapping.
        return state.settings().tags().contains(Key.of("minecraft:crops"))
                || (state.behavior() != null
                && state.behavior().getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("crop"));
    }

    private void applyDisplayName(ItemStack stack, Key blockId, Key itemId) {
        // ItemMeta 的 Adventure 名称接口可用于早期 1.20，不链接新版 DataComponentTypes。
        var meta = stack.getItemMeta();
        if (meta == null || meta.hasDisplayName()) return;
        Component name = displayName(blockId, itemId);
        if (name != null) {
            meta.displayName(name);
            stack.setItemMeta(meta);
        }
    }

    /** 名字解析优先级：display-names 配置表 > ID 自动美化（auto-name）。 */
    public @Nullable Component displayName(Key blockId, Key itemId) {
        String configured = plugin.getConfig().getString("display-names." + blockId.asString());
        if (configured == null || configured.isBlank()) {
            configured = plugin.getConfig().getString("display-names." + itemId.asString());
        }
        if (configured != null && !configured.isBlank()) {
            return MiniMessage.miniMessage().deserialize(configured);
        }
        if (!plugin.settings().autoName()) return null;
        return Names.prettify(itemId.value());
    }
}
