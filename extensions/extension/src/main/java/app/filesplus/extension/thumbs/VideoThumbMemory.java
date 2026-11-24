package app.filesplus.extension.thumbs;

import java.util.LinkedHashMap;

/** Bounded access-ordered memory shared by request and worker threads. Entries have no time-based expiry. */
final class VideoThumbMemory<K, V> {
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final int limit;

    VideoThumbMemory(int limit) { this.limit = limit; }

    synchronized V get(K key) { return entries.get(key); }

    synchronized V put(K key, V value) {
        V previous = entries.put(key, value);
        if (entries.size() > limit) entries.remove(entries.keySet().iterator().next());
        return previous;
    }
}
