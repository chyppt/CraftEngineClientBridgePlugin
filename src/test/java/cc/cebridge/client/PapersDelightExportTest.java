package cc.cebridge.client;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** PD 公开记录/字段契约夹具；验证多产物、标签和工具，不需要创建世界。 */
class PapersDelightExportTest {
    public record Matcher(List<String> expressions) {}
    public record Result(String item,int count,double chance) {}
    public record Cutting(Matcher inputMatcher,Matcher toolsMatcher,List<Result> results,String source) {}
    public record Ingredient(Matcher matcher) {}
    public static final class Cooking {
        public String source="test/pot";
        public String result="test:soup";
        public int resultCount=3;
        public String container="minecraft:bowl";
        public int cookingTime=200;
        public List<Ingredient> ingredients=List.of(new Ingredient(new Matcher(List.of("test:vegetable"))));
    }
    public static final class Resolver {
        final AtomicInteger calls=new AtomicInteger();
        public boolean matchesTag(ItemStack stack,String tag) { calls.incrementAndGet();return tag.equals("test:knives"); }
        public boolean matchesAdvancedTag(ItemStack stack,String tag) { calls.incrementAndGet();return tag.equals("test:foods"); }
    }
    public static final class Items {
        public static ItemStack create(String id,int count) {
            if(id.equals("test:missing")) return null;
            var stack=mock(ItemStack.class);when(stack.isEmpty()).thenReturn(false);
            when(stack.serializeAsBytes()).thenReturn((id+"/"+count).getBytes(java.nio.charset.StandardCharsets.UTF_8));return stack;
        }
    }
    private PapersDelightExport exporter(Resolver resolver) throws Exception {
        return new PapersDelightExport(getClass().getClassLoader(),Items.class.getMethod("create",String.class,int.class),resolver,
            List.of(Items.create("test:knife",1),Items.create("test:vegetable",1)));
    }
    @Test void cuttingRetainsAllOutputsChanceToolAndStableIds() throws Exception {
        var resolver=new Resolver();var exporter=exporter(resolver);
        var recipe=new Cutting(new Matcher(List.of("advtag:test:foods")),new Matcher(List.of("#test:knives")),
            List.of(new Result("test:slices",4,1),new Result("test:seeds",2,0.25),new Result("test:missing",1,0)),"test/cut");
        var out=exporter.convert(recipe,"cutting",0);
        assertEquals("cutting",out.get("type").getAsString());
        assertEquals(2,out.getAsJsonArray("outputs").size());
        assertEquals(0.25,out.getAsJsonArray("outputChances").get(1).getAsDouble());
        assertEquals("tool",out.getAsJsonArray("inputs").get(1).getAsJsonObject().get("role").getAsString());
        assertEquals(2,out.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonArray("choices").size());
        assertEquals(out,exporter.convert(recipe,"cutting",0));
        assertEquals(4,resolver.calls.get(),"Repeated expressions reuse the catalog-local cache");
        assertNotEquals(out.get("id"),exporter.convert(recipe,"cutting",1).get("id"));
    }
    @Test void potRetainsContainerTimeAndResultCount() throws Exception {
        var out=exporter(new Resolver()).convert(new Cooking(),"cooking_pot",0);
        assertEquals("cooking_pot",out.get("type").getAsString());
        assertEquals(200,out.get("cookingTimeTicks").getAsInt());
        assertEquals("container",out.getAsJsonArray("inputs").get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("test:soup/3",new String(Base64.getDecoder().decode(out.getAsJsonArray("outputs").get(0).getAsString()),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void unknownItemsAndEmptyTagsAreRejectedInsteadOfShowingCarriers() throws Exception {
        var exporter=exporter(new Resolver());
        var recipe=new Cutting(new Matcher(List.of("#test:missing")),new Matcher(List.of()),List.of(new Result("test:result",1,1)),"test/bad");
        assertThrows(IllegalArgumentException.class,()->exporter.convert(recipe,"cutting",0));
        var missing=new Cutting(new Matcher(List.of("test:vegetable")),new Matcher(List.of()),List.of(new Result("test:missing",1,1)),"test/bad");
        assertThrows(IllegalArgumentException.class,()->exporter.convert(missing,"cutting",0));
    }
}
