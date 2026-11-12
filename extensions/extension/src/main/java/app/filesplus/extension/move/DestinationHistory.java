package app.filesplus.extension.move;

import android.content.SharedPreferences;
import android.os.Looper;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModSettings;
import app.filesplus.extension.browse.Location;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Pinned and recently used move destinations, stored newline-separated in the mod preferences as
 * {@link Location} strings (a plain absolute path is a local location, so older histories read as before).
 * Lines that do not parse are dropped when read.
 */
public final class DestinationHistory {
    private static final String AREA = "move";
    private static final String KEY_PINNED = "move.pinned";
    private static final String KEY_RECENT = "move.recent";
    private static final int MAX_RECENT = 20;

    private DestinationHistory() {}

    // ---- Location API ------------------------------------------------------------------------------------------

    public static synchronized List<Location> pinnedLocations() {
        try {
            return read(KEY_PINNED);
        } catch (Throwable t) {
            DiagLog.e(AREA, "pinned failed", t);
            return new ArrayList<>();
        }
    }

    public static synchronized List<Location> recentLocations() {
        try {
            return read(KEY_RECENT);
        } catch (Throwable t) {
            DiagLog.e(AREA, "recent failed", t);
            return new ArrayList<>();
        }
    }

    /** Pinned first, then recent, without duplicates. */
    public static synchronized List<Location> orderedLocations() {
        try {
            LinkedHashSet<Location> all = new LinkedHashSet<>(read(KEY_PINNED));
            all.addAll(read(KEY_RECENT));
            return new ArrayList<>(all);
        } catch (Throwable t) {
            DiagLog.e(AREA, "ordered failed", t);
            return new ArrayList<>();
        }
    }

    /**
     * Call when at least one file reached the destination (a failed move must not leave its destination in the
     * history). Puts it at the top of the recent list; duplicates collapse and the list is capped.
     */
    public static synchronized void recordSuccess(Location destination) {
        try {
            if (destination == null) return;
            List<Location> list = read(KEY_RECENT);
            list.remove(destination);
            list.add(0, destination);
            while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
            write(KEY_RECENT, list);
        } catch (Throwable t) {
            DiagLog.e(AREA, "record failed", t);
        }
    }

    public static synchronized void setPinned(Location destination, boolean pinned) {
        try {
            if (destination == null) return;
            List<Location> list = read(KEY_PINNED);
            list.remove(destination);
            if (pinned) list.add(destination);
            write(KEY_PINNED, list);
            if (!pinned) {
                // Keep the folder visible: an unpinned entry goes to the front of the recent list.
                List<Location> recent = read(KEY_RECENT);
                recent.remove(destination);
                recent.add(0, destination);
                while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
                write(KEY_RECENT, recent);
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "setPinned failed", t);
        }
    }

    /**
     * Whether the destination still exists. Does file I/O: never call it on the UI thread. An SMB destination
     * is reported as existing (it cannot be checked without a connection; the move itself fails safely).
     */
    public static boolean exists(Location destination) {
        if (destination == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Destination check must run off the UI thread");
        }
        return !destination.isLocal() || destination.file().isDirectory();
    }

    // ---- String API (local paths), kept until the old move sheet is replaced ----------------------------------

    public static synchronized List<String> pinned() {
        return strings(pinnedLocations());
    }

    public static synchronized List<String> recent() {
        return strings(recentLocations());
    }

    /** Pinned first, then recent, without duplicates. */
    public static synchronized List<String> ordered() {
        return strings(orderedLocations());
    }

    /** Puts the folder at the top of the recent list (local path string; same rules as recordSuccess). */
    public static synchronized void record(String folderPath) {
        recordSuccess(Location.parse(folderPath));
    }

    public static synchronized void setPinned(String folderPath, boolean pinned) {
        setPinned(Location.parse(folderPath), pinned);
    }

    private static List<String> strings(List<Location> locations) {
        List<String> out = new ArrayList<>(locations.size());
        for (Location location : locations) out.add(location.toString());
        return out;
    }

    private static List<Location> read(String key) {
        List<Location> out = new ArrayList<>();
        SharedPreferences prefs = ModSettings.prefs();
        if (prefs == null) return out; // Before ModKit.init: nothing stored yet.
        String raw = prefs.getString(key, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            Location location = Location.parse(line);
            if (location != null && !out.contains(location)) out.add(location);
        }
        return out;
    }

    private static void write(String key, List<Location> list) {
        SharedPreferences prefs = ModSettings.prefs();
        if (prefs == null) return;
        List<String> lines = strings(list);
        prefs.edit().putString(key, String.join("\n", lines)).apply();
    }
}
