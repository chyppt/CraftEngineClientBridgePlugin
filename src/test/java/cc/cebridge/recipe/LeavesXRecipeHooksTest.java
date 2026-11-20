package cc.cebridge.recipe;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static cc.cebridge.recipe.LeavesXRecipeHooks.State.*;
import static org.junit.jupiter.api.Assertions.*;

class LeavesXRecipeHooksTest {
    @Test
    void disabledCoreProtocolDoesNotInvalidateItsCache() {
        AtomicInteger calls = new AtomicInteger();
        var hooks = access(new LeavesXRecipeHooks.Hook("JEI", "test", () -> false,
                calls::incrementAndGet, null));
        assertEquals(DISABLED, hooks.invalidate().getFirst().state());
        assertEquals(0, calls.get());
    }

    @Test
    void enabledProtocolsAreInvalidatedIndependently() {
        AtomicInteger rei = new AtomicInteger();
        AtomicInteger jei = new AtomicInteger();
        var hooks = access(new LeavesXRecipeHooks.Hook("REI", "test", () -> true, rei::incrementAndGet, null),
                new LeavesXRecipeHooks.Hook("JEI", "test", () -> true, jei::incrementAndGet, null));
        assertTrue(hooks.invalidate().stream().allMatch(status -> status.state() == READY));
        assertEquals(1, rei.get());
        assertEquals(1, jei.get());
    }

    @Test
    void oneProtocolFailureDoesNotPreventTheOtherFromRefreshing() {
        AtomicInteger calls = new AtomicInteger();
        var hooks = access(new LeavesXRecipeHooks.Hook("REI", "test", () -> true,
                () -> { throw new InvocationTargetException(new IllegalStateException("fixture")); }, null),
                new LeavesXRecipeHooks.Hook("JEI", "test", () -> true, calls::incrementAndGet, null));
        var states = hooks.invalidate();
        assertEquals(FAILED, states.get(0).state());
        assertTrue(states.get(0).detail().contains("IllegalStateException"));
        assertEquals(READY, states.get(1).state());
        assertEquals(1, calls.get());
    }

    @Test
    void changesToCoreFlagsTakeEffectWithoutCachingOldValues() {
        AtomicBoolean enabled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        var hooks = access(new LeavesXRecipeHooks.Hook("JEI", "test", enabled::get, calls::incrementAndGet, null));
        hooks.invalidate();
        enabled.set(true);
        hooks.invalidate();
        enabled.set(false);
        hooks.invalidate();
        assertEquals(1, calls.get());
    }

    @Test
    void inspectingStatusNeverInvalidatesCoreCaches() {
        AtomicInteger calls = new AtomicInteger();
        var hooks = access(new LeavesXRecipeHooks.Hook("REI", "test", () -> true, calls::incrementAndGet, null));
        assertEquals(READY, hooks.inspect().getFirst().state());
        assertEquals(0, calls.get());
    }

    @Test
    void paperWithoutLeavesXRemainsLoadable() {
        var hooks = LeavesXRecipeHooks.discover(ClassLoader.getPlatformClassLoader(), "26.1.2");
        assertTrue(hooks.inspect().stream().allMatch(status -> status.state() == UNAVAILABLE));
        assertTrue(hooks.invalidate().stream().allMatch(status -> status.state() == UNAVAILABLE));
    }

    @Test
    void anotherMinecraftVersionIsRejectedBeforeLoadingCoreClasses() {
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new AssertionError("Core classes must not be loaded for an unsupported version");
            }
        };
        var hooks = LeavesXRecipeHooks.discover(loader, "26.3");
        assertTrue(hooks.inspect().stream().allMatch(status -> status.state() == UNAVAILABLE));
    }

    @Test
    void unreadableCoreFlagDoesNotTriggerReload() {
        AtomicInteger calls = new AtomicInteger();
        var hooks = access(new LeavesXRecipeHooks.Hook("JEI", "test",
                () -> { throw new IllegalStateException("configuration unavailable"); }, calls::incrementAndGet, null));
        assertEquals(FAILED, hooks.invalidate().getFirst().state());
        assertEquals(0, calls.get());
    }

    private static LeavesXRecipeHooks access(LeavesXRecipeHooks.Hook... hooks) {
        return new LeavesXRecipeHooks(List.of(hooks));
    }
}
