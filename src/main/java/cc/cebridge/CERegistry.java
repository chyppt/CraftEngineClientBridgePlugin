package cc.cebridge;

import cc.cebridge.util.Names;
import io.papermc.paper.datacomponent.DataComponentTypes;
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
 * 2. 构建 Jade 载体物品（保留 CE 的 item_model，必要时补 item_name 和 Jade marker）。
 */
public final class CERegistry {

    private final CraftEngineBridgePlugin plugin;
    private volatile Map<Key, BukkitItemDefinition> blockToItem = Map.of();
    private volatile Map<Key, Key> cropDisplayItems = Map.of();
    // 仅缓存模板，对外始终 clone，避免不同实体共享可变 ItemStack。
    private final Map<Key, ItemStack> carrierTemplates = new HashMap<>();
    private final Map<Key, ItemStack> cropTemplates = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean markerWarningLogged = new java.util.concurrent.atomic.AtomicBoolean();

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
        carrierTemplates.clear();
        cropTemplates.clear();
        markerWarningLogged.set(false);
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
     * 构建隐形载体的物品：直接使用 CE 物品并保留 item_model，
     * 缺失 item_name 时按配置表或 ID 美化补齐。
     */
    public synchronized @Nullable ItemStack buildCarrierItem(ImmutableBlockState state) {
        if (state == null) return null;
        Key blockId = state.owner().value().id();
        if (!isCrop(state)) return buildCarrierItem(blockId);
        ItemStack template = cropTemplates.get(blockId);
        if (template == null) {
            template = buildCropDisplayItem(blockId);
            // 作物不能回退到 settings.item 的种子，否则 Jade 会稳定地显示错误名称。
            // 解析失败时暂不创建载体，下一次观察检查会重试；成熟产物加载完成后立即恢复。
            if (template == null) return null;
            // 名字只描述作物本身，不用后缀伪装客户端原生作物状态栏。
            cropTemplates.put(blockId, template);
        }
        return template.clone();
    }

    public synchronized @Nullable ItemStack buildCarrierItem(Key blockId) {
        var block = CraftEngineBlocks.byId(blockId);
        // ID 入口也走同一个作物模板，禁止在无状态路径先创建种子载体。
        if (block != null && isCrop(block.defaultState())) return buildCarrierItem(block.defaultState());
        ItemStack template = carrierTemplates.get(blockId);
        if (template != null) return template.clone();
        BukkitItemDefinition def = itemForBlock(blockId);
        if (def == null) return null;
        ItemStack stack;
        try {
            stack = def.buildBukkitItem();
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to build CE item for carrier " + blockId.asString() + ": " + t);
            return null;
        }
        Key itemId = def.id();
        if (!stack.hasData(DataComponentTypes.ITEM_NAME)) {
            Component name = displayName(blockId, itemId);
            if (name != null) stack.setData(DataComponentTypes.ITEM_NAME, name);
        }
        ItemStack prepared = addJadeMarker(stack, blockId);
        carrierTemplates.put(blockId, prepared);
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
                return material == null ? null : addJadeMarker(new ItemStack(material), blockId);
            }
            ItemStack item = definition.buildBukkitItem();
            if (item == null || item.isEmpty()) return null;
            if (!item.hasData(DataComponentTypes.ITEM_NAME)) {
                Component name = displayName(blockId, definition.id());
                if (name != null) item.setData(DataComponentTypes.ITEM_NAME, name);
            }
            return addJadeMarker(item, blockId);
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

    /** Paper does not expose CUSTOM_DATA in the API; use the server's NMS component API reflectively. */
    private ItemStack addJadeMarker(ItemStack stack, Key blockId) {
        try {
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            Object nmsStack = craftItemStack.getMethod("asNMSCopy", ItemStack.class).invoke(null, stack);
            Class<?> compoundTag = Class.forName("net.minecraft.nbt.CompoundTag");
            Object tag = compoundTag.getConstructor().newInstance();
            Object existing = nmsStack.getClass().getMethod("get", Class.forName("net.minecraft.core.component.DataComponentType"))
                    .invoke(nmsStack, Class.forName("net.minecraft.core.component.DataComponents").getField("CUSTOM_DATA").get(null));
            if (existing != null) {
                Object copy = existing.getClass().getMethod("copyTag").invoke(existing);
                tag = copy;
            }
            Object markerTag = compoundTag.getConstructor().newInstance();
            compoundTag.getMethod("putString", String.class, String.class).invoke(markerTag, "id", blockId.asString());
            compoundTag.getMethod("put", String.class, Class.forName("net.minecraft.nbt.Tag"))
                    .invoke(tag, "$jade:stack", markerTag);
            Class<?> customData = Class.forName("net.minecraft.world.item.component.CustomData");
            Object component = customData.getMethod("of", compoundTag).invoke(null, tag);
            Object type = Class.forName("net.minecraft.core.component.DataComponents").getField("CUSTOM_DATA").get(null);
            nmsStack.getClass().getMethod("set", Class.forName("net.minecraft.core.component.DataComponentType"), Object.class)
                    .invoke(nmsStack, type, component);
            ItemStack converted = (ItemStack) craftItemStack.getMethod("asBukkitCopy", craftItemStack.getMethod("asNMSCopy", ItemStack.class).getReturnType())
                    .invoke(null, nmsStack);
            return converted;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // 不同服务端映射可能不支持反射；只记录一次，避免每个方块刷屏。
            if (markerWarningLogged.compareAndSet(false, true)) {
                plugin.getLogger().warning("Unable to add Jade custom_data marker; using normal CE carrier item: " + ex);
            }
            return stack;
        }
    }

    /** 掉落物等纯物品场景的名字兜底。 */
    public @Nullable Component displayNameForItem(Key itemId) {
        return displayName(itemId, itemId);
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
