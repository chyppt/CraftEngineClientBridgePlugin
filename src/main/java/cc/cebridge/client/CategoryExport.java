package cc.cebridge.client;

import cc.cebridge.compat.CraftEngineCompat;

import com.google.gson.*;
import java.util.*;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.context.PlayerOptionalContext;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.AdventureHelper;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.plugin.context.NetworkTextReplaceContext;
import org.bukkit.entity.Player;

/** 在玩家所属线程计算条件，保留 CE 首页顺序；只将子分类成员扁平化。 */
final class CategoryExport {
    private CategoryExport() {}
    static JsonArray forPlayer(Player player, Set<String> available) {
        var manager=CraftEngine.instance().itemBrowserManager();
        var user=BukkitAdaptor.adapt(player);
        var context=PlayerOptionalContext.of(user);
        var result=new JsonArray();
        var permitted=new HashMap<String,Boolean>();
        java.util.function.Predicate<String> allowed=id->permitted.computeIfAbsent(id,key->
            manager.byId(Key.of(key)).map(c->c.condition().test(context)).orElse(false));
        for(Category root: manager.categories()) {
            if(isAggregate(root.id().asString())) continue;
            if(root.hidden() || !allowed.test(root.id().asString())) continue;
            if(result.size() >= 128) throw new IllegalArgumentException("CE 一级分类超过客户端 128 标签槽");
            var members=cc.cebridge.wire.CategoryMembers.flatten(root.id().asString(),
                id->manager.byId(Key.of(id)).map(Category::members).orElse(List.of()),
                allowed,available::contains);
            if(members.isEmpty()) continue;
            var category=new JsonObject(); category.addProperty("id",root.id().asString());
            category.add("title",CraftEngineCompat.categoryTitle(root.displayName(), user));
            Item icon=CraftEngineCompat.buildItem(root.icon(),user);
            if(icon!=null && !icon.isEmpty()) category.addProperty("icon",Base64.getEncoder().encodeToString(
                    ItemStackUtils.getBukkitStack(icon.toClientSide(user)).serializeAsBytes()));
            var items=new JsonArray(); members.forEach(items::add); category.add("items",items); result.add(category);
        }
        return result;
    }
    private static boolean isAggregate(String id) {
        // 仅排除 CE 内置综合分类；其他内容包可能把真正一级分类也命名为 all。
        return id.equals("internal:all");
    }
}
