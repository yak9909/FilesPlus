package app.filesplus.extension.move;

import android.content.SharedPreferences;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModSettings;

import java.io.File;

/**
 * The last folder that something was pasted into (the "last pasted folder" setting of the "All" tab). The pins and
 * the recent list are gone; the old {@code move.pinned} / {@code move.recent} values stay in the preferences
 * untouched and are only read as a fallback when nothing was recorded yet.
 */
public final class DestinationHistory {
    private static final String AREA = "move";
    private static final String KEY_LAST = "move.last";
    /** Old newline-separated history; its first line is the folder used last. */
    private static final String KEY_OLD_RECENT = "move.recent";

    private DestinationHistory() {}

    /** Call when at least one item reached the folder. */
    public static synchronized void recordLast(File folder) {
        try {
            SharedPreferences prefs = ModSettings.prefs();
            if (folder == null || prefs == null) return; // Before ModKit.init there is nothing to store into.
            prefs.edit().putString(KEY_LAST, folder.getPath()).apply();
        } catch (Throwable t) {
            DiagLog.e(AREA, "recordLast failed", t);
        }
    }

    /** The stored "last pasted folder" exactly as written (no fallback), or null. For the self test. */
    public static synchronized String rawLast() {
        SharedPreferences prefs = ModSettings.prefs();
        return prefs == null ? null : prefs.getString(KEY_LAST, null);
    }

    /** Puts a value from {@link #rawLast} back (null removes it). For the self test. */
    public static synchronized void restoreRawLast(String raw) {
        SharedPreferences prefs = ModSettings.prefs();
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (raw == null) editor.remove(KEY_LAST);
        else editor.putString(KEY_LAST, raw);
        editor.commit();
    }

    /** The last pasted folder (it may no longer exist: check before use), or null when there is none. */
    public static synchronized File last() {
        try {
            SharedPreferences prefs = ModSettings.prefs();
            if (prefs == null) return null;
            String path = prefs.getString(KEY_LAST, null);
            if (path == null || path.isEmpty()) {
                String old = prefs.getString(KEY_OLD_RECENT, "");
                int end = old == null ? -1 : old.indexOf('\n');
                path = old == null ? "" : (end < 0 ? old : old.substring(0, end)).trim();
                if (!path.startsWith("/")) return null; // An SMB line or garbage is not a local folder.
            }
            return path.isEmpty() ? null : new File(path);
        } catch (Throwable t) {
            DiagLog.e(AREA, "last failed", t);
            return null;
        }
    }
}
