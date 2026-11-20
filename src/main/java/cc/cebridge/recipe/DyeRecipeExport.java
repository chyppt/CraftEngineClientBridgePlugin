package cc.cebridge.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.CustomDyeRecipe;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.UniqueIdItem;
import net.momirealms.craftengine.core.item.recipe.input.CraftingInput;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/** 只在目录构建时计算单染料示例；不枚举指数级混色组合、不执行合成函数或消耗原料。 */
final class DyeRecipeExport {
    private static final int MAX_VARIANTS = 1024;
    private DyeRecipeExport() {}
    private record Choice(String id, Item item, String encoded) {}

    static List<JsonObject> convert(CustomDyeRecipe recipe) {
        return convert(recipe, DyeRecipeExport::encode);
    }

    // 序列化与 CE 染色算法分开，测试无需启动 Bukkit 物品缓存。
    static List<JsonObject> convert(CustomDyeRecipe recipe, Function<Item, String> encoder) {
        int targets = recipe.target().items().size(), dyes = recipe.dye().items().size();
        // 在创建物品和调用 CE 计算之前检查预算，失败时整条配方不发布半份结果。
        if (targets == 0 || dyes == 0 || targets > 256 || dyes > 256 || (long) targets * dyes > MAX_VARIANTS)
            throw new IllegalArgumentException("Dye recipe exceeds choice/variant limit (256 choices, 1024 pairs)");
        List<Choice> targetChoices = choices(recipe.target(), encoder), dyeChoices = choices(recipe.dye(), encoder);
        List<JsonObject> result = new ArrayList<>();
        for (Choice target : targetChoices) for (Choice dye : dyeChoices) {
            // CE 的 assemble 可能修改输入；每个组合都使用独立副本，不污染其余颜色或目录原型。
            var input = CraftingInput.of(2, 1, List.of(
                    UniqueIdItem.of(target.item().copy()), UniqueIdItem.of(dye.item().copy())));
            if (!recipe.matches(input)) continue;
            Item output = recipe.assemble(input, ItemBuildContext.empty());
            if (output == null || output.isEmpty()) throw new IllegalArgumentException("Empty CE dye result");
            JsonObject value = new JsonObject();
            value.addProperty("id", variantId(recipe.id().asString(), target.id(), dye.id()));
            value.addProperty("type", "shapeless");
            value.addProperty("displayOnly", true);
            JsonArray inputs = new JsonArray();
            inputs.add(slot(target.encoded())); inputs.add(slot(dye.encoded()));
            value.add("inputs", inputs);
            JsonArray outputs = new JsonArray(); outputs.add(encoder.apply(output)); value.add("outputs", outputs);
            result.add(value);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Dye recipe has no matching single-dye example");
        return result;
    }

    private static List<Choice> choices(Ingredient ingredient, Function<Item, String> encoder) {
        List<Choice> choices = new ArrayList<>();
        for (var key : ingredient.items()) {
            Item item = Item.byId(key.key(), ItemBuildContext.empty());
            if (item == null || item.isEmpty()) throw new IllegalArgumentException("Missing dye ingredient " + key);
            item = ingredient.applyPredicateLooks(item).count(ingredient.count());
            choices.add(new Choice(key.toString(), item, encoder.apply(item)));
        }
        return choices;
    }

    private static JsonObject slot(String encoded) {
        JsonObject slot = new JsonObject(); slot.addProperty("exactInput", true);
        JsonArray choices = new JsonArray(); choices.add(encoded); slot.add("choices", choices); return slot;
    }

    private static String encode(Item item) {
        if (item == null || item.isEmpty()) throw new IllegalArgumentException("Empty dye result/ingredient");
        ItemStack stack = ItemStackUtils.getBukkitStack(item);
        if (stack == null || stack.getType().isAir()) throw new IllegalArgumentException("Empty dye stack");
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    private static String variantId(String recipe, String target, String dye) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (recipe + "\0" + target + "\0" + dye).getBytes(StandardCharsets.UTF_8));
            return "cebridge:dye/" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
