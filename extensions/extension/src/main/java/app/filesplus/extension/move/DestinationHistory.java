package app.filesplus.extension.move;

import android.content.SharedPreferences;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Pinned and recently used move destinations, stored newline-separated in the mod preferences. */
public final class DestinationHistory {
    private static final String AREA = "move";
    private static final String KEY_PINNED = "move.pinned";
    private static final String KEY_RECENT = "move.recent";
    private static final int MAX_RECENT = 20;

    private DestinationHistory() {}

    public static synchronized List<String> pinned() {
        try {
            return read(KEY_PINNED);
        } catch (Throwable t) {
            DiagLog.e(AREA, "pinned failed", t);
            return new ArrayList<>();
        }
    }

    public static synchronized List<String> recent() {
        try {
            return read(KEY_RECENT);
        } catch (Throwable t) {
            DiagLog.e(AREA, "recent failed", t);
            return new ArrayList<>();
        }
    }

    /** Pinned first, then recent, without duplicates. */
    public static synchronized List<String> ordered() {
        try {
            LinkedHashSet<String> all = new LinkedHashSet<>(read(KEY_PINNED));
            all.addAll(read(KEY_RECENT));
            return new ArrayList<>(all);
        } catch (Throwable t) {
            DiagLog.e(AREA, "ordered failed", t);
            return new ArrayList<>();
        }
    }

    /** Puts the folder at the top of the recent list (duplicates collapse, list is capped). */
    public static synchronized void record(String folderPath) {
        try {
            String path = normalize(folderPath);
            if (path.isEmpty()) return;
            List<String> list = read(KEY_RECENT);
            list.remove(path);
            list.add(0, path);
            while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
            write(KEY_RECENT, list);
        } catch (Throwable t) {
            DiagLog.e(AREA, "record failed", t);
        }
    }

    public static synchronized void setPinned(String folderPath, boolean pinned) {
        try {
            String path = normalize(folderPath);
            if (path.isEmpty()) return;
            List<String> list = read(KEY_PINNED);
            list.remove(path);
            if (pinned) list.add(path);
            write(KEY_PINNED, list);
        } catch (Throwable t) {
            DiagLog.e(AREA, "setPinned failed", t);
        }
    }

    /** Trims, drops trailing separators (except for the root) and rejects line breaks. */
    private static String normalize(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.indexOf('\n') >= 0 || p.indexOf('\r') >= 0) return "";
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static List<String> read(String key) {
        List<String> out = new ArrayList<>();
        String raw = ModSettings.prefs().getString(key, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String p = normalize(line);
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private static void write(String key, List<String> list) {
        SharedPreferences.Editor editor = ModSettings.prefs().edit();
        editor.putString(key, String.join("\n", list));
        editor.apply();
    }
}
