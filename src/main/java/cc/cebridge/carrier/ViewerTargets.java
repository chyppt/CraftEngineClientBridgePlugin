package cc.cebridge.carrier;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 仅保存每名观察者的当前目标；计数更新是原子的，不在锁内调用世界 API。 */
final class ViewerTargets<P, K> {
    private final Map<P, K> targets = new HashMap<>();
    private final Map<K, Integer> viewers = new HashMap<>();

    /** 返回失去最后一名观察者的旧目标；调用者在所属区域安排清理。 */
    synchronized K move(P player, K target) {
        K previous = targets.get(player);
        if (Objects.equals(previous, target)) return null;
        if (target == null) targets.remove(player);
        else targets.put(player, target);
        if (target != null) viewers.merge(target, 1, Integer::sum);
        if (previous == null) return null;
        int remaining = viewers.get(previous) - 1;
        if (remaining == 0) {
            viewers.remove(previous);
            return previous;
        }
        viewers.put(previous, remaining);
        return null;
    }

    synchronized K get(P player) { return targets.get(player); }
    synchronized boolean isViewed(K target) { return viewers.containsKey(target); }
    synchronized int targetCount() { return viewers.size(); }
    synchronized int viewerCount() { return targets.size(); }
    synchronized void clear() { targets.clear(); viewers.clear(); }
}
