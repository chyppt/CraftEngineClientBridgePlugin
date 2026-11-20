package cc.cebridge.compat;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Folia 使用所属区域调度；旧 Paper 回退主线程。反射入口仅解析一次。 */
public final class Schedulers {
    private static final boolean FOLIA = hasFolia();
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();
    private Schedulers() {}

    private static boolean hasFolia() {
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); return true; }
        catch (ClassNotFoundException absent) { return false; }
    }

    private static Object invoke(String owner, Object receiver, String name, Class<?>[] types, Object... args) {
        try {
            String key = owner + "#" + name + java.util.Arrays.toString(types);
            Method method = METHODS.computeIfAbsent(key, ignored -> {
                try { return Class.forName(owner).getMethod(name, types); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
            return method.invoke(receiver, args);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Folia scheduling failed: " + name, failure); }
    }

    public static Dispatch global() { return new Dispatch(null, false); }
    public static Dispatch entity(Player player) { return new Dispatch(player, false); }
    public static Dispatch async() { return new Dispatch(null, true); }

    public static boolean owns(World world, int x, int z) {
        return !FOLIA || (boolean) invoke("org.bukkit.Bukkit", null, "isOwnedByCurrentRegion",
                new Class<?>[]{World.class, int.class, int.class}, world, x, z);
    }

    public static void region(Plugin plugin, World world, int x, int z, Consumer<Object> action) {
        if (!FOLIA) { Bukkit.getScheduler().runTask(plugin, () -> action.accept(null)); return; }
        Object scheduler = invoke("org.bukkit.Bukkit", null, "getRegionScheduler", new Class<?>[0]);
        invoke("io.papermc.paper.threadedregions.scheduler.RegionScheduler", scheduler, "run",
                new Class<?>[]{Plugin.class, World.class, int.class, int.class, Consumer.class}, plugin, world, x, z, action);
    }

    public static final class Dispatch {
        private final Player player;
        private final boolean async;
        private Dispatch(Player player, boolean async) { this.player = player; this.async = async; }
        public void run(Plugin plugin, Consumer<Object> action) { schedule(plugin, action, null, 0); }
        public void run(Plugin plugin, Consumer<Object> action, Runnable retired) { schedule(plugin, action, retired, 0); }
        public void runNow(Plugin plugin, Consumer<Object> action) { schedule(plugin, action, null, 0); }
        public void runDelayed(Plugin plugin, Consumer<Object> action, long delay) { schedule(plugin, action, null, delay); }
        public void runDelayed(Plugin plugin, Consumer<Object> action, Runnable retired, long delay) { schedule(plugin, action, retired, delay); }

        private void schedule(Plugin plugin, Consumer<Object> action, Runnable retired, long delay) {
            if (!FOLIA) {
                Runnable task = () -> {
                    if (player != null && !player.isOnline()) { if (retired != null) retired.run(); return; }
                    action.accept(null);
                };
                if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
                else if (delay > 0) Bukkit.getScheduler().runTaskLater(plugin, task, delay);
                else Bukkit.getScheduler().runTask(plugin, task);
                return;
            }
            if (player != null) {
                Object scheduler = invoke("org.bukkit.entity.Entity", player, "getScheduler", new Class<?>[0]);
                if (delay > 0) invoke("io.papermc.paper.threadedregions.scheduler.EntityScheduler", scheduler, "runDelayed",
                        new Class<?>[]{Plugin.class, Consumer.class, Runnable.class, long.class}, plugin, action, retired, delay);
                else invoke("io.papermc.paper.threadedregions.scheduler.EntityScheduler", scheduler, "run",
                        new Class<?>[]{Plugin.class, Consumer.class, Runnable.class}, plugin, action, retired);
            } else {
                String type = async ? "AsyncScheduler" : "GlobalRegionScheduler";
                Object scheduler = invoke("org.bukkit.Bukkit", null, "get" + type, new Class<?>[0]);
                String owner = "io.papermc.paper.threadedregions.scheduler." + type;
                if (async) invoke(owner, scheduler, "runNow", new Class<?>[]{Plugin.class, Consumer.class}, plugin, action);
                else if (delay > 0) invoke(owner, scheduler, "runDelayed", new Class<?>[]{Plugin.class, Consumer.class, long.class}, plugin, action, delay);
                else invoke(owner, scheduler, "run", new Class<?>[]{Plugin.class, Consumer.class}, plugin, action);
            }
        }
    }
}
