package cc.cebridge.client;

import cc.cebridge.CraftEngineBridgePlugin;
import cc.cebridge.recipe.RecipeSnapshotBuilder;
import com.google.gson.*;
import java.util.*;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

final class ClientCatalogExport {
    private ClientCatalogExport() {}
    static JsonObject build(CraftEngineBridgePlugin plugin) {
        var snapshot=RecipeSnapshotBuilder.build();
        JsonObject root=JsonParser.parseString(snapshot.json()).getAsJsonObject();
        PapersDelightExport.append(plugin,root);
        root.addProperty("bridgeProtocol",1);
        root.addProperty("targetHud",true);
        root.addProperty("minecraft","26.1.2");
        root.addProperty("vanillaStates",CraftEngine.instance().blockManager().vanillaBlockStateCount());
        var blocks=new JsonArray();
        int coffeeGrowthStates=0;
        for(var block:CraftEngineBlocks.loadedBlocks().values()) {
            ItemStack icon=plugin.registry().buildCarrierItem(block.defaultState());
            String encodedIcon=icon!=null && !icon.isEmpty()?Base64.getEncoder().encodeToString(icon.serializeAsBytes()):null;
            var behavior = block.defaultState().behavior();
            var crop = behavior.getFirst(net.momirealms.craftengine.bukkit.block.behavior.CropBlockBehavior.class);
            var stem = behavior.getFirst(net.momirealms.craftengine.bukkit.block.behavior.StemBlockBehavior.class);
            // 使用实际行为的属性，支持 CE 配置重命名及组合行为；高秆作物的 age 是周期计时，不是成熟度。
            var age = crop != null ? crop.ageProperty : stem != null ? stem.ageProperty : null;
            boolean coffeeCrop=block.id().asString().equals("rusticdelight:coffee_crop");
            if (age == null && (coffeeCrop || block.defaultState().settings().tags().contains(net.momirealms.craftengine.core.util.Key.of("minecraft:crops")))
                    && behavior.getFirst(net.momirealms.craftengine.bukkit.block.behavior.VerticalCropBlockBehavior.class) == null
                    && behavior.getFirst(net.momirealms.craftengine.bukkit.block.behavior.VineCropHeadBlockBehavior.class) == null) {
                var property = block.defaultState().<Integer>getProperty("age");
                if (property instanceof net.momirealms.craftengine.core.block.property.IntegerProperty integer) age = integer;
            }
            for(var state:block.variantProvider().states()) {
                var value=new JsonObject(); value.addProperty("stateId",state.customBlockState().registryId());
                value.addProperty("visualStateId",state.visualBlockState().registryId());
                value.addProperty("id",block.id().asString());
                value.addProperty("translation",block.translationKey());
                // Jade 不能用 barrier/tripwire 等视觉载体的硬度判断 CE 方块能否破坏。
                value.addProperty("hardness",state.settings().hardness());
                value.addProperty("requiresCorrectTool",state.settings().requireCorrectTool());
                var mineableTags=new JsonArray();
                state.settings().tags().stream()
                        .filter(tag->tag.namespace().equals("minecraft") && tag.value().startsWith("mineable/"))
                        .map(tag->tag.namespace()+":"+tag.value()).sorted().forEach(mineableTags::add);
                value.add("mineableTags",mineableTags);
                if(encodedIcon!=null) value.addProperty("icon",encodedIcon);
                // 状态目录携带进度，客户端收到正常方块更新后即可本地查表，无需查询世界。
                if (age != null && age.max > age.min) {
                    value.addProperty("growth", (state.get(age) - (float) age.min) / (age.max - (float) age.min));
                    if (coffeeCrop) coffeeGrowthStates++;
                }
                blocks.add(value);
            }
        }
        root.add("blocks",blocks);
        plugin.getLogger().info("客户端目录：items="+snapshot.items()+", recipes="+root.getAsJsonArray("recipes").size()
                +", states="+blocks.size()+", skipped="+snapshot.skipped()+", failed="+snapshot.failed());
        plugin.getLogger().info("咖啡作物生长状态：rusticdelight:coffee_crop="+coffeeGrowthStates+" 个 age 状态");
        if(!snapshot.problems().isEmpty()) plugin.getLogger().warning("客户端目录问题：原因统计="+snapshot.problemCounts()+"；样本（最多12条）="+snapshot.problems());
        return root;
    }
}
