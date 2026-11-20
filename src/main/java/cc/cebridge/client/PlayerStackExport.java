package cc.cebridge.client;

import com.google.gson.*;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;

/** 先保存 CE 身份再做面向客户端的转换，避免网络处理隐藏 custom_data 后丢失配方索引。 */
final class PlayerStackExport {
    private record Converted(String stack,String customId) {}
    private PlayerStackExport() {}
    static void convert(JsonObject root,Player player) {
        var cache=new HashMap<String,Converted>();var user=BukkitAdaptor.adapt(player);
        java.util.function.Function<String,Converted> encode=encoded->cache.computeIfAbsent(encoded,key->{
            ItemStack raw=ItemStack.deserializeBytes(Base64.getDecoder().decode(key));
            var wrapped=BukkitAdaptor.adapt(raw);
            String id=wrapped.customId().map(Object::toString).orElse(null);
            var converted=wrapped.toClientSide(user);
            return new Converted(Base64.getEncoder().encodeToString(((ItemStack)converted.platformItem()).serializeAsBytes()),id);
        });
        for(var entry:root.getAsJsonArray("items")) {
            var value=entry.getAsJsonObject();var converted=encode.apply(value.get("stack").getAsString());
            value.addProperty("stack",converted.stack());if(converted.customId()!=null) value.addProperty("customId",converted.customId());
        }
        for(var entry:root.getAsJsonArray("blocks")) {
            var value=entry.getAsJsonObject();if(!value.has("icon")) continue;
            var converted=encode.apply(value.get("icon").getAsString());value.addProperty("icon",converted.stack());
            if(converted.customId()!=null) value.addProperty("iconId",converted.customId());
        }
        for(var entry:root.getAsJsonArray("recipes")) {
            var recipe=entry.getAsJsonObject();recipe.add("outputIds",convertArray(recipe.getAsJsonArray("outputs"),encode));
            for(var input:recipe.getAsJsonArray("inputs")) {
                var slot=input.getAsJsonObject();if(slot.has("choices")) slot.add("choiceIds",convertArray(slot.getAsJsonArray("choices"),encode));
            }
        }
    }
    private static JsonArray convertArray(JsonArray array,java.util.function.Function<String,Converted> encode) {
        var ids=new JsonArray();
        for(int i=0;i<array.size();i++) {
            var converted=encode.apply(array.get(i).getAsString());array.set(i,new JsonPrimitive(converted.stack()));
            ids.add(converted.customId()==null?JsonNull.INSTANCE:new JsonPrimitive(converted.customId()));
        }
        return ids;
    }
}
