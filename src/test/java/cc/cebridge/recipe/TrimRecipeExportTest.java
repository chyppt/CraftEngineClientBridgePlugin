package cc.cebridge.recipe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Function;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.*;
import net.momirealms.craftengine.core.util.*;
import org.junit.jupiter.api.Test;

class TrimRecipeExportTest {
    private static Ingredient ingredient(String... ids) {
        var keys=new LinkedHashSet<UniqueKey>();for(var id:ids) keys.add(UniqueKey.create(Key.of(id)));
        return Ingredient.of(List.of(),keys,keys,false);
    }
    private static CustomSmithingTrimRecipe recipe(Ingredient base,Ingredient template,Ingredient addition) {
        return new CustomSmithingTrimRecipe(Key.of("test:trim"),true,template,base,addition,Key.of("minecraft:sentry"),null,null);
    }
    @Test void exactOutputUsesCeTrimAndCorrectTemplateBaseAdditionOrder() {
        try(var fixture=new Fixture()) {
            var base=fixture.item("minecraft:iron_chestplate");var template=fixture.item("minecraft:sentry_armor_trim_smithing_template");
            var redstone=fixture.item("minecraft:redstone");var gold=fixture.item("minecraft:gold_ingot");
            var redResult=mock(Item.class);var goldResult=mock(Item.class);
            fixture.labels.put(redResult,"red_trim");fixture.labels.put(goldResult,"gold_trim");
            var seen=new ArrayList<Item>();
            Function<net.momirealms.craftengine.core.item.recipe.input.SmithingInput,Item> assemble=input->{
                assertSame(base,input.base().item());assertSame(template,input.template().item());
                seen.add(input.addition().item());return input.addition().item()==redstone?redResult:goldResult;
            };
            var values=TrimRecipeExport.convert(recipe(ingredient("minecraft:iron_chestplate"),ingredient("minecraft:sentry_armor_trim_smithing_template"),ingredient("minecraft:redstone","minecraft:gold_ingot")),fixture.labels::get,assemble);
            assertEquals(2,values.size());assertNotEquals(values.get(0).get("id"),values.get(1).get("id"));
            assertEquals("smithing",values.getFirst().get("type").getAsString());
            assertEquals("minecraft:sentry_armor_trim_smithing_template",values.getFirst().getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonArray("choices").get(0).getAsString());
            assertEquals("minecraft:iron_chestplate",values.getFirst().getAsJsonArray("inputs").get(1).getAsJsonObject().getAsJsonArray("choices").get(0).getAsString());
            assertEquals("red_trim",values.getFirst().getAsJsonArray("outputs").get(0).getAsString());
            verify(base,times(2)).copy();assertEquals(List.of(redstone,gold),seen);
        }
    }
    @Test void variantBudgetRejectsBeforeBuildingAnyItem() {
        try(var fixture=new Fixture()) {
            var many=new String[24];for(int i=0;i<many.length;i++) many[i]="test:item_"+i;
            assertThrows(IllegalArgumentException.class,()->TrimRecipeExport.convert(recipe(ingredient(many),ingredient("test:template"),ingredient(many)),fixture.labels::get,input->null));
            fixture.api.verifyNoInteractions();
        }
    }
    @Test void invalidTrimOutputIsNotExportedAsUntrimmedArmor() {
        try(var fixture=new Fixture()) {
            fixture.item("minecraft:iron_chestplate");fixture.item("minecraft:sentry_armor_trim_smithing_template");fixture.item("minecraft:redstone");
            assertThrows(IllegalArgumentException.class,()->TrimRecipeExport.convert(recipe(ingredient("minecraft:iron_chestplate"),ingredient("minecraft:sentry_armor_trim_smithing_template"),ingredient("minecraft:redstone")),fixture.labels::get,input->null));
        }
    }
    private static class Fixture implements AutoCloseable {
        final Map<String,Item> items=new HashMap<>();final Map<Item,String> labels=new IdentityHashMap<>();
        final org.mockito.MockedStatic<Item> api=mockStatic(Item.class);
        final org.mockito.MockedStatic<UniqueIdItem> unique=mockStatic(UniqueIdItem.class);
        Fixture() {
            api.when(()->Item.byId(any(Key.class),any(ItemBuildContext.class))).thenAnswer(c->items.get(c.getArgument(0).toString()));
            unique.when(()->UniqueIdItem.of(any())).thenAnswer(c->{var wrapped=mock(UniqueIdItem.class);Item item=c.getArgument(0);when(wrapped.item()).thenReturn(item);when(wrapped.is(any())).thenReturn(true);return wrapped;});
        }
        Item item(String id) { var item=mock(Item.class);items.put(id,item);labels.put(item,id);when(item.copy()).thenReturn(item);when(item.count(anyInt())).thenReturn(item);return item; }
        public void close() { unique.close();api.close(); }
    }
}
