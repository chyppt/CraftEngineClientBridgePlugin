package cc.cebridge.recipe;

import cc.cebridge.compat.CraftEngineCompat;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Function;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.*;
import net.momirealms.craftengine.core.item.recipe.input.SmithingInput;

/** 饰纹输出依赖底材和材料，每个示例用 CE assemble 得到实际结果，禁止用未饰纹原料冒充。 */
final class TrimRecipeExport {
    private static final int MAX_VARIANTS=512;
    private record Choice(String id,Item item,String encoded) {}
    private TrimRecipeExport() {}
    static List<JsonObject> convert(CustomSmithingTrimRecipe recipe) {
        return convert(recipe,item->Base64.getEncoder().encodeToString(ItemStackUtils.getBukkitStack(item).serializeAsBytes()));
    }
    static List<JsonObject> convert(CustomSmithingTrimRecipe recipe,Function<Item,String> encode) {
        return convert(recipe,encode,input->recipe.assemble(input,ItemBuildContext.empty()));
    }
    // 隔离平台饰纹写入，测试可检查每组输入和输出，而无需启动 CraftEngine/Bukkit 世界。
    static List<JsonObject> convert(CustomSmithingTrimRecipe recipe,Function<Item,String> encode,Function<SmithingInput,Item> assemble) {
        long count=(long)recipe.base().items().size()*recipe.template().items().size()*recipe.addition().items().size();
        if(count<1 || count>MAX_VARIANTS) throw new IllegalArgumentException("Trim recipe exceeds 512 exact combinations");
        var bases=choices(recipe.base(),encode);var templates=choices(recipe.template(),encode);var additions=choices(recipe.addition(),encode);
        var result=new ArrayList<JsonObject>();
        for(var base:bases) for(var template:templates) for(var addition:additions) {
            // CE 可以修改底材；每一组都给独立副本，不改变目录或下一组输出。
            var input=new SmithingInput(UniqueIdItem.of(base.item().copy()),UniqueIdItem.of(template.item().copy()),UniqueIdItem.of(addition.item().copy()));
            if(!recipe.matches(input)) continue;
            var output=assemble.apply(input);
            if(output==null || output.isEmpty()) continue; // 原版不支持饰纹的底材不发布虚假结果。
            var value=new JsonObject();value.addProperty("id",id(recipe.id().asString(),base.id(),template.id(),addition.id()));
            value.addProperty("type","smithing");value.addProperty("displayOnly",true);
            var inputs=new JsonArray();inputs.add(slot(template.encoded()));inputs.add(slot(base.encoded()));inputs.add(slot(addition.encoded()));
            value.add("inputs",inputs);var outputs=new JsonArray();outputs.add(encode.apply(output));value.add("outputs",outputs);result.add(value);
        }
        if(result.isEmpty()) throw new IllegalArgumentException("Trim recipe has no valid CE result");
        return result;
    }
    private static List<Choice> choices(Ingredient ingredient,Function<Item,String> encode) {
        if(ingredient.items().size()>256) throw new IllegalArgumentException("Trim ingredient exceeds 256 choices");
        var result=new ArrayList<Choice>();
        for(var key:ingredient.items()) {
            var item=CraftEngineCompat.buildItem(key.key(),ItemBuildContext.empty());
            if(item==null || item.isEmpty()) throw new IllegalArgumentException("Missing trim ingredient "+key);
            item=CraftEngineCompat.applyPredicateLooks(ingredient, item).count(ingredient.count());
            result.add(new Choice(key.toString(),item,encode.apply(item)));
        }
        return result;
    }
    private static JsonObject slot(String stack) {
        var slot=new JsonObject();slot.addProperty("exactInput",true);var choices=new JsonArray();choices.add(stack);slot.add("choices",choices);return slot;
    }
    private static String id(String recipe,String base,String template,String addition) {
        try {
            return "cebridge:trim/"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (recipe+"\0"+base+"\0"+template+"\0"+addition).getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
