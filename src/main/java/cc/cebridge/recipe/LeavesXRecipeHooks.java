package cc.cebridge.recipe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 对接 LeavesX 26.1.2 已有的配方失效通知，不注册配方、不自行发送同步包。
 * 可选反射避免在普通 Paper/Folia 上解析不存在的核心类。
 */
public final class LeavesXRecipeHooks {
    private static final String REI_SETTING = "leaves.yml: settings.protocol.rei-server-protocol";
    private static final String JEI_SETTING = "leavesx.yml: features.just-enough-items.recipe-sync";
    private final List<Hook> hooks;

    LeavesXRecipeHooks(List<Hook> hooks) {
        this.hooks = List.copyOf(hooks);
    }

    public static LeavesXRecipeHooks discover(ClassLoader loader, String minecraftVersion) {
        if (!"26.1.2".equals(minecraftVersion)) {
            String reason = "适配目标为 LeavesX 26.1.2，当前 Minecraft 为 " + minecraftVersion;
            return new LeavesXRecipeHooks(List.of(unavailable("REI", REI_SETTING, reason),
                    unavailable("JEI", JEI_SETTING, reason)));
        }
        return new LeavesXRecipeHooks(List.of(bindRei(loader), bindJei(loader)));
    }

    private static Hook bindRei(ClassLoader loader) {
        try {
            Class<?> configClass = Class.forName("org.leavesmc.leaves.LeavesConfig", false, loader);
            Field protocol = configClass.getField("protocol");
            Field enabled = protocol.getType().getField("reiServerProtocol");
            Method reload = Class.forName("org.leavesmc.leaves.protocol.rei.REIServerProtocol", false, loader)
                    .getMethod("onRecipeReload");
            return new Hook("REI", REI_SETTING, () -> enabled.getBoolean(protocol.get(null)),
                    () -> reload.invoke(null), null);
        } catch (ReflectiveOperationException | LinkageError ex) {
            return unavailable("REI", REI_SETTING, "未找到兼容的核心接口：" + ex.getClass().getSimpleName());
        }
    }

    private static Hook bindJei(ClassLoader loader) {
        try {
            Class<?> runtime = Class.forName("org.leavesx.leavesx.config.LeavesXRuntime", false, loader);
            Method configuration = runtime.getMethod("configuration");
            Method extensions = configuration.getReturnType().getMethod("extensions");
            Method enabled = extensions.getReturnType().getMethod("jeiRecipeSync");
            Method reload = Class.forName("org.leavesmc.leaves.protocol.jei.JEIRecipeSyncProtocol", false, loader)
                    .getMethod("onDataPackReload");
            return new Hook("JEI", JEI_SETTING,
                    () -> (boolean) enabled.invoke(extensions.invoke(configuration.invoke(null))),
                    () -> reload.invoke(null), null);
        } catch (ReflectiveOperationException | LinkageError ex) {
            return unavailable("JEI", JEI_SETTING, "未找到兼容的核心接口：" + ex.getClass().getSimpleName());
        }
    }

    private static Hook unavailable(String name, String setting, String reason) {
        return new Hook(name, setting, null, null, reason);
    }

    /** 只读配置；开关每次读取，核心热重载配置后无需重新启动本插件。 */
    public List<Status> inspect() {
        return hooks.stream().map(hook -> execute(hook, false)).toList();
    }

    /** 必须在全局调度器调用；交由核心按其通道协商、缓存和分包规则重新发送。 */
    public List<Status> invalidate() {
        return hooks.stream().map(hook -> execute(hook, true)).toList();
    }

    private static Status execute(Hook hook, boolean invalidate) {
        if (hook.unavailable() != null) {
            return new Status(hook.name(), hook.setting(), State.UNAVAILABLE, hook.unavailable());
        }
        try {
            if (!hook.enabled().get()) {
                return new Status(hook.name(), hook.setting(), State.DISABLED, "核心配方同步开关未开启");
            }
            if (invalidate) hook.reload().run();
            return new Status(hook.name(), hook.setting(), State.READY,
                    invalidate ? "已通知核心重新同步配方" : "核心同步接口可用（不代表客户端已显示）");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            return new Status(hook.name(), hook.setting(), State.FAILED,
                    "核心接口调用失败：" + cause.getClass().getSimpleName());
        }
    }

    public enum State { UNAVAILABLE, DISABLED, READY, FAILED }

    public record Status(String protocol, String setting, State state, String detail) { }

    @FunctionalInterface
    interface Flag { boolean get() throws ReflectiveOperationException; }

    @FunctionalInterface
    interface Action { void run() throws ReflectiveOperationException; }

    record Hook(String name, String setting, Flag enabled, Action reload, String unavailable) { }
}
