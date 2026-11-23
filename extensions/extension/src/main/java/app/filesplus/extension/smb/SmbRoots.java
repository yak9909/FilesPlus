package app.filesplus.extension.smb;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.settings.SettingsHooks;
import app.filesplus.extension.ui.OriginalScreens;
import app.filesplus.extension.ui.ProtoNode;
import app.filesplus.extension.ui.ScreenArgs;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native All-storage roots and the read-only controls for the SMB DocumentsProvider. */
public final class SmbRoots {
    private static final String HOME = "com.google.android.apps.nbu.files.home.HomeActivity";
    private static final String AUTHORITY_SUFFIX = ".smb.documents";
    private static final String[] WRITE_ITEMS = {
            "delete_action", "rename_action", "move_to_action", "copy_to_action", "compress_action",
            "new_folder_action", "move_to_trash_action", "backup_to_google_drive", "move_into_safe_folder",
            "restore_action", "add_to_favorites", "remove_from_favorites", "favorites"
    };
    private static final String[] READ_LABELS = {
            "file_browser_popup_select", "home_share", "quick_share", "file_browser_menu_item_open_with",
            "file_browser_menu_item_file_info", "file_browser_menu_item_folder_info"
    };

    private SmbRoots() {}

    public static boolean installed() { return false; }

    public static void hookError(Throwable error) {
        DiagLog.e("smb", "Storage browser hook failed", error);
    }

    static String authority(String packageName) {
        return packageName + AUTHORITY_SUFFIX;
    }

    static byte[] rowBytes(String label, String treeUri) {
        return new ProtoNode().setMessage(1, new ProtoNode().setMessage(1, folder(label, treeUri))
                .setVarint(2, 1)).encode();
    }

    private static ProtoNode folder(String label, String treeUri) {
        return new ProtoNode().setString(1, label).setString(2, treeUri).setString(3, treeUri)
                .setVarint(10, OriginalScreens.KIND_UNKNOWN);
    }

    static boolean isLocation(String packageName, String location) {
        if (packageName == null || location == null) return false;
        try {
            URI uri = URI.create(location);
            return "content".equals(uri.getScheme()) && authority(packageName).equals(uri.getRawAuthority());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    public static boolean isProviderUri(Uri uri, Context context) {
        try {
            return uri != null && context != null && isLocation(context.getPackageName(), uri.toString());
        } catch (Throwable error) {
            hookError(error);
            return false;
        }
    }

    private static Root root(Context context, SmbConnection connection) {
        if (connection.id.isEmpty() || connection.id.indexOf(':') >= 0 || connection.label().isEmpty()) {
            throw new IllegalArgumentException("Invalid saved SMB root");
        }
        String tree = DocumentsContract.buildTreeDocumentUri(authority(context.getPackageName()),
                SmbDocId.root(connection.id)).toString();
        return new Root(connection.label(), tree);
    }

    private static final class Root {
        final String label;
        final String tree;

        Root(String label, String tree) {
            this.label = label;
            this.tree = tree;
        }
    }

    /** The original list belongs to the loader. Give the adapter a new snapshot only if a row was added. */
    public static List<?> append(List<?> original, Activity host) {
        try {
            if (host == null || !HOME.equals(host.getClass().getName()) || original == null || original.isEmpty()) {
                return original;
            }
            List<Object> rows = new ArrayList<>(original);
            Set<String> locations = new HashSet<>();
            for (SmbConnection connection : SmbConnectionStore.all()) {
                try {
                    Root root = root(host, connection);
                    if (!locations.add(root.tree)) throw new IllegalArgumentException("Duplicate SMB root");
                    Object row = OriginalScreens.parseLike(original.get(0), rowBytes(root.label, root.tree));
                    if (!validRow(row, root.label, root.tree)) throw new IllegalArgumentException("SMB row mismatch");
                    rows.add(row);
                } catch (Throwable error) {
                    hookError(error);
                }
            }
            return rows.size() == original.size() ? original : rows;
        } catch (Throwable error) {
            hookError(error);
            return original;
        }
    }

    /** The patch fills this with reads of the exact fields used by the native row binder. */
    private static boolean validRow(Object row, String label, String tree) { return false; }

    public static boolean openFirst(Activity host) {
        try {
            List<SmbConnection> saved = SmbConnectionStore.all();
            if (saved.isEmpty()) {
                Toast.makeText(host, SettingsHooks.isJapanese(host) ? "保存済みの SMB 接続がありません"
                        : "No saved SMB connections", Toast.LENGTH_SHORT).show();
                return false;
            }
            Root root = root(host, saved.get(0));
            return OriginalScreens.openStorageRoot(host, folder(root.label, root.tree));
        } catch (Throwable error) {
            hookError(error);
            return false;
        }
    }

    public static boolean selfTestBrokenRoot(Activity host) {
        try {
            return !OriginalScreens.openStorageRoot(host, folder("Broken SMB root", ""));
        } catch (Throwable error) {
            hookError(error);
            return false;
        }
    }

    public static boolean isFolder(byte[] folder) {
        try {
            return isEntry(folder, 2);
        } catch (Throwable error) {
            hookError(error);
            return false;
        }
    }

    public static boolean isFolderProto(Object folder) {
        try {
            return installed() && isFolder(ScreenArgs.serialize(folder));
        } catch (Throwable error) {
            hookError(error);
            return false;
        }
    }

    public static boolean isFile(Object entry) {
        try {
            return installed() && isEntry(ScreenArgs.serialize(entry), 9);
        } catch (Throwable error) {
            hookError(error);
            return false;
        }
    }

    private static boolean isEntry(byte[] entry, int uriField) {
        Context context = ModKit.context();
        return installed() && context != null && entry != null
                && isLocation(context.getPackageName(), ProtoNode.decode(entry).getString(uriField));
    }

    public static void filterMenu(Menu menu, Object folder, Object file) {
        try {
            if (installed() && (isFile(file) || isFolderProto(folder))) hideWrites(menu);
        } catch (Throwable error) {
            hookError(error);
        }
    }

    private static void hideWrites(Menu menu) {
        Context context = ModKit.context();
        if (menu == null || context == null) return;
        for (String item : WRITE_ITEMS) {
            int resource = id(context, "id", item);
            if (resource == 0) continue;
            MenuItem entry = menu.findItem(resource);
            if (entry != null) entry.setVisible(false);
        }
    }

    public static List<?> filterList(List<?> original, Object entry, boolean folder, Context context) {
        try {
            if (!isEntry(ScreenArgs.serialize(entry), folder ? 2 : 9)) return original;
            Set<String> readLabels = new HashSet<>();
            for (String name : READ_LABELS) {
                int id = id(context, "string", name);
                if (id != 0) readLabels.add(context.getString(id));
            }
            List<Object> allowed = new ArrayList<>();
            for (Object row : original) {
                if (readLabels.contains(rowTitle(row))) allowed.add(row);
            }
            return allowed;
        } catch (Throwable error) {
            hookError(error);
            return original;
        }
    }

    /** The patch fills this with a read of the native row's title field. */
    private static String rowTitle(Object row) { return null; }

    /** Returns true when the native FAB update should be skipped. */
    public static boolean hideFab(View root, Object folder) {
        try {
            if (root == null || !isFolderProto(folder)) return false;
            Context context = root.getContext();
            for (String name : new String[]{"create_new_folder", "create_new_folder_expressive"}) {
                View button = root.findViewById(id(context, "id", name));
                if (button != null) {
                    button.setVisibility(View.GONE);
                    button.setEnabled(false);
                }
            }
            return true;
        } catch (Throwable error) {
            hookError(error);
            return false;
        }
    }

    private static int id(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }
}
