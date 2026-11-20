package cc.cebridge.recipe;

import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.recipe.*;
import net.momirealms.craftengine.core.item.recipe.result.CustomRecipeResult;
import net.momirealms.craftengine.core.util.Color;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真正运行 CE matches/assemble；只替换需服务器的物品后端和 NBT 序列化。 */
class DyeRecipeExportTest {
    private static Ingredient ingredient(String... ids) {
        var keys = new LinkedHashSet<UniqueKey>();
        for (String id : ids) keys.add(UniqueKey.create(Key.of(id)));
        return Ingredient.of(List.of(), keys, keys, false);
    }
    private static CustomDyeRecipe recipe(Ingredient target, Ingredient dye, CustomRecipeResult result, boolean transmute) {
        return new CustomDyeRecipe(Key.of("test:dye"), false, result, null, "", CraftingRecipeCategory.MISC,
                target, dye, null, null, false, transmute);
    }
    @Test void boundsAreCheckedBeforeBuildingItems() {
        Ingredient many = ingredient(IntStream.range(0, 33).mapToObj(i -> "test:item" + i).toArray(String[]::new));
        try (var items = mockStatic(Item.class)) {
            assertThrows(IllegalArgumentException.class, () -> DyeRecipeExport.convert(recipe(many, many, null, false)));
            assertThrows(IllegalArgumentException.class, () -> DyeRecipeExport.convert(recipe(ingredient(), many, null, false)));
            items.verifyNoInteractions();
        }
    }
    @Test void realDyeAssemblyKeepsColorOutputsSeparateAndIdsStable() {
        try (var fixture = new Fixture()) {
            Item target = fixture.item("test:jug", null), red = fixture.item("minecraft:red_dye", "red"), blue = fixture.item("minecraft:blue_dye", "blue");
            var applied = new ArrayList<List<Color>>();
            Item dyed = fixture.item("test:jug_colored", null);
            when(target.copyWithCount(1)).thenReturn(dyed);
            when(dyed.applyDyedColors(anyList())).thenAnswer(call -> {
                List<Color> colors = List.copyOf(call.getArgument(0)); applied.add(colors);
                return fixture.output(colors.getFirst().color());
            });
            var recipe = recipe(ingredient("test:jug"), ingredient("minecraft:red_dye", "minecraft:blue_dye"), null, false);
            var first = fixture.convert(recipe);
            assertEquals(2, first.size());
            assertEquals("shapeless", first.getFirst().get("type").getAsString());
            assertTrue(first.getFirst().get("displayOnly").getAsBoolean());
            assertEquals(2, first.getFirst().getAsJsonArray("inputs").size());
            assertNotEquals(first.get(0).getAsJsonArray("outputs"), first.get(1).getAsJsonArray("outputs"));
            assertNotEquals(first.get(0).get("id"), first.get(1).get("id"));
            assertEquals(2, applied.size());
            assertNotEquals(applied.get(0), applied.get(1));
            assertEquals(first, fixture.convert(recipe));
            verify(target, times(4)).copy(); verify(red, times(2)).copy(); verify(blue, times(2)).copy();
        }
    }
    @Test void nonDyeChoicesAreNotAdvertisedAsValidRecipes() {
        try (var fixture = new Fixture()) {
            Item target = fixture.item("test:jug", null);
            fixture.item("minecraft:stone", null);
            assertThrows(IllegalArgumentException.class, () -> fixture.convert(
                    recipe(ingredient("test:jug"), ingredient("minecraft:stone"), null, false)));
            verify(target, never()).copyWithCount(anyInt());
        }
    }
    @Test void transmuteUsesCeResultMergeBeforeColoring() {
        try (var fixture = new Fixture()) {
            Item target = fixture.item("test:jug", null), copy = fixture.item("test:copy", null);
            fixture.item("minecraft:red_dye", "red");
            Item base = fixture.item("test:new_result", null), merged = fixture.item("test:merged", null);
            CustomRecipeResult result = mock(CustomRecipeResult.class);
            when(result.buildItem(any())).thenReturn(base);
            when(base.vanillaId()).thenReturn(Key.of("minecraft:paper"));
            when(target.copyWithCount(1)).thenReturn(copy);
            when(copy.transmuteCopy(Key.of("minecraft:paper"))).thenReturn(merged);
            when(merged.applyDyedColors(anyList())).thenReturn(fixture.output(123));
            assertEquals(1, fixture.convert(recipe(ingredient("test:jug"), ingredient("minecraft:red_dye"), result, true)).size());
            verify(merged).merge(base);
            verify(merged).applyDyedColors(anyList());
        }
    }
    @Test void emptyCeOutputFailsInsteadOfPublishingAnUndyedPlaceholder() {
        try (var fixture = new Fixture()) {
            Item target = fixture.item("test:jug", null);
            fixture.item("minecraft:red_dye", "red");
            when(target.copyWithCount(1)).thenReturn(target);
            when(target.applyDyedColors(anyList())).thenReturn(null);
            assertThrows(IllegalArgumentException.class, () -> fixture.convert(
                    recipe(ingredient("test:jug"), ingredient("minecraft:red_dye"), null, false)));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Map<String, Item> items = new HashMap<>();
        private final Map<Item, UniqueKey> keys = new IdentityHashMap<>();
        private final Map<Item, byte[]> bytes = new IdentityHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();
        private final MockedStatic<Item> itemApi = mockStatic(Item.class);
        private final MockedStatic<UniqueIdItem> uniqueApi = mockStatic(UniqueIdItem.class);
        Fixture() {
            itemApi.when(() -> Item.byId(any(Key.class), any(net.momirealms.craftengine.core.item.ItemBuildContext.class))).thenAnswer(call -> items.get(call.getArgument(0).toString()));
            uniqueApi.when(() -> UniqueIdItem.of(any())).thenAnswer(call -> {
                Item item = call.getArgument(0); var unique = mock(UniqueIdItem.class);
                when(unique.item()).thenReturn(item); when(unique.id()).thenReturn(keys.get(item));
                when(unique.is(any())).thenAnswer(c -> c.getArgument(0) == keys.get(item));
                return unique;
            });

        }
        List<com.google.gson.JsonObject> convert(CustomDyeRecipe recipe) {
            return DyeRecipeExport.convert(recipe, item -> Base64.getEncoder().encodeToString(bytes.get(item)));
        }
        Item item(String id, String color) {
            Item item = mock(Item.class); items.put(id, item); keys.put(item, UniqueKey.create(Key.of(id)));
            when(item.copy()).thenReturn(item); when(item.count(anyInt())).thenReturn(item); when(item.count()).thenReturn(1);
            when(item.getDefinition()).thenReturn(Optional.empty());
            when(item.hasComponent(any())).thenReturn(color != null);
            when(item.getComponentAsJava(any())).thenReturn(color);
            bytes.put(item, new byte[]{(byte) sequence.incrementAndGet()});
            return item;
        }
        Item output(int color) {
            Item output = mock(Item.class);
            bytes.put(output, new byte[]{(byte)(color >> 16), (byte)(color >> 8), (byte)color}); return output;
        }
        @Override public void close() { uniqueApi.close(); itemApi.close(); }
    }
}
