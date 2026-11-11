package app.filesplus.extension.smb;

import android.content.Context;
import android.content.SharedPreferences;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Saved SMB connections in the private preferences file "filesplus_smb". The connection list is a JSON array
 * without passwords; each password is a separate "pw.&lt;id&gt;" entry holding an {@link SmbSecretBox} blob.
 * Passwords and blobs are never logged. If the stored list cannot be parsed, readers see an empty list and
 * every writer refuses to run, so the raw data is never overwritten.
 */
public final class SmbConnectionStore {
    private static final String AREA = "smb";
    private static final String PREFS = "filesplus_smb";
    private static final String KEY_LIST = "connections";
    private static final String PW_PREFIX = "pw.";

    private SmbConnectionStore() {}

    /** Saved connections for display; unreadable data gives an empty list (nothing is lost, see class comment). */
    public static synchronized List<SmbConnection> all() {
        try {
            SharedPreferences prefs = prefs();
            return prefs == null ? new ArrayList<>() : parse(prefs.getString(KEY_LIST, "[]"), false);
        } catch (Throwable t) {
            DiagLog.i(AREA, "Saved connections are unreadable (" + t.getClass().getSimpleName() + ")");
            return new ArrayList<>();
        }
    }

    public static synchronized SmbConnection get(String id) {
        for (SmbConnection c : all()) if (c.id.equals(id)) return c;
        return null;
    }

    /**
     * Adds or replaces a connection. passwordOrNullToKeep: null keeps the stored password, "" removes it,
     * anything else is stored encrypted. Returns false (and stores nothing) when saving fails, including
     * when the password cannot be encrypted or the stored list is corrupt. May touch the Keystore: call off
     * the main thread. The stored lastPath wins unless host or share changed (then it is reset).
     */
    public static boolean save(SmbConnection c, String passwordOrNullToKeep) {
        String blob = null;
        if (passwordOrNullToKeep != null && !passwordOrNullToKeep.isEmpty()) {
            // Keystore work stays outside the store lock so readers on the UI thread never wait for it.
            blob = SmbSecretBox.encrypt(passwordOrNullToKeep);
            if (blob == null) return false;
        }
        return write(c, passwordOrNullToKeep, blob);
    }

    private static synchronized boolean write(SmbConnection c, String passwordOrNullToKeep, String blob) {
        try {
            SharedPreferences prefs = prefs();
            if (prefs == null) return false;
            List<SmbConnection> list = parse(prefs.getString(KEY_LIST, "[]"), true);
            int index = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(c.id)) index = i;
            boolean sessionChanged = passwordOrNullToKeep != null;
            if (index >= 0) {
                SmbConnection old = list.get(index);
                // A folder written by the browser since the dialog opened must survive the save.
                c = c.withLastPath(old.host.equals(c.host) && old.share.equals(c.share) ? old.lastPath : "");
                sessionChanged |= !old.targetKey().equals(c.targetKey());
                list.set(index, c);
            } else {
                list.add(c);
            }
            // One editor, so the list and the password change together.
            SharedPreferences.Editor editor = prefs.edit().putString(KEY_LIST, toJson(list));
            if (blob != null) editor.putString(PW_PREFIX + c.id, blob);
            else if (passwordOrNullToKeep != null) editor.remove(PW_PREFIX + c.id);
            if (!editor.commit()) return false;
            // A pure rename or folder change keeps the live session.
            if (sessionChanged) SmbVfs.invalidate(c.id);
            SmbDocumentsProvider.notifyRootsChanged();
            return true;
        } catch (JSONException e) {
            DiagLog.i(AREA, "Saved connections are unreadable; not overwriting them");
            return false;
        } catch (Throwable t) {
            DiagLog.i(AREA, "Cannot save connection: " + t.getClass().getSimpleName());
            return false;
        }
    }

    public static synchronized void delete(String id) {
        try {
            SharedPreferences prefs = prefs();
            if (prefs == null) return;
            List<SmbConnection> list = parse(prefs.getString(KEY_LIST, "[]"), true);
            for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
            prefs.edit().putString(KEY_LIST, toJson(list)).remove(PW_PREFIX + id).apply();
            SmbVfs.invalidate(id);
            SmbDocumentsProvider.notifyRootsChanged();
        } catch (JSONException e) {
            DiagLog.i(AREA, "Saved connections are unreadable; not deleting");
        } catch (Throwable t) {
            DiagLog.i(AREA, "Cannot delete connection: " + t.getClass().getSimpleName());
        }
    }

    /** True when a password blob is stored (it may still be unreadable, see {@link #passwordReadable}). */
    public static synchronized boolean hasPassword(String id) {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.contains(PW_PREFIX + id);
    }

    /** The decrypted password, or null when none is stored or it cannot be decrypted. */
    public static String password(String id) {
        return SmbSecretBox.decrypt(blob(id)); // Decrypt outside the store lock.
    }

    private static synchronized String blob(String id) {
        SharedPreferences prefs = prefs();
        return prefs == null ? null : prefs.getString(PW_PREFIX + id, null);
    }

    /** True when a stored password decrypts. Uses the Keystore: call off the main thread. */
    public static boolean passwordReadable(String id) {
        return password(id) != null;
    }

    /** Remembers the last browsed folder; does not touch sessions or the password. */
    public static synchronized void setLastPath(String id, String path) {
        patch(id, false, path);
    }

    /** Renames a saved connection without touching sessions or the password (async write, safe on the UI thread). */
    public static synchronized void setDisplayName(String id, String name) {
        patch(id, true, name);
    }

    private static void patch(String id, boolean rename, String value) {
        try {
            SharedPreferences prefs = prefs();
            if (prefs == null) return;
            List<SmbConnection> list = parse(prefs.getString(KEY_LIST, "[]"), true);
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                SmbConnection c = list.get(i);
                if (!c.id.equals(id)) continue;
                SmbConnection updated = rename ? c.withDisplayName(value) : c.withLastPath(value);
                if (updated.displayName.equals(c.displayName) && updated.lastPath.equals(c.lastPath)) continue;
                list.set(i, updated);
                changed = true;
            }
            if (changed) {
                prefs.edit().putString(KEY_LIST, toJson(list)).apply();
                if (rename) SmbDocumentsProvider.notifyRootsChanged();
            }
        } catch (JSONException e) {
            DiagLog.i(AREA, "Saved connections are unreadable; not updating them");
        } catch (Throwable t) {
            DiagLog.i(AREA, "Cannot update connection: " + t.getClass().getSimpleName());
        }
    }

    private static SharedPreferences prefs() {
        Context context = ModKit.context();
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Strict parsing throws on any damaged entry (so writers never drop data); lenient parsing skips it. */
    private static List<SmbConnection> parse(String raw, boolean strict) throws JSONException {
        List<SmbConnection> out = new ArrayList<>();
        JSONArray array = new JSONArray(raw);
        for (int i = 0; i < array.length(); i++) {
            SmbConnection c = fromJson(array.optJSONObject(i));
            if (c != null) out.add(c);
            else if (strict) throw new JSONException("damaged entry");
        }
        return out;
    }

    private static String toJson(List<SmbConnection> list) throws JSONException {
        JSONArray array = new JSONArray();
        for (SmbConnection c : list) {
            array.put(new JSONObject().put("id", c.id).put("name", c.displayName).put("host", c.host)
                    .put("port", c.port).put("share", c.share).put("start", c.startPath).put("user", c.user)
                    .put("domain", c.domain).put("open", c.openWhere).put("last", c.lastPath));
        }
        return array.toString();
    }

    private static SmbConnection fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        String host = o.optString("host", "");
        String share = o.optString("share", "");
        if (id.isEmpty() || host.isEmpty() || share.isEmpty()) return null;
        return new SmbConnection(id, o.optString("name", ""), host, o.optInt("port", SmbClientFactory.DEFAULT_PORT), share,
                o.optString("start", ""), o.optString("user", ""), o.optString("domain", ""),
                o.optString("open", SmbConnection.OPEN_LAST), o.optString("last", ""));
    }
}
