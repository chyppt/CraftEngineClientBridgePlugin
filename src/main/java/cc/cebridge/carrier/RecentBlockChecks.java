package cc.cebridge.carrier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, short-lived maintenance results shared by viewers; never caches live world reads. */
final class RecentBlockChecks<K, S> {
    private final Map<K, Entry<S>> entries = new LinkedHashMap<>(16, 0.75F, true);
    private final int capacity;
    private final long lifetimeNanos;

    RecentBlockChecks(int capacity, long lifetimeNanos) {
        if (capacity < 1 || lifetimeNanos <= 0) throw new IllegalArgumentException("Invalid cache bounds");
        this.capacity = capacity;
        this.lifetimeNanos = lifetimeNanos;
    }

    synchronized boolean isFresh(K key, S currentState, long now) {
        Entry<S> entry = entries.get(key);
        if (entry == null) return false;
        // CE states are immutable and interned. Identity also detects a rebuilt CE registry.
        if (entry.state() != currentState || now - entry.checkedAt() >= lifetimeNanos) {
            entries.remove(key);
            return false;
        }
        return true;
    }

    synchronized void record(K key, S state, long now) {
        entries.put(key, new Entry<>(state, now));
        if (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }

    synchronized void remove(K key) { entries.remove(key); }

    synchronized void clear() { entries.clear(); }

    private record Entry<S>(S state, long checkedAt) {}
}
