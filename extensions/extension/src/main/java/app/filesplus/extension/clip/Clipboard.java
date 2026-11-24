package app.filesplus.extension.clip;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The FilesPlus clipboard (DESIGN 5.12.2): a list of files and folders that were cut, kept in
 * SharedPreferences {@code filesplus_clipboard} so it survives the process being killed. Contents older than
 * 24 hours are dropped by {@link #get}. Only CUT exists for now; COPY is added after the verified copy (S4).
 * The same file keeps the records of running and cut-short pastes (see {@link #beginPaste}).
 *
 * <p>Temporary names are persisted before creation. Startup cleanup is delegated to InterruptedPastes.
 */
public final class Clipboard {
    private static final String AREA = "clip";
    private static final String DEFAULT_PREFS = "filesplus_clipboard";
    private static final String KEY_MODE = "mode";
    private static final String KEY_CREATED = "createdAt";
    private static final String KEY_ITEMS = "items";
    /**
     * JSON object of the pastes running now: key "<process>:<token>" -> {proc, dest, at, reserved[], parts[]}. A record of
     * another process is a paste that was cut short (the process died) and is moved to KEY_INTERRUPTED.
     */
    private static final String KEY_PASTE = "paste";
    /** JSON array of cut-short pastes: {dest, at, reserved[], parts[], partFolders[], toasted}. Kept until the user dismisses them. */
    private static final String KEY_INTERRUPTED = "interrupted";
    private static final int MAX_INTERRUPTED = 20;
    static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    /** Tells this process's pastes from those of a process that died. */
    private static final String PROCESS = Long.toHexString(System.nanoTime() ^ (System.currentTimeMillis() << 20));

    private static final Object LOCK = new Object();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile String prefsName = DEFAULT_PREFS;
    private static long lastToken;

    private Clipboard() {}

    /** Only CUT is produced for now. */
    public enum Mode { CUT }

    /** An immutable snapshot of the clipboard. {@code createdAt} is the time of the last change. */
    public static final class State {
        public final Mode mode;
        public final List<ClipItem> items;
        public final long createdAt;

        public State(Mode mode, List<ClipItem> items, long createdAt) {
            this.mode = mode;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.createdAt = createdAt;
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    private static State empty() {
        return new State(Mode.CUT, Collections.<ClipItem>emptyList(), 0);
    }

    /** The current clipboard; an empty state when nothing is stored, it is unreadable or it is older than 24 hours. */
    public static State get(Context context) {
        boolean expired = false;
        State state;
        synchronized (LOCK) {
            state = read(context);
            if (!state.isEmpty() && System.currentTimeMillis() - state.createdAt > MAX_AGE_MS) {
                DiagLog.i(AREA, "Clipboard dropped: older than 24 hours (" + state.items.size() + " item(s))");
                prefs(context).edit().remove(KEY_MODE).remove(KEY_CREATED).remove(KEY_ITEMS).apply();
                state = empty();
                expired = true;
            }
        }
        if (expired) notifyListeners();
        return state;
    }

    /** Replaces the contents with these items (a new "cut"). */
    public static void cut(Context context, List<ClipItem> items) {
        synchronized (LOCK) {
            write(context, Mode.CUT, unique(items), read(context).createdAt);
        }
        notifyListeners();
    }

    /** Adds items to the current contents (same mode). Equal paths are kept once. */
    public static void add(Context context, List<ClipItem> items) {
        synchronized (LOCK) {
            State current = read(context);
            List<ClipItem> merged = new ArrayList<>(current.items);
            merged.addAll(items);
            write(context, Mode.CUT, unique(merged), current.createdAt);
        }
        notifyListeners();
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            prefs(context).edit().remove(KEY_MODE).remove(KEY_CREATED).remove(KEY_ITEMS).apply();
        }
        notifyListeners();
    }

    /**
     * Called when a paste has finished: if the clipboard still is the snapshot the paste used, it is cleared
     * ({@code remaining} empty) or reduced to {@code remaining}. A clipboard that changed meanwhile is left alone.
     */
    public static void finishCut(Context context, State used, List<ClipItem> remaining) {
        boolean changed = false;
        synchronized (LOCK) {
            State current = read(context);
            if (current.createdAt == used.createdAt && !current.isEmpty()) {
                if (remaining.isEmpty()) {
                    prefs(context).edit().remove(KEY_MODE).remove(KEY_CREATED).remove(KEY_ITEMS).apply();
                } else {
                    write(context, Mode.CUT, remaining, current.createdAt);
                }
                changed = true;
            }
        }
        if (changed) notifyListeners();
    }

    /** Listeners run on the main thread, once per change. Remove them again when the screen goes away. */
    public static void addListener(Runnable listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    // ---- paste marker (an interrupted paste is never resumed automatically) ----------------------------------

    /** A paste that was cut short by the process dying (ColorOS). Only recorded temporary entries can be cleaned on startup. */
    public static final class Interrupted {
        /** The folder the paste was going into. */
        public final String destination;
        public final long at;
        /**
         * The names that were reserved (a 0-byte file or an empty folder created before the rename) when the
         * paste stopped. One of them can still be that placeholder: a stray empty file or folder.
         */
        public final List<String> reserved;
        /** Exact paths of temporary files still eligible for startup cleanup. */
        public final List<String> parts;
        public final List<String> partFolders;
        /** The "interrupted" toast was already shown for it. */
        public final boolean toasted;

        Interrupted(String destination, long at, List<String> reserved, boolean toasted) {
            this(destination, at, reserved, Collections.emptyList(), Collections.emptyList(), toasted);
        }

        Interrupted(String destination, long at, List<String> reserved, List<String> parts,
                    List<String> partFolders, boolean toasted) {
            this.destination = destination;
            this.at = at;
            this.reserved = Collections.unmodifiableList(reserved);
            this.parts = Collections.unmodifiableList(parts);
            this.partFolders = Collections.unmodifiableList(partFolders);
            this.toasted = toasted;
        }
    }

    /**
     * Records that a paste into {@code destination} started and returns its token. Records left by a process that
     * died are first moved to the interrupted list (never overwritten).
     */
    public static long beginPaste(Context context, String destination) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            try {
                promoteStale(prefs);
                long token = ++lastToken;
                JSONObject all = readObject(prefs, KEY_PASTE);
                all.put(PROCESS + ":" + token, new JSONObject().put("proc", PROCESS).put("dest", destination)
                        .put("at", System.currentTimeMillis()).put("reserved", new JSONArray()));
                prefs.edit().putString(KEY_PASTE, all.toString()).commit();
                return token;
            } catch (Throwable t) {
                DiagLog.e(AREA, "Cannot record the start of a paste", t);
                return ++lastToken;
            }
        }
    }

    /**
     * Records that {@code path} was reserved for the rename that follows right now. Written to disk before the
     * rename, so a process that dies in between leaves the name in the interrupted record.
     */
    public static void reserve(Context context, long token, String path) {
        changeReserved(context, token, path, true);
    }

    /** The rename of {@code path} is over (done, or failed and the placeholder removed). */
    public static void unreserve(Context context, long token, String path) {
        changeReserved(context, token, path, false);
    }

    public static boolean recordPart(Context context, long token, String path) {
        return recordPart(context, token, path, false);
    }

    public static boolean recordPart(Context context, long token, String path, boolean directory) {
        return changeName(context, token, directory ? "partFolders" : "parts", path, true);
    }

    public static void forgetPart(Context context, long token, String path, boolean directory) {
        changeName(context, token, directory ? "partFolders" : "parts", path, false);
    }

    private static void changeReserved(Context context, long token, String path, boolean add) {
        changeName(context, token, "reserved", path, add);
    }

    private static boolean changeName(Context context, long token, String field, String path, boolean add) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            try {
                JSONObject all = readObject(prefs, KEY_PASTE);
                JSONObject record = all.optJSONObject(PROCESS + ":" + token);
                if (record == null) return false;
                JSONArray old = record.optJSONArray(field);
                JSONArray names = new JSONArray();
                if (old != null) for (int i = 0; i < old.length(); i++) {
                    if (!path.equals(old.getString(i))) names.put(old.getString(i));
                }
                if (add) names.put(path);
                record.put(field, names);
                boolean saved = prefs.edit().putString(KEY_PASTE, all.toString()).commit();
                if (!saved) DiagLog.e(AREA, "Cannot save " + field + " for " + path, null);
                return saved;
            } catch (Throwable t) {
                DiagLog.e(AREA, "Cannot update " + field + " for " + path, t);
                return false;
            }
        }
    }

    /** The paste has finished. A record with an empty proc is kept only for part cleanup. */
    public static void endPaste(Context context, long token) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            try {
                JSONObject all = readObject(prefs, KEY_PASTE);
                String key = PROCESS + ":" + token;
                JSONObject record = all.optJSONObject(key);
                if (record != null && hasParts(record)) record.put("proc", "");
                else all.remove(key);
                SharedPreferences.Editor editor = prefs.edit();
                if (all.length() == 0) editor.remove(KEY_PASTE);
                else editor.putString(KEY_PASTE, all.toString());
                editor.commit();
            } catch (Throwable t) {
                DiagLog.e(AREA, "Cannot clear the paste record", t);
            }
        }
    }

    /** Pastes that were cut short, oldest first. They stay until {@link #dismissInterrupted}. */
    public static List<Interrupted> interrupted(Context context) {
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = prefs(context);
                promoteStale(prefs);
                return readInterrupted(prefs);
            } catch (Throwable t) {
                DiagLog.e(AREA, "Cannot read the interrupted pastes", t);
                return Collections.emptyList();
            }
        }
    }

    private static List<Interrupted> readInterrupted(SharedPreferences prefs) throws Exception {
        List<Interrupted> out = new ArrayList<>();
        JSONArray list = readArray(prefs, KEY_INTERRUPTED);
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            out.add(new Interrupted(o.optString("dest", ""), o.optLong("at", 0),
                    names(o, "reserved"), names(o, "parts"), names(o, "partFolders"), o.optBoolean("toasted", false)));
        }
        return out;
    }

    /** The toast for the interrupted pastes was shown; the records stay for the diagnostics page. */
    public static void markInterruptedToasted(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            try {
                JSONArray list = readArray(prefs, KEY_INTERRUPTED);
                for (int i = 0; i < list.length(); i++) list.getJSONObject(i).put("toasted", true);
                prefs.edit().putString(KEY_INTERRUPTED, list.toString()).commit();
            } catch (Throwable t) {
                DiagLog.e(AREA, "Cannot update the interrupted pastes", t);
            }
        }
    }

    /** Forget reviewed records once they no longer have temporary files to clean. */
    public static void dismissInterrupted(Context context) {
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = prefs(context);
                JSONArray list = readArray(prefs, KEY_INTERRUPTED);
                for (int i = list.length() - 1; i >= 0; i--) {
                    JSONObject record = list.optJSONObject(i);
                    if (record != null && !hasParts(record)) list.remove(i);
                }
                if (!prefs.edit().putString(KEY_INTERRUPTED, list.toString()).commit()) {
                    DiagLog.e(AREA, "Cannot dismiss interrupted pastes", null);
                }
            } catch (Throwable t) {
                DiagLog.e(AREA, "Cannot dismiss interrupted pastes", t);
            }
        }
    }

    /** Runs as the first file-executor task after application initialization. */
    public static void recoverInterrupted(Context context) {
        try {
            SharedPreferences prefs;
            List<Interrupted> snapshot;
            synchronized (LOCK) {
                prefs = prefs(context);
                promoteStale(prefs);
                snapshot = readInterrupted(prefs);
            }

            Map<String, Set<String>> resolved = new LinkedHashMap<>();
            for (Interrupted record : snapshot) {
                if (record.parts.isEmpty() && record.partFolders.isEmpty()) continue;
                List<String> remaining = InterruptedPastes.cleanParts(context, record);
                Set<String> removed = new HashSet<>(record.parts);
                removed.addAll(record.partFolders);
                removed.removeAll(remaining);
                if (!removed.isEmpty()) resolved.computeIfAbsent(record.destination, key -> new HashSet<>())
                        .addAll(removed);
            }
            if (resolved.isEmpty()) return;

            synchronized (LOCK) {
                // Merge into the current records so concurrent diagnostics edits are preserved.
                JSONArray list = readArray(prefs, KEY_INTERRUPTED);
                boolean changed = false;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject record = list.getJSONObject(i);
                    Set<String> removed = resolved.get(record.optString("dest"));
                    if (removed == null) continue;
                    for (String field : new String[]{"parts", "partFolders"}) {
                        List<String> parts = names(record, field);
                        if (!parts.removeAll(removed)) continue;
                        record.put(field, new JSONArray(parts));
                        changed = true;
                    }
                }
                if (changed && !prefs.edit().putString(KEY_INTERRUPTED, list.toString()).commit()) {
                    DiagLog.e(AREA, "Cannot save interrupted move cleanup", null);
                }
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot recover interrupted moves", t);
        }
    }

    private static boolean hasParts(JSONObject record) {
        return !names(record, "parts").isEmpty() || !names(record, "partFolders").isEmpty();
    }

    private static List<String> names(JSONObject record, String field) {
        List<String> paths = new ArrayList<>();
        JSONArray names = record.optJSONArray(field);
        if (names != null) for (int i = 0; i < names.length(); i++) {
            String path = names.optString(i, "");
            if (!path.isEmpty()) paths.add(path);
        }
        return paths;
    }

    /** Moves records of earlier processes from the running list to the interrupted list. */
    private static void promoteStale(SharedPreferences prefs) throws Exception {
        JSONObject all = readObject(prefs, KEY_PASTE);
        if (all.length() == 0) return;
        JSONObject keep = new JSONObject();
        JSONArray list = readArray(prefs, KEY_INTERRUPTED);
        boolean moved = false;
        java.util.Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject record = all.getJSONObject(key);
            String process = record.optString("proc");
            if (PROCESS.equals(process)) {
                keep.put(key, record);
            } else {
                list.put(new JSONObject().put("dest", record.optString("dest", "")).put("at", record.optLong("at", 0))
                        .put("reserved", record.optJSONArray("reserved") == null ? new JSONArray()
                                : record.optJSONArray("reserved"))
                        .put("parts", new JSONArray(names(record, "parts")))
                        .put("partFolders", new JSONArray(names(record, "partFolders")))
                        .put("toasted", process.isEmpty()));
                moved = true;
            }
        }
        if (!moved) return;
        for (int i = 0; list.length() > MAX_INTERRUPTED && i < list.length();) {
            if (!hasParts(list.getJSONObject(i))) list.remove(i);
            else i++;
        }
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_INTERRUPTED, list.toString());
        if (keep.length() == 0) editor.remove(KEY_PASTE);
        else editor.putString(KEY_PASTE, keep.toString());
        editor.commit();
        DiagLog.i(AREA, "Interrupted paste or pending part cleanup recorded (see Diagnostics)");
    }

    private static JSONObject readObject(SharedPreferences prefs, String key) {
        try {
            String raw = prefs.getString(key, null);
            return raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable t) {
            DiagLog.e(AREA, "Unreadable " + key + ", ignored", t);
            return new JSONObject();
        }
    }

    private static JSONArray readArray(SharedPreferences prefs, String key) {
        try {
            String raw = prefs.getString(key, null);
            return raw == null ? new JSONArray() : new JSONArray(raw);
        } catch (Throwable t) {
            DiagLog.e(AREA, "Unreadable " + key + ", ignored", t);
            return new JSONArray();
        }
    }

    // ---- storage ----------------------------------------------------------------------------------------------

    /**
     * Test hook (same package): use another SharedPreferences file, so the self test never touches the user's
     * clipboard or paste records. {@code null} goes back to the real one.
     */
    static void useStoreForTest(String name) {
        prefsName = name == null ? DEFAULT_PREFS : name;
    }

    /** Test hook: empties the preferences file {@code name}. */
    static void dropStore(Context context, String name) {
        Context app = ModKit.context() != null ? ModKit.context() : context;
        app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** Test hook: simulate a dead process without killing the diagnostics activity. */
    static void injectDeadPasteForTest(Context context, String destination, List<String> reserved) throws Exception {
        synchronized (LOCK) {
            if (DEFAULT_PREFS.equals(prefsName)) throw new IllegalStateException("Test store required");
            SharedPreferences store = prefs(context);
            JSONObject all = readObject(store, KEY_PASTE);
            JSONArray names = new JSONArray();
            for (String path : reserved) names.put(path);
            all.put("dead-test:1", new JSONObject().put("proc", "dead-test").put("dest", destination)
                    .put("at", System.currentTimeMillis()).put("reserved", names));
            store.edit().putString(KEY_PASTE, all.toString()).commit();
        }
    }

    private static SharedPreferences prefs(Context context) {
        Context app = ModKit.context();
        if (app == null) app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        return app.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    private static List<ClipItem> unique(List<ClipItem> items) {
        Map<String, ClipItem> byKey = new LinkedHashMap<>();
        for (ClipItem item : items) {
            if (item != null && item.path() != null && !byKey.containsKey(item.key())) byKey.put(item.key(), item);
        }
        return new ArrayList<>(byKey.values());
    }

    /** Never returns null. An unreadable store is removed and read as empty. */
    private static State read(Context context) {
        SharedPreferences prefs = prefs(context);
        String raw = prefs.getString(KEY_ITEMS, null);
        if (raw == null) return empty();
        try {
            if (!Mode.CUT.name().equals(prefs.getString(KEY_MODE, Mode.CUT.name()))) throw new IllegalStateException("mode");
            JSONArray array = new JSONArray(raw);
            List<ClipItem> items = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                boolean dir = o.optBoolean("d", false);
                // An entry written before folders were measured has no count: a folder stays unknown (refused as
                // too large until it is cut again), a file counts as 1.
                items.add(new ClipItem(o.getString("p"), o.optLong("i", 0), o.optString("n", ""),
                        o.optLong("s", 0), dir, o.optInt("c", dir ? ClipItem.UNKNOWN_COUNT : 1),
                        o.optBoolean("o", false)));
            }
            return new State(Mode.CUT, items, prefs.getLong(KEY_CREATED, 0));
        } catch (Throwable t) {
            DiagLog.e(AREA, "Clipboard unreadable, cleared", t);
            prefs.edit().remove(KEY_MODE).remove(KEY_CREATED).remove(KEY_ITEMS).apply();
            return empty();
        }
    }

    /** Stores the items with a change time that is later than {@code previous} even within one millisecond. */
    private static void write(Context context, Mode mode, List<ClipItem> items, long previous) {
        try {
            long time = Math.max(System.currentTimeMillis(), previous + 1);
            prefs(context).edit().putString(KEY_MODE, mode.name()).putLong(KEY_CREATED, time)
                    .putString(KEY_ITEMS, json(items)).apply();
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot store the clipboard", t);
        }
    }

    private static String json(List<ClipItem> items) throws Exception {
        JSONArray array = new JSONArray();
        for (ClipItem item : items) {
            array.put(new JSONObject().put("p", item.path()).put("i", item.mediaId()).put("n", item.name())
                    .put("s", item.size()).put("d", item.isDir()).put("c", item.fileCount())
                    .put("o", item.overLimit()));
        }
        return array.toString();
    }

    /** Test hook (same package): stores a state exactly as given, including its time. */
    static void restore(Context context, State state) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs(context).edit();
            try {
                if (state.isEmpty()) {
                    editor.remove(KEY_MODE).remove(KEY_CREATED).remove(KEY_ITEMS);
                } else {
                    editor.putString(KEY_MODE, state.mode.name()).putLong(KEY_CREATED, state.createdAt)
                            .putString(KEY_ITEMS, json(state.items));
                }
            } catch (Throwable t) {
                DiagLog.e(AREA, "Cannot restore the clipboard", t);
                return;
            }
            editor.commit();
        }
        notifyListeners();
    }

    // The paste UI also signals transient changes such as a running paste or a setting change.
    static void notifyListeners() {
        if (LISTENERS.isEmpty()) return;
        Runnable run = () -> {
            for (Runnable listener : LISTENERS) {
                try {
                    listener.run();
                } catch (Throwable t) {
                    DiagLog.e(AREA, "clipboard listener failed", t);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) run.run();
        else new Handler(Looper.getMainLooper()).post(run);
    }
}
