package cc.cebridge.compat;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** 调用者须在玩家所属线程；拒绝跨区域或未加载区块的射线，不加载区块。 */
public final class RegionAccess {
    private RegionAccess() {}

    public static Block targetBlock(Player player, int distance) {
        var eye = player.getEyeLocation();
        var world = eye.getWorld();
        var end = eye.clone().add(eye.getDirection().multiply(distance));
        for (int x = Math.min(eye.getBlockX(), end.getBlockX()) >> 4;
             x <= (Math.max(eye.getBlockX(), end.getBlockX()) >> 4); x++) {
            for (int z = Math.min(eye.getBlockZ(), end.getBlockZ()) >> 4;
                 z <= (Math.max(eye.getBlockZ(), end.getBlockZ()) >> 4); z++) {
                if (!Schedulers.owns(world, x, z) || !world.isChunkLoaded(x, z)) return null;
            }
        }
        return player.getTargetBlockExact(distance);
    }
}
