package cc.cebridge.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 在原始 ID 上检查范围，防止 18 个原版饰纹膨胀成数千个 cebridge 配方。 */
class RecipeSnapshotScopeTest {
    @Test void leavesXKeepsOnlyProvidersItNeeds() {
        assertFalse(RecipeSnapshotBuilder.includeRecipe(Key.of("minecraft:sentry_armor_trim_smithing_template_smithing_trim"), true));
        assertFalse(RecipeSnapshotBuilder.includeRecipe(Key.of("minecraft:leather_horse_armor_dyed"), true));
        for (String id : new String[]{"craftengine:custom_trim", "farmersdelight:sandwich", "rusticdelight:coffee", "cebridge:custom"})
            assertTrue(RecipeSnapshotBuilder.includeRecipe(Key.of(id), true), id);
    }
    @Test void optionalClientStillReceivesTheCompleteCatalog() {
        assertTrue(RecipeSnapshotBuilder.includeRecipe(Key.of("minecraft:sentry_armor_trim_smithing_template_smithing_trim"), false));
        assertTrue(RecipeSnapshotBuilder.includeRecipe(Key.of("minecraft:wolf_armor_dyed"), false));
        assertTrue(RecipeSnapshotBuilder.includeRecipe(Key.of("farmersdelight:sandwich"), false));
    }
}
