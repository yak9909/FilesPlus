package app.filesplus.extension.preview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Files selected in the preview screen. Entries are opaque app objects (the app's own file proto)
 * keyed by MediaStore id, so they can be handed back to the app's selection model unchanged.
 * Static on purpose: it survives activity re-creation; process death may drop it.
 */
public final class SelectionStore {
    private static final Map<Long, Object> SELECTED = new LinkedHashMap<>();

    private SelectionStore() {}

    /** Selects the file, or deselects it when already selected. Returns the new selected state. */
    public static synchronized boolean toggle(long id, Object entry) {
        if (SELECTED.containsKey(id)) {
            SELECTED.remove(id);
            return false;
        }
        SELECTED.put(id, entry);
        return true;
    }

    public static synchronized boolean isSelected(long id) {
        return SELECTED.containsKey(id);
    }

    public static synchronized int count() {
        return SELECTED.size();
    }

    /** MediaStore ids of the selected files in selection order. */
    public static synchronized long[] ids() {
        long[] ids = new long[SELECTED.size()];
        int i = 0;
        for (Long id : SELECTED.keySet()) ids[i++] = id;
        return ids;
    }

    /** Selected entries in selection order (a copy). */
    public static synchronized List<Object> entries() {
        return new ArrayList<>(SELECTED.values());
    }

    /** Returns the entries whose id passes {@code keep} (a copy) and clears the store in one step. */
    public static synchronized List<Object> drain(LongPredicate keep) {
        List<Object> entries = new ArrayList<>();
        for (Map.Entry<Long, Object> entry : SELECTED.entrySet()) {
            if (keep.test(entry.getKey())) entries.add(entry.getValue());
        }
        SELECTED.clear();
        return entries;
    }

    public static synchronized void clear() {
        SELECTED.clear();
    }
}
