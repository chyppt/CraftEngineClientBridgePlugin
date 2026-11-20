package cc.cebridge.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.bukkit.item.recipe.BukkitRecipeManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.*;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;

/** LeavesX 与可选客户端共用转换器，按接收方过滤原始配方 ID。 */
public final class RecipeSnapshotBuilder {
    private RecipeSnapshotBuilder() {}

    public record Result(String json, int items, int recipes, int skipped, int failed,
                         Map<String, Integer> types, Map<String, Integer> problemCounts, List<String> problems) { }

    public static Result build() { return build(false); }

    /** 核心已同步原版配方；必须在染色/饰纹展开及 ID 重写之前过滤。 */
    public static Result buildForLeavesX() { return build(true); }

    static boolean includeRecipe(Key originalId, boolean leavesX) {
        return !leavesX || !originalId.isMinecraftNamespace();
    }

    private static Result build(boolean leavesX) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("source", "craftengine:content");
        JsonArray items = new JsonArray();
        List<String> problems = new ArrayList<>();
        Map<String, Integer> types = new LinkedHashMap<>();
        Map<String, Integer> problemCounts = new LinkedHashMap<>();
        int failed = 0;
        int skipped = 0;
        for (var entry : CraftEngineItems.loadedItems().entrySet()) {
            if (!(entry.getValue() instanceof BukkitItemDefinition bukkit)) continue;
            try {
                JsonObject item = new JsonObject();
                item.addProperty("id", entry.getKey().asString());
                item.addProperty("stack", encode(bukkit.buildBukkitItem()));
                items.add(item);
            } catch (RuntimeException failure) {
                failed++;
                problemCounts.merge("item: " + failure.getClass().getSimpleName(), 1, Integer::sum);
                problem(problems, "item " + entry.getKey(), failure.toString());
            }
        }
        root.add("items", items);
        JsonArray recipes = new JsonArray();
        for (RecipeType type : RecipeType.values()) {
            for (Recipe recipe : BukkitRecipeManager.instance().recipesByType(type)) {
                if (!includeRecipe(recipe.id(), leavesX)) continue;
                try {
                    // 染色结果依赖输入；保持原 schema，以有界的单染料示例导出为无序展示配方。
                    if (recipe instanceof CustomDyeRecipe dye) {
                        var variants = DyeRecipeExport.convert(dye);
                        if (recipes.size() + variants.size() > 10_000) {
                            throw new IllegalArgumentException("Recipe catalog exceeds 10000 entries");
                        }
                        for (JsonObject variant : variants) recipes.add(variant);
                        types.merge("shapeless", variants.size(), Integer::sum);
                        continue;
                    }
                    if (recipe instanceof CustomSmithingTrimRecipe trim) {
                        var variants=TrimRecipeExport.convert(trim);
                        if(recipes.size()+variants.size()>10_000) throw new IllegalArgumentException("Recipe catalog exceeds 10000 entries");
                        variants.forEach(recipes::add);types.merge("smithing",variants.size(),Integer::sum);continue;
                    }
                    JsonObject converted = convert(recipe);
                    if (converted == null) {
                        skipped++;
                        problemCounts.merge("unsupported " + recipe.getClass().getSimpleName(), 1, Integer::sum);
                        problem(problems, "recipe " + recipe.id(), "unsupported " + recipe.getClass().getSimpleName());
                    } else {
                        recipes.add(converted);
                        types.merge(converted.get("type").getAsString(), 1, Integer::sum);
                    }
                } catch (RuntimeException failure) {
                    failed++;
                    problemCounts.merge(recipe.getClass().getSimpleName() + ": " + failure.getClass().getSimpleName(), 1, Integer::sum);
                    problem(problems, "recipe " + recipe.id(), failure.toString());
                }
            }
        }
        root.add("recipes", recipes);
        return new Result(root.toString(), items.size(), recipes.size(), skipped, failed,
                Map.copyOf(types), Map.copyOf(problemCounts), List.copyOf(problems));
    }

    private static void problem(List<String> problems, String id, String reason) {
        // 保留有界样本定位失败对象；不按每个物品刷屏，也不在 tick 路径导出快照。
        if (problems.size() < 12) problems.add(id + ": " + reason);
    }

    private static JsonObject convert(Recipe recipe) {
        if (!(recipe instanceof FixedResultRecipe fixed)) return null;
        ItemStack result = ItemStackUtils.getBukkitStack(recipe instanceof VisualResultRecipe visual
                ? visual.buildVisualOrActualResult(ItemBuildContext.empty())
                : fixed.result().buildItem(ItemBuildContext.empty()));
        if (result == null || result.getType().isAir()) return null;
        JsonObject out = new JsonObject();
        out.addProperty("id", recipe.id().asString());
        out.add("outputs", stackArray(result));
        JsonArray inputs = new JsonArray();
        String type;
        if (recipe instanceof CustomShapedRecipe shaped) {
            type = "shaped";
            out.addProperty("width", shaped.parsedPattern().width());
            out.addProperty("height", shaped.parsedPattern().height());
            Optional<Ingredient>[] pattern = shaped.parsedPattern().ingredients();
            for (int slotIndex = 0; slotIndex < pattern.length; slotIndex++) {
                Optional<Ingredient> optional = pattern[slotIndex];
                if (optional.isEmpty()) continue;
                JsonObject slot = new JsonObject();
                slot.addProperty("slot", slotIndex);
                addChoices(slot, optional.get());
                inputs.add(slot);
            }
        } else if (recipe instanceof CustomShapelessRecipe shapeless) {
            type = "shapeless";
            for (Ingredient ingredient : shapeless.placementInfo().ingredients()) {
                JsonObject slot = new JsonObject(); addChoices(slot, ingredient); inputs.add(slot);
            }
        } else if (recipe instanceof CustomStoneCuttingRecipe stone) {
            type = "stonecutting"; JsonObject slot = new JsonObject(); addChoices(slot, stone.ingredient()); inputs.add(slot);
        } else if (recipe instanceof CustomCookingRecipe cooking) {
            type = recipeType(recipe); JsonObject slot = new JsonObject(); addChoices(slot, cooking.ingredient()); inputs.add(slot);
            out.addProperty("cookingTimeTicks", cooking.cookingTime()); out.addProperty("experience", cooking.experience());
        } else if (recipe instanceof CustomSmithingTransformRecipe smithing) {
            type = "smithing";
            addSmithingSlot(inputs, 0, smithing.template());
            addSmithingSlot(inputs, 1, smithing.base());
            addSmithingSlot(inputs, 2, smithing.addition());
        } else return null;
        if (inputs.size() == 0) return null;
        out.addProperty("type", type); out.add("inputs", inputs); out.addProperty("displayOnly", true);
        return out;
    }

    private static String recipeType(Recipe recipe) {
        String name = recipe.getClass().getSimpleName().toLowerCase();
        if (name.contains("blasting")) return "blasting";
        if (name.contains("smoking")) return "smoking";
        if (name.contains("campfire")) return "campfire";
        return "smelting";
    }

    private static void addSmithingSlot(JsonArray inputs, int slotIndex, Ingredient ingredient) {
        JsonObject slot = new JsonObject();
        slot.addProperty("slot", slotIndex);
        if (ingredient == null) slot.addProperty("empty", true);
        else addChoices(slot, ingredient);
        inputs.add(slot);
    }

    private static void addChoices(JsonObject slot, Ingredient ingredient) {
        if (ingredient.items().isEmpty() || ingredient.items().size() > 256) {
            throw new IllegalArgumentException("Unsupported ingredient choice count");
        }
        JsonArray choices = new JsonArray();
        slot.addProperty("exactInput", ingredient.hasCustomItem() || ingredient.predicate() != null || ingredient.count() != 1);
        for (UniqueKey key : ingredient.items()) {
            net.momirealms.craftengine.core.item.Item built = net.momirealms.craftengine.core.item.Item.byId(key.key(), ItemBuildContext.empty());
            if (built == null) throw new IllegalArgumentException("Missing ingredient " + key);
            built = ingredient.applyPredicateLooks(built);
            ItemStack stack = ItemStackUtils.getBukkitStack(built);
            if (stack == null || stack.getType().isAir()) {
                throw new IllegalArgumentException("Empty ingredient " + key);
            }
            stack.setAmount(ingredient.count());
            choices.add(encode(stack));
        }
        slot.add("choices", choices);
    }

    private static ItemStack item(Key id, int count) {
        BukkitItemDefinition custom = CraftEngineItems.byId(id);
        if (custom != null) return custom.buildBukkitItem(ItemBuildContext.empty(), count);
        Material material = Material.matchMaterial(id.value());
        return material == null ? null : new ItemStack(material, count);
    }

    private static JsonArray stackArray(ItemStack stack) { JsonArray a = new JsonArray(); a.add(encode(stack)); return a; }
    private static String encode(ItemStack stack) { return Base64.getEncoder().encodeToString(stack.serializeAsBytes()); }
}
