package cc.cebridge;
import cc.cebridge.wire.CategoryMembers;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CategoryMembersTest {
    @Test void preservesFirstLevelOrderAndFlattensNestedMembers() {
        var graph=Map.of("root",List.of("a","#child","b"),"child",List.of("b","c"));
        assertEquals(List.of("a","b","c"),CategoryMembers.flatten("root",id->graph.getOrDefault(id,List.of()),id->true,id->true));
    }
    @Test void forbiddenChildDoesNotLeakIntoParent() {
        var graph=Map.of("root",List.of("a","#restricted","b"),"restricted",List.of("secret"));
        assertEquals(List.of("a","b"),CategoryMembers.flatten("root",id->graph.getOrDefault(id,List.of()),id->!id.equals("restricted"),id->true));
    }
    @Test void missingItemsAndCyclesCannotCorruptCatalog() {
        var graph=Map.of("root",List.of("","#child","missing"),"child",List.of("a","#root","a"));
        assertEquals(List.of("a"),CategoryMembers.flatten("root",id->graph.getOrDefault(id,List.of()),id->true,id->id.equals("a")));
    }
    @Test void hiddenNestedCategoryCanStillContributeMembersWhenAuthorized() {
        var graph=Map.of("main",List.of("#hidden"),"hidden",List.of("item"));
        assertEquals(List.of("item"),CategoryMembers.flatten("main",id->graph.getOrDefault(id,List.of()),id->true,id->true));
    }
}
