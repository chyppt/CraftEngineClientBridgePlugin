package cc.cebridge.command;

import cc.cebridge.CraftEngineBridgePlugin;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class BridgeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS =
            List.of("reload", "info", "status", "rescan", "purge", "refresh", "recipes");

    private final CraftEngineBridgePlugin plugin;

    public BridgeCommand(CraftEngineBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                plugin.reloadAll(() -> sender.sendRichMessage("<green>配置已重载，已索引 "
                        + plugin.registry().blockCount() + " 个 CE 方块。</green>"));
            }
            case "rescan" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendRichMessage("<red>该子命令仅玩家可用。</red>");
                    return true;
                }
                int radius = parseRadius(args);
                sender.sendRichMessage("<gray>开始维护半径 " + radius + " 内的旧载体（仅已加载区块）…</gray>");
                plugin.carriers().rescanMissing(player, radius, count ->
                        sender.sendRichMessage("<green>维护完成，清理 " + count + " 个旧载体；正在观察的方块会自动补齐。</green>"));
            }
            case "purge" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendRichMessage("<red>该子命令仅玩家可用。</red>");
                    return true;
                }
                int radius = parseRadius(args);
                plugin.carriers().purgeAround(player, radius, removed ->
                        sender.sendRichMessage("<green>清理完成，移除失效载体 " + removed + " 个。</green>"));
            }
            case "refresh" -> {
                plugin.recipeBridge().requestRefresh(true);
                plugin.clientBridge().requestRefresh();
                sender.sendMessage("已请求重建 CE 物品索引和配方快照；/cebridge recipes 查看导出结果。");
                if (!(sender instanceof Player player)) {
                    sender.sendRichMessage("<red>该子命令仅玩家可用。</red>");
                    return true;
                }
                int radius = parseRadius(args);
                plugin.carriers().rescanMissing(player, radius, count ->
                        sender.sendRichMessage("<green>刷新完成：清理 " + count + " 个旧载体，正在观察的方块会自动更新。</green>"));
            }
            case "recipes" -> {
                sender.sendMessage("可选客户端模组：" + plugin.clientBridge().connectedPlayers() + " 位玩家已协商；无需 LeavesX 协议。");
                sender.sendMessage("LeavesX 配方重载联动：" + plugin.settings().leavesXRecipeSync());
                for (var status : plugin.recipeBridge().status()) {
                    sender.sendMessage(status.protocol() + "：" + status.detail());
                    sender.sendMessage("核心配置：" + status.setting());
                }
                sender.sendMessage("LeavesX SPI：" + (plugin.recipeBridge().spiRegistered() ? "已注册（不代表客户端收到配方）" : "未注册，仅旧核心重载通知"));
                sender.sendMessage("快照状态：" + plugin.recipeBridge().lastError());
                var snapshot = plugin.recipeBridge().lastSnapshot();
                if (snapshot != null) {
                    sender.sendMessage("导出：items=" + snapshot.items() + ", recipes=" + snapshot.recipes()
                            + ", skipped=" + snapshot.skipped() + ", failed=" + snapshot.failed());
                    sender.sendMessage("类型：" + snapshot.types());
                    if (!snapshot.problemCounts().isEmpty()) sender.sendMessage("问题统计：" + snapshot.problemCounts());
                    snapshot.problems().forEach(sender::sendMessage);
                }
                sender.sendMessage("服务端：" + plugin.getServer().getVersion() + "；桥接插件：" + plugin.getPluginMeta().getVersion());
                if (sender instanceof Player player) {
                    var channels = player.getListeningPluginChannels();
                    sender.sendMessage("客户端注册通道：REI=" + channels.contains("roughlyenoughitems:sync_displays")
                            + "，Fabric recipes=" + channels.contains("fabric:recipe_sync")
                            + "，NeoForge recipes=" + channels.contains("neoforge:recipe_content"));
                }
                sender.sendMessage("物品侧栏目录仍由客户端注册表决定；CE 精确原料组件在 JEI vanilla codec 中有限制。");
            }
            case "info" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendRichMessage("<red>该子命令仅玩家可用。</red>");
                    return true;
                }
                sendBlockInfo(player);
            }
            default -> {
                sender.sendRichMessage("<gray>已索引 CE 方块: <white>" + plugin.registry().blockCount());
                sender.sendRichMessage("<gray>载体: <white>" + plugin.settings().carriers()
                        + " <gray>| 自动命名: <white>" + plugin.settings().autoName()
                        + " <gray>| LeavesX 配方重载联动: <white>" + plugin.settings().leavesXRecipeSync());
                sender.sendRichMessage("<gray>子命令: <white>reload, info, status, recipes, rescan [半径], purge [半径], refresh [半径]");
            }
        }
        return true;
    }

    private void sendBlockInfo(Player player) {
        Block target = player.getTargetBlockExact(8);
        if (target == null) {
            player.sendRichMessage("<red>视线内没有方块（距离 8 格内）。</red>");
            return;
        }
        if (!CraftEngineBlocks.isCustomBlock(target)) {
            player.sendRichMessage("<yellow>目标不是 CE 自定义方块: " + target.getType() + "</yellow>");
            return;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(target);
        if (state == null) {
            player.sendRichMessage("<red>无法读取 CE 方块状态。</red>");
            return;
        }
        Key blockId = state.owner().value().id();
        player.sendRichMessage("<gray>方块 ID: <white>" + blockId.asString());
        var cropItem = plugin.registry().cropDisplayItems().get(blockId);
        String configuredItem = plugin.getConfig().getString("jade-display-items." + blockId.asString());
        if (configuredItem != null || cropItem != null) {
            player.sendMessage("作物展示物品：" + (configuredItem != null ? configuredItem : cropItem.asString()));
        }
        var def = plugin.registry().itemForBlock(blockId);
        player.sendRichMessage("<gray>关联物品: <white>" + (def == null ? "无（未配置物品反查）" : def.id().asString()));
        ItemDisplay carrier = plugin.carriers().carrierAt(target.getLocation());
        if (carrier == null) {
            player.sendRichMessage("<gray>载体: <red>缺失，可用 /cebridge rescan 补齐</red>");
            return;
        }
        player.sendRichMessage("<gray>载体: <green>存在</green> <dark_gray>(" + carrier.getUniqueId() + ")</dark_gray>");
        ItemStack item = carrier.getItemStack();
        if (item == null || item.isEmpty()) {
            player.sendRichMessage("<gray>载体物品: <red>空！Jade 将无法识别</red>");
            return;
        }
        player.sendRichMessage("<gray>载体物品: <white>" + item.getType() + " ×" + item.getAmount());
        if (item.hasData(DataComponentTypes.ITEM_NAME)) {
            Component name = item.getData(DataComponentTypes.ITEM_NAME);
            String text = name == null ? "?" : PlainTextComponentSerializer.plainText().serialize(name);
            player.sendRichMessage("<gray>item_name: <green>✔</green> <white>" + text);
        } else {
            player.sendRichMessage("<gray>item_name: <red>✘ 缺失（Jade 会显示原版名）</red>");
        }
        if (item.hasData(DataComponentTypes.ITEM_MODEL)) {
            net.kyori.adventure.key.Key model = item.getData(DataComponentTypes.ITEM_MODEL);
            player.sendRichMessage("<gray>item_model: <green>✔</green> <white>" + (model == null ? "?" : model.asString()));
        } else {
            player.sendRichMessage("<gray>item_model: <yellow>未设置（沿用 CE 物品定义）</yellow>");
        }
        player.sendRichMessage("<gray>Jade: <yellow>服务端载体存在，客户端跟踪及 HUD 显示需另行验证</yellow>");
    }

    private static int parseRadius(String[] args) {
        if (args.length >= 2) {
            try {
                return Math.max(1, Math.min(48, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
            }
        }
        return 16;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("rescan")
                || args[0].equalsIgnoreCase("purge")
                || args[0].equalsIgnoreCase("refresh"))) {
            return List.of("8", "16", "24", "32");
        }
        return List.of();
    }
}
