package cc.cebridge.command;

import cc.cebridge.CraftEngineBridgePlugin;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class BridgeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS =
            List.of("reload", "info", "status", "recipes");

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
                plugin.reloadAll(() -> reply(sender, "配置已重载，已索引 "
                        + plugin.registry().blockCount() + " 个 CE 方块。"));
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
                sender.sendMessage("服务端：" + plugin.getServer().getVersion() + "；桥接插件：" + plugin.getDescription().getVersion());
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
                sender.sendRichMessage("<gray>客户端模组已连接: <white>" + plugin.clientBridge().connectedPlayers()
                        + " <gray>| 自动命名: <white>" + plugin.settings().autoName()
                        + " <gray>| LeavesX 配方重载联动: <white>" + plugin.settings().leavesXRecipeSync());
                sender.sendRichMessage("<gray>子命令: <white>reload, info, status, recipes");
            }
        }
        return true;
    }

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            cc.cebridge.compat.Schedulers.entity(player).run(plugin, task -> player.sendMessage(message), null);
        } else {
            cc.cebridge.compat.Schedulers.global().run(plugin, task -> sender.sendMessage(message));
        }
    }

    private void sendBlockInfo(Player player) {
        Block target = cc.cebridge.compat.RegionAccess.targetBlock(player, 8);
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
        player.sendMessage("客户端识别与显示由配套模组处理。已协商玩家：" + plugin.clientBridge().connectedPlayers());
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
