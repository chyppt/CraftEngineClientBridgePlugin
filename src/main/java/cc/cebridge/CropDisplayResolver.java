package cc.cebridge;

import net.momirealms.craftengine.core.loot.Loot;
import net.momirealms.craftengine.core.loot.LootPool;
import net.momirealms.craftengine.core.loot.LootTable;
import net.momirealms.craftengine.core.loot.entry.AbstractCompositeLootEntryContainer;
import net.momirealms.craftengine.core.loot.entry.LootEntryContainer;
import net.momirealms.craftengine.core.loot.entry.SingleItemLootEntryContainer;
import net.momirealms.craftengine.core.util.Key;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 只读 CE 已解析的掉落定义，不执行掉落、随机数、条件或自定义函数。
 * CE 26.9.1 未公开这些容器的 getter，反射仅限四个已知字段，且仅在重载时运行。
 * 不能把它当作掉落模拟器：显示选择首个非种子产物，多产物作物可由配置明确覆盖。
 */
final class CropDisplayResolver {
    private CropDisplayResolver() { }

    static Key resolve(Loot loot, Key plantingItem, Predicate<Key> available)
            throws ReflectiveOperationException {
        if (!(loot instanceof LootTable)) return null;
        Set<Key> candidates = new LinkedHashSet<>();
        for (Object pool : (List<?>) read(LootTable.class, "pools", loot)) {
            for (Object entry : (List<?>) read(LootPool.class, "entryContainers", pool)) {
                collect((LootEntryContainer) entry, candidates, 0);
            }
        }
        // 豆类/咖啡本身也能种植：没有独立成品时，保留该物品，不乱猜一种别的作物。
        for (Key id : candidates) {
            if (!id.equals(plantingItem) && !isSeed(id) && available.test(id)) return id;
        }
        if (plantingItem != null && candidates.contains(plantingItem)
                && !isSeed(plantingItem) && available.test(plantingItem)) return plantingItem;
        return null;
    }

    private static void collect(LootEntryContainer entry, Set<Key> result, int depth)
            throws ReflectiveOperationException {
        if (depth > 16 || result.size() >= 256) return;
        if (entry instanceof SingleItemLootEntryContainer) {
            result.add((Key) read(SingleItemLootEntryContainer.class, "item", entry));
        } else if (entry instanceof AbstractCompositeLootEntryContainer) {
            for (Object child : (List<?>) read(AbstractCompositeLootEntryContainer.class, "children", entry)) {
                collect((LootEntryContainer) child, result, depth + 1);
            }
        }
    }

    private static boolean isSeed(Key id) {
        return id.value().endsWith("_seeds") || id.value().endsWith("_seed")
                || id.value().contains("entity_renderer/");
    }

    private static Object read(Class<?> owner, String name, Object instance)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(owner.getName() + "." + name);
        return field.get(instance);
    }
}
