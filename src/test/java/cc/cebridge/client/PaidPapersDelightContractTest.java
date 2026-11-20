package cc.cebridge.client;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 仅针对用户提供的 PD JAR 验证二进制契约，不包含或重新分发该 JAR。 */
class PaidPapersDelightContractTest {
    @Test void paid130RuntimeContracts() throws Exception {
        String jar = System.getProperty("cebridge.pdJar");
        assumeTrue(jar != null && java.nio.file.Files.isRegularFile(Path.of(jar)));
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, getClass().getClassLoader())) {
            var resolver = PapersDelightExport.resolver(loader);
            var paidResolver = loader.loadClass("dev.tako.papersdelight.recipe.DEOBF_c");
            var singleton = java.util.Arrays.stream(paidResolver.getDeclaredFields())
                .filter(f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()) && paidResolver.isAssignableFrom(f.getType()))
                .findFirst().orElseThrow();
            singleton.setAccessible(true);
            assertSame(singleton.get(null),resolver,"Paid 1.3.0 must reuse its static resolver rather than depend on constructor visibility");
            assertNotNull(PapersDelightExport.tagMethod(resolver.getClass(), false));
            assertNotNull(PapersDelightExport.tagMethod(resolver.getClass(), true));
            var util = loader.loadClass("dev.tako.papersdelight.ce.CraftEngineUtil");
            assertEquals(String.class, PapersDelightExport.remainderMethod(util).getReturnType());
            var instance = loader.loadClass("dev.tako.papersdelight.PapersDelight");
            var board = loader.loadClass("dev.tako.papersdelight.mechanic.cutting.CuttingBoardManager");
            assertEquals(1, java.util.Arrays.stream(instance.getDeclaredFields()).filter(f -> f.getType() == board).count());
            var manager = loader.loadClass("dev.tako.papersdelight.recipe.RecipeManager");
            assertEquals(List.class, manager.getMethod("recipes").getReturnType());
            var ingredient = loader.loadClass("dev.tako.papersdelight.recipe.IngredientDef");
            assertEquals(List.class, ingredient.getMethod("displayExpressions").getReturnType());
            var cutting = loader.loadClass("dev.tako.papersdelight.mechanic.cutting.CuttingRecipe");
            for (String method : List.of("DEOBF_ai", "DEOBF_aj", "DEOBF_ak")) assertNotNull(cutting.getMethod(method));
        }
    }
    @Test void paidDumplingConvertsUsingActualRecords() throws Exception {
        String jar = System.getProperty("cebridge.pdJar");
        assumeTrue(jar != null && java.nio.file.Files.isRegularFile(Path.of(jar)));
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, getClass().getClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("dev.tako.papersdelight.recipe.DefaultItemMatcherResolver"),
                "Reproduces the exact server error from 2.0.12");
            var resolver = PapersDelightExport.resolver(loader);
            var exporter = new PapersDelightExport(loader,PapersDelightExportTest.Items.class.getMethod("create",String.class,int.class),resolver,List.of());
            var ingredient = loader.loadClass("dev.tako.papersdelight.recipe.IngredientDef");
            var ingredients = new java.util.ArrayList<Object>();
            var constructor = ingredient.getConstructor(org.bukkit.Material.class,String.class,String.class,List.class);
            ingredients.add(constructor.newInstance(null,null,"farmersdelight:wheat_dough",List.of()));
            ingredients.add(constructor.newInstance(null,null,null,List.of("minecraft:porkchop")));
            ingredients.add(constructor.newInstance(null,null,null,List.of("dumplings_delight:chinese_cabbage","farmersdelight:cabbage")));
            ingredients.add(constructor.newInstance(null,null,"dumplings_delight:greenonion",List.of()));
            var cooking = loader.loadClass("dev.tako.papersdelight.recipe.CookingRecipe")
                .getConstructor(String.class,String.class,int.class,String.class,int.class,float.class,List.class,String.class)
                .newInstance("cooking","dumplings_delight:pork_cabbage_boiled_dumpling",2,"minecraft:bowl",200,.35F,ingredients,"dumplings_delight:cooking/pork_cabbage_boiled_dumpling");
            var out = exporter.convert(cooking,"cooking_pot",0);
            assertEquals(5,out.getAsJsonArray("inputs").size());
            assertEquals("dumplings_delight:pork_cabbage_boiled_dumpling/2",new String(java.util.Base64.getDecoder()
                .decode(out.getAsJsonArray("outputs").get(0).getAsString()),java.nio.charset.StandardCharsets.UTF_8));

        }
    }

}
