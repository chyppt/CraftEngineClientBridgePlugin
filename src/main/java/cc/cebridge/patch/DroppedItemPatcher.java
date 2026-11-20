package cc.cebridge.patch;

import cc.cebridge.CraftEngineBridgePlugin;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 掉落物修补：Jade 显示掉落物名字用的是物品悬浮名（vanilla 同步），
 * CE 物品若未配置 item_name 则会显示基础物品名（如"线"）。
 * 本监听在 CE 自定义物品落地且缺少 item_name 时补上兜底显示名。
 */
public final class DroppedItemPatcher implements Listener {

    private final CraftEngineBridgePlugin plugin;

    public DroppedItemPatcher(CraftEngineBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Item itemEntity)) return;
        ItemStack stack = itemEntity.getItemStack();
        if (stack.isEmpty() || stack.hasData(DataComponentTypes.ITEM_NAME)) return;
        Key itemId = CraftEngineItems.getCustomItemId(stack);
        if (itemId == null) return;
        Component name = plugin.registry().displayNameForItem(itemId);
        if (name == null) return;
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        itemEntity.setItemStack(stack);
    }
}
