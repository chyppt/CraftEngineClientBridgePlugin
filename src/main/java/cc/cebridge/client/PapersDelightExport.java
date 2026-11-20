package cc.cebridge.client;

import cc.cebridge.CraftEngineBridgePlugin;
import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** 可选 PD 运行时适配；只在目录刷新时读取配方，不扫描世界或加载区块。 */
final class PapersDelightExport {
    private final ClassLoader loader;
    private final Method createItem;
    private final Object resolver;
    private final Map<String,List<String>> choices = new HashMap<>();
    private final List<ItemStack> candidates = new ArrayList<>();

    private PapersDelightExport(ClassLoader loader, JsonObject root) throws ReflectiveOperationException {
        this.loader = loader;
        createItem = type("ce.CraftEngineUtil").getMethod("createItem", String.class, int.class);
        resolver = resolver(loader);
        // 复用 CE 已构建的目录；每种标签只解析一次，使用 PD 自己的匹配语义。
        for (var entry : root.getAsJsonArray("items"))
            candidates.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.getAsJsonObject().get("stack").getAsString())));
        for (Material material : Material.values()) if (material.isItem() && !material.isAir()) candidates.add(new ItemStack(material));
    }

    // 用于脱离 Bukkit 世界验证 PD 结构转换，生产入口仍从已启用的插件读取。
    PapersDelightExport(ClassLoader loader, Method createItem, Object resolver, List<ItemStack> candidates) {
        this.loader=loader;this.createItem=createItem;this.resolver=resolver;this.candidates.addAll(candidates);
    }

    static void append(CraftEngineBridgePlugin plugin, JsonObject root) {
        var pd = plugin.getServer().getPluginManager().getPlugin("PapersDelight");
        if (pd == null || !pd.isEnabled()) return;
        try {
            var exporter = new PapersDelightExport(pd.getClass().getClassLoader(), root);
            Object instance = exporter.type("PapersDelight").getField("INSTANCE").get(null);
            int cutting = 0, cooking = 0, failed = 0;
            var recipes = root.getAsJsonArray("recipes");
            for (String kind : List.of("cutting", "cooking_pot")) {
                List<?> active;
                try {
                    // 付费版将锅管理器隐藏为 Object；其 RecipeManager 快照是静态共享的。
                    Object manager = kind.equals("cutting")
                        ? manager(instance,exporter.type("mechanic.cutting.CuttingBoardManager"))
                        : exporter.type("recipe.RecipeManager").getConstructor().newInstance();
                    if (manager == null) throw new IllegalStateException("PD manager is not ready: " + kind);
                    active = List.copyOf((List<?>) call(manager,kind.equals("cutting") ? "getRecipes" : "recipes"));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    failed++;
                    plugin.getLogger().warning("PD " + kind + " 管理器读取失败，其他类别继续导出: " + error);
                    continue;
                }
                for (int i = 0; i < active.size(); i++) {
                    try {
                        if (recipes.size() >= 10_000) throw new IllegalArgumentException("Client recipe catalog limit reached");
                        recipes.add(exporter.convert(active.get(i), kind, i));
                        if (kind.equals("cutting")) cutting++; else cooking++;
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        if (++failed <= 5) plugin.getLogger().warning("PD " + kind + " 配方导出失败: " + error);
                    }
                }
            }
            plugin.getLogger().info("PD 客户端配方：砧板=" + cutting + ", 锅=" + cooking + ", failed=" + failed);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            plugin.getLogger().warning("PD 配方适配不可用，保留 CE 配方：" + error);
        }
    }

    JsonObject convert(Object recipe, String kind, int index) throws ReflectiveOperationException {
        var out = new JsonObject();
        String source = Objects.toString(kind.equals("cutting") ? callEither(recipe,"source","DEOBF_ai") : publicFieldEither(recipe,"source","DEOBF_b"), "");
        out.addProperty("id", "cebridge:pd/" + kind + "/" + digest(source + "|" + index));
        out.addProperty("type", kind);
        out.addProperty("displayOnly", true);
        var inputs = new JsonArray(); var outputs = new JsonArray(); var chances = new JsonArray();
        if (kind.equals("cutting")) {
            inputs.add(slot((List<?>) call(callEither(recipe,"inputMatcher","DEOBF_aj"),"expressions"), "ingredient"));
            var tools = (List<?>) call(callEither(recipe,"toolsMatcher","DEOBF_ak"),"expressions");
            if (!tools.isEmpty()) inputs.add(slot(tools, "tool"));
            for (Object result : (List<?>) call(recipe,"results")) {
                double chance = ((Number) call(result,"chance")).doubleValue();
                if (!Double.isFinite(chance) || chance <= 0) continue;
                outputs.add(stack((String) call(result,"item"), ((Number) call(result,"count")).intValue()));
                chances.add(chance);
            }
        } else {
            for (Object ingredient : (List<?>) publicField(recipe,"ingredients")) {
                List<?> expressions;
                try { expressions = (List<?>) call(ingredient,"displayExpressions"); }
                catch (NoSuchMethodException older) { expressions = (List<?>) call(call(ingredient,"matcher"),"expressions"); }
                inputs.add(slot(expressions, "ingredient", ingredientCount(ingredient)));
            }
            String container = (String) publicField(recipe,"container");
            // 与 PD 出锅逻辑一致：未显式指定时使用 CE 食物的合成剩余物。
            // 付费版同名同参的方法可能只以返回类型区分，不能使用 getMethod 随机选择。
            if (container == null || container.isBlank()) container = (String) remainderMethod(type("ce.CraftEngineUtil"))
                .invoke(null,(String) publicField(recipe,"result"));
            if (container != null && !container.isBlank()) inputs.add(slot(List.of(container), "container"));
            outputs.add(stack((String) publicField(recipe,"result"), ((Number) publicField(recipe,"resultCount")).intValue()));
            chances.add(1.0);
            out.addProperty("cookingTimeTicks", ((Number) publicField(recipe,"cookingTime")).intValue());
        }
        if (inputs.isEmpty() || inputs.size() > 9 || outputs.isEmpty() || outputs.size() > 9)
            throw new IllegalArgumentException("Unsupported PD slot count");
        out.add("inputs",inputs); out.add("outputs",outputs); out.add("outputChances",chances);
        return out;
    }

    private JsonObject slot(List<?> expressions, String role) throws ReflectiveOperationException {
        return slot(expressions,role,1);
    }
    private JsonObject slot(List<?> expressions, String role, int count) throws ReflectiveOperationException {
        var encoded = new LinkedHashSet<String>();
        for (Object expression : expressions) {
            String key = (String) expression;
            var cached = choices.get(key);
            if (cached == null) {
                var resolved = new ArrayList<String>();
                if (key.startsWith("#") || key.startsWith("advtag:")) {
                    boolean advanced = key.startsWith("advtag:");
                    Method match = tagMethod(resolver.getClass(),advanced);
                    String tag = key.substring(advanced ? 7 : 1);
                    for (ItemStack candidate : candidates) if (Boolean.TRUE.equals(match.invoke(resolver,candidate,tag))) {
                        resolved.add(encode(candidate));
                        if (resolved.size() > 256) throw new IllegalArgumentException("Too many PD tag choices: " + key);
                    }
                } else resolved.add(stack(key,1));
                cached = List.copyOf(resolved); choices.put(key,cached);
            }
            encoded.addAll(cached);
        }
        if (encoded.isEmpty() || encoded.size() > 256) throw new IllegalArgumentException("Unresolved PD ingredient: " + expressions);
        var slot = new JsonObject(); var array = new JsonArray();
        for (String value : encoded) {
            if (count == 1) array.add(value);
            else {
                ItemStack stack = ItemStack.deserializeBytes(Base64.getDecoder().decode(value));
                stack.setAmount(count); array.add(encode(stack));
            }
        }
        slot.add("choices",array); slot.addProperty("role",role); slot.addProperty("exactInput",true);
        return slot;
    }

    private String stack(String id, int count) throws ReflectiveOperationException {
        var stack = (ItemStack) createItem.invoke(null,id,count);
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Missing PD item: " + id);
        return encode(stack);
    }
    /** 两个已核对的 PD 布局；不扫描 JAR，也不依赖混淆后易变化的管理器字段名。 */
    static Object resolver(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type;
        try { type = Class.forName("dev.tako.papersdelight.recipe.DefaultItemMatcherResolver",true,loader); }
        catch (ClassNotFoundException paid) {
            type = Class.forName("dev.tako.papersdelight.recipe.DEOBF_c",true,loader);
        }
        // 官方构建提供静态 resolver；混淆/收缩可改变构造器可见性，因此优先复用实例。
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && type.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object instance = field.get(null);
                if (instance != null) return instance;
            }
        }
        // 兼容没有静态实例的旧版，允许其非公开无参构造器。
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
    static Method tagMethod(Class<?> type, boolean advanced) throws NoSuchMethodException {
        String name = advanced ? "matchesAdvancedTag" : "matchesTag";
        try { return type.getMethod(name,ItemStack.class,String.class); }
        catch (NoSuchMethodException paid) {
            // 付费版保留泛型接口的桥接方法，参数擦除为 Object。
            return type.getMethod(name,Object.class,String.class);
        }
    }
    static Object manager(Object instance, Class<?> type) throws ReflectiveOperationException {
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                field.setAccessible(true); return field.get(instance);
            }
        }
        throw new NoSuchFieldException("PD manager: " + type.getName());
    }
    private static Object callEither(Object object,String normal,String paid) throws ReflectiveOperationException {
        try { return call(object,normal); }
        catch (NoSuchMethodException missing) { return call(object,paid); }
    }
    private static Object publicFieldEither(Object object,String normal,String paid) throws ReflectiveOperationException {
        try { return publicField(object,normal); }
        catch (NoSuchFieldException missing) { return publicField(object,paid); }
    }
    static Method remainderMethod(Class<?> type) throws NoSuchMethodException {
        for (String name : List.of("getCraftRemainderId","DEOBF_a"))
            for (Method method : type.getMethods())
                if (method.getName().equals(name) && method.getReturnType() == String.class
                        && Modifier.isStatic(method.getModifiers())
                        && Arrays.equals(method.getParameterTypes(),new Class<?>[]{String.class})) return method;
        throw new NoSuchMethodException("PD craft remainder method");
    }
    private static int ingredientCount(Object ingredient) throws ReflectiveOperationException {
        int count;
        try { count = ((Number)call(ingredient,"count")).intValue(); }
        catch (NoSuchMethodException older) { return 1; }
        if (count < 1 || count > 99) throw new IllegalArgumentException("Invalid PD ingredient count: " + count);
        return count;
    }
    private static String encode(ItemStack stack) { return Base64.getEncoder().encodeToString(stack.serializeAsBytes()); }
    private Class<?> type(String suffix) throws ClassNotFoundException { return Class.forName("dev.tako.papersdelight." + suffix,true,loader); }
    private static Object call(Object object, String name) throws ReflectiveOperationException { return object.getClass().getMethod(name).invoke(object); }
    private static Object publicField(Object object, String name) throws ReflectiveOperationException { return object.getClass().getField(name).get(object); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

}
