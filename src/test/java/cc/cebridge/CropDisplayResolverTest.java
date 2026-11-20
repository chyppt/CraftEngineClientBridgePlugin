package cc.cebridge;

import net.momirealms.craftengine.core.loot.LootPool;
import net.momirealms.craftengine.core.loot.LootTable;
import net.momirealms.craftengine.core.loot.entry.AbstractCompositeLootEntryContainer;
import net.momirealms.craftengine.core.loot.entry.LootEntryContainer;
import net.momirealms.craftengine.core.loot.entry.SingleItemLootEntryContainer;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CropDisplayResolverTest {
    @Test void pepperPoolsSkipBothImmatureAndMatureSeedDrops() throws Exception {
        // 对齐 cer/rusticdelight/configuration/crops_entity.yml 的三个工厂实例。
        for (String[] group : List.of(
                new String[]{"bell_pepper_seeds", "red", "green", "yellow"},
                new String[]{"pale_bell_pepper_seeds", "orange", "white", "pink"},
                new String[]{"dark_bell_pepper_seeds", "blue", "purple", "black"})) {
            Key seed = Key.of("rusticdelight:" + group[0]);
            Key fruit = Key.of("rusticdelight:bell_pepper_" + group[1]);
            var loot = table(item(seed.asString()), item(seed.asString()), item(fruit.asString()),
                    item("rusticdelight:bell_pepper_" + group[2]),
                    item("rusticdelight:bell_pepper_" + group[3]));
            Set<Key> registeredItems = Set.of(seed, fruit,
                    Key.of("rusticdelight:bell_pepper_" + group[2]),
                    Key.of("rusticdelight:bell_pepper_" + group[3]));
            assertEquals(fruit, CropDisplayResolver.resolve(loot, seed, registeredItems::contains), group[0]);
        }
    }

    @Test void cabbageAlternativesResolveTheCropWithoutExecutingConditions() throws Exception {
        var alternatives = new Children(List.of(item("farmersdelight:cabbage"), item("farmersdelight:cabbage_seeds")));
        assertEquals(Key.of("farmersdelight:cabbage"), CropDisplayResolver.resolve(
                table(alternatives, item("farmersdelight:cabbage_seeds")), Key.of("farmersdelight:cabbage_seeds"), k -> true));
    }

    @Test void unrelatedNamesResolveFromLootInsteadOfPluralGuessing() throws Exception {
        for (String crop : List.of("cotton_boll", "bellpepper", "broccoli", "chinese_cabbage", "garlic_chive")) {
            Key fruit = Key.of("test:" + crop);
            assertEquals(fruit, CropDisplayResolver.resolve(table(item("test:seed"), item(fruit.asString())),
                    Key.of("test:seed"), key -> true));
        }
    }

    @Test void unavailableOutputsAndRendererItemsAreIgnored() throws Exception {
        var loot = table(item("test:entity_renderer/crop"), item("test:missing"), item("test:harvest"));
        assertEquals(Key.of("test:harvest"), CropDisplayResolver.resolve(loot, null,
                key -> !key.value().equals("missing")));
    }

    @Test void ediblePlantingItemRemainsFallbackWhenNoSeparateProduceExists() throws Exception {
        assertEquals(Key.of("rusticdelight:coffee_beans"), CropDisplayResolver.resolve(table(item("rusticdelight:coffee_beans")),
                Key.of("rusticdelight:coffee_beans"), key -> true));
    }

    @Test void vanillaProduceCanBeSelectedWithoutACustomItemDefinition() throws Exception {
        Key wheat = Key.of("minecraft:wheat");
        assertEquals(wheat, CropDisplayResolver.resolve(
                table(item("minecraft:wheat_seeds"), item("minecraft:wheat")),
                Key.of("minecraft:wheat_seeds"), Set.of(wheat)::contains));
    }

    @Test void seedOnlyLootNeverCreatesASeedCarrier() throws Exception {
        assertNull(CropDisplayResolver.resolve(table(item("farmersdelight:cabbage_seeds")),
                Key.of("farmersdelight:cabbage_seeds"), key -> true));
    }
    @Test void nullAndEmptyLootDoNotInventProduce() throws Exception {
        assertNull(CropDisplayResolver.resolve(null, null, key -> true));
        assertNull(CropDisplayResolver.resolve(table(), null, key -> true));
    }

    @Test void reloadUsesNewDefinitionsWithoutRetainingPreviousChoices() throws Exception {
        assertEquals(Key.of("test:a"), CropDisplayResolver.resolve(table(item("test:a")), null, k -> true));
        assertEquals(Key.of("test:b"), CropDisplayResolver.resolve(table(item("test:b")), null, k -> true));
    }

    private static LootTable table(LootEntryContainer... entries) {
        // 空数值 provider 故意不能执行，证明解析过程没有模拟随机掉落或访问世界。
        return new LootTable(List.of(new LootPool(List.of(entries), List.of(), List.of(), null, null)), List.of());
    }

    private static LootEntryContainer item(String id) { return new ItemEntry(Key.of(id)); }

    private static final class ItemEntry extends SingleItemLootEntryContainer {
        ItemEntry(Key id) { super(id, List.of(), List.of(), 1, 0); }
    }

    private static final class Children extends AbstractCompositeLootEntryContainer {
        Children(List<LootEntryContainer> entries) { super(List.of(), entries); }
        @Override protected LootEntryContainer compose(List<? extends LootEntryContainer> entries) {
            return (context, consumer) -> { throw new AssertionError("Must not execute loot"); };
        }
    }
}
