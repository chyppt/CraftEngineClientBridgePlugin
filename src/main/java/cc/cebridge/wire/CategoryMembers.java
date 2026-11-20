package cc.cebridge.wire;
import java.util.*;
import java.util.function.*;
/** 扁平化只读分类图；访问集合阻断循环和重复展开，LinkedHashSet 保留配置顺序并去重。 */
public final class CategoryMembers {
    private CategoryMembers() {}
    public static List<String> flatten(String root,Function<String,List<String>> members,
            Predicate<String> allowedCategory,Predicate<String> availableItem) {
        var out=new LinkedHashSet<String>(); collect(root,members,allowedCategory,availableItem,out,new HashSet<>(),0);return List.copyOf(out);
    }
    private static void collect(String id,Function<String,List<String>> members,Predicate<String> allowed,
            Predicate<String> available,LinkedHashSet<String> out,Set<String> visited,int depth) {
        if(depth>16 || !allowed.test(id) || !visited.add(id)) return;
        for(String value:members.apply(id)) {
            if(value==null || value.isBlank()) continue;
            if(value.charAt(0)=='#') collect(value.substring(1),members,allowed,available,out,visited,depth+1);
            else if(available.test(value)) out.add(value);
        }
        // 同一次导出的分类只需展开一次；DAG 共享子分类也不能导致指数遍历。
    }
}
