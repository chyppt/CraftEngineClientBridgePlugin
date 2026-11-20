package cc.cebridge.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.NetworkTextReplaceContext;
import net.momirealms.craftengine.core.util.AdventureHelper;
import net.momirealms.craftengine.core.util.Key;

/** 只隔离已确认发生变化的 CE API；反射查找按能力缓存，不在物品循环中反复查找。 */
public final class CraftEngineCompat {
    private CraftEngineCompat() {}

    private static final class ItemMethods {
        static final Method BY_ID = optional(Item.class, "byId", Key.class, ItemBuildContext.class);
        static final Method PREDICATE = optional(Ingredient.class, "predicate");
        static final Method LOOKS = optional(Ingredient.class, "applyPredicateLooks", Item.class);
    }

    public static Item buildItem(Key id, ItemBuildContext context) {
        if (ItemMethods.BY_ID != null) return (Item) invoke(ItemMethods.BY_ID, null, id, context);
        // 26.7 没有 Item.byId；BuildableItem 同时涵盖 CE 与原版物品。
        return CraftEngine.instance().itemManager().getBuildableItem(id)
                .map(buildable -> buildable.buildItem(context)).orElse(null);
    }

    public static Item buildItem(Key id, Player player) {
        return buildItem(id, ItemBuildContext.of(player));
    }

    public static boolean hasPredicate(Ingredient ingredient) {
        return ItemMethods.PREDICATE != null && invoke(ItemMethods.PREDICATE, ingredient) != null;
    }

    public static Item applyPredicateLooks(Ingredient ingredient, Item item) {
        return ItemMethods.LOOKS == null ? item : (Item) invoke(ItemMethods.LOOKS, ingredient, item);
    }

    public static JsonElement categoryTitle(String text, Player player) {
        return JsonParser.parseString(TextMethods.serialize(TextMethods.deserialize(text, NetworkTextReplaceContext.of(player))));
    }

    private static final class TextMethods {
        static final Method DESERIALIZE = optional(AdventureHelper.class, "deserialize", String.class, Context.class);
        static final Method MINI_MESSAGE = optional(AdventureHelper.class, "miniMessage");
        static final Method TAG_RESOLVERS = DESERIALIZE == null ? optional(Context.class, "tagResolvers") : null;
        static final Method SERIALIZE = java.util.Arrays.stream(AdventureHelper.class.getMethods())
                .filter(method -> method.getName().equals("componentToJson") && method.getParameterCount() == 1)
                .findFirst().orElseThrow(() -> new IllegalStateException("CE 缺少 componentToJson API"));
        // 早期 CE 使用重定位 Adventure MiniMessage，后期改为 CE message 包。
        static final Method LEGACY_DESERIALIZE = DESERIALIZE == null
                ? java.util.Arrays.stream(MINI_MESSAGE.getReturnType().getMethods())
                    .filter(method -> method.getName().equals("deserialize") && method.getParameterCount() == 2
                            && method.getParameterTypes()[0] == String.class && method.getParameterTypes()[1].isArray())
                    .findFirst().orElseThrow(() -> new IllegalStateException("CE 缺少 MiniMessage deserialize API")) : null;

        static Object deserialize(String text, Context context) {
            if (DESERIALIZE != null) return invoke(DESERIALIZE, null, text, context);
            if (TAG_RESOLVERS == null) throw new IllegalStateException("CE 分类文本缺少 tagResolvers API");
            Object resolvers = invoke(TAG_RESOLVERS, context);
            return invoke(LEGACY_DESERIALIZE, invoke(MINI_MESSAGE, null), text, resolvers);
        }

        static String serialize(Object component) { return (String) invoke(SERIALIZE, null, component); }
    }

    private static Method optional(Class<?> owner, String name, Class<?>... parameters) {
        try { return owner.getMethod(name, parameters); }
        catch (NoSuchMethodException absent) { return null; }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("CE API 调用失败: " + method.getName(), cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("CE API 不可访问: " + method.getName(), failure);
        }
    }
}
