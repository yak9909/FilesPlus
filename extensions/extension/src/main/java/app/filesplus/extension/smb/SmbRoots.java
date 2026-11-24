package app.filesplus.extension.smb;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import android.widget.TextView;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.settings.SettingsHooks;
import app.filesplus.extension.settings.SettingsPages;
import app.filesplus.extension.ui.OriginalScreens;
import app.filesplus.extension.ui.ProtoNode;
import app.filesplus.extension.ui.ScreenArgs;

import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Native All-storage roots and the read-only controls for the SMB DocumentsProvider. */
public final class SmbRoots {
    private static final String HOME = "com.google.android.apps.nbu.files.home.HomeActivity";
    private static final AtomicLong REVISION = new AtomicLong();
    // The observer and its list are owned by the native Home fragment.
    private static final Map<Activity, HomeList> HOMES = new WeakHashMap<>();
    private static final Map<Activity, Browser> BROWSERS = new WeakHashMap<>();
    private static final Set<Activity> RESUMED = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean trackingActivities;
    // Both native catalogue deliveries run synchronously on the main thread.
    private static boolean bareRefresh;

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

    /** Implemented by the native All storage catalogue observer. */
    public interface RootRefresh {
        void modKitRefreshSmbRoots(List<?> original);
    }

    private static final class HomeList {
        final WeakReference<RootRefresh> observer;
        final List<?> original;
        final long revision;

        HomeList(RootRefresh observer, List<?> original, long revision) {
            this.observer = new WeakReference<>(observer);
            this.original = original;
            this.revision = revision;
        }
    }

    private static final class Browser {
        final String id;
        final SmbConnection opened;
        final long revision;
        boolean dirty;

        Browser(String id, SmbConnection opened, long revision) {
            this.id = id;
            this.opened = opened;
            this.revision = revision;
        }
    }

    public static long revision() {
        return REVISION.get();
    }

    /** Called on the UI thread when a Home list, SMB browser or SMB settings list is created. */
    public static void trackActivities(Activity host) {
        try {
            if (trackingActivities) return;
            host.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle state) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityResumed(Activity activity) {
                    try {
                        RESUMED.add(activity);
                        // Let the native Activity finish its own resume dispatch before relaunching a screen.
                        new Handler(Looper.getMainLooper()).post(() -> refresh(activity));
                    } catch (Throwable t) { hookError(t); }
                }
                @Override public void onActivityPaused(Activity activity) { RESUMED.remove(activity); }
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                @Override public void onActivityDestroyed(Activity activity) {
                    RESUMED.remove(activity);
                    HOMES.remove(activity);
                    BROWSERS.remove(activity);
                }
            });
            trackingActivities = true;
            // The first Home subscription can emit after its Activity has already resumed.
            if (host.hasWindowFocus()) RESUMED.add(host);
        } catch (Throwable t) { hookError(t); }
    }

    /** The store calls this after writing and invalidating the connection. */
    static void connectionChanged(String id) {
        try {
            long revision = REVISION.incrementAndGet();
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    for (Browser browser : BROWSERS.values()) {
                        if (browser.id.equals(id) && browser.revision < revision) browser.dirty = true;
                    }
                    for (Activity host : new ArrayList<>(RESUMED)) refresh(host);
                } catch (Throwable t) { hookError(t); }
            });
        } catch (Throwable t) { hookError(t); }
    }

    private static void refresh(Activity host) {
        try {
            if (host == null || !RESUMED.contains(host) || host.isFinishing() || host.isDestroyed()) return;
            HomeList home = HOMES.get(host);
            if (home != null && home.revision != revision()) {
                RootRefresh observer = home.observer.get();
                if (observer == null) HOMES.remove(host);
                else {
                    replayHomeRoots(observer, home.original);
                    DiagLog.i("smb", "Home roots refreshed rev=" + revision());
                }
            }
            SettingsPages.refreshSmbList(host);
            Browser browser = BROWSERS.get(host);
            if (browser == null || !browser.dirty) return;
            browser.dirty = false;
            SmbConnection connection = SmbConnectionStore.get(browser.id);
            if (connection == null || reopen(host, connection)) host.finish();
        } catch (Throwable t) { hookError(t); }
    }

    private static boolean reopen(Activity host, SmbConnection connection) {
        Root root = root(host, connection);
        return OriginalScreens.openStorageRoot(host, folder(root.label, root.tree));
    }

    static void replayHomeRoots(RootRefresh observer, List<?> original) {
        // The native diff compares stable URI and free space. Remove and reinsert the SMB rows to force a bind.
        bareRefresh = true;
        try {
            observer.modKitRefreshSmbRoots(original);
        } finally {
            bareRefresh = false;
        }
        observer.modKitRefreshSmbRoots(original);
    }

    static boolean shouldAppendHomeRoots(String hostName, List<?> original) {
        return !bareRefresh && HOME.equals(hostName) && original != null && !original.isEmpty();
    }

    /** The original list belongs to the loader. Give the adapter a new snapshot only if a row was added. */
    public static List<?> append(List<?> original, Activity host, RootRefresh observer) {
        try {
            if (host == null || !shouldAppendHomeRoots(host.getClass().getName(), original)) return original;
            if (observer != null) {
                trackActivities(host);
                HOMES.put(host, new HomeList(observer, original, revision()));
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

    /** Restores the saved spelling at the native All storage title view. */
    public static void bindRootLabel(View view, Object folder) {
        try {
            String label = folderLabel(ScreenArgs.serialize(folder), view.getContext());
            if (label == null) return;
            View title = view.findViewById(id(view.getContext(), "id", "title"));
            if (title instanceof TextView) ((TextView) title).setText(label);
        } catch (Throwable t) { hookError(t); }
    }

    /** The toolbar uses the first folder in the same native stack as its breadcrumb. */
    public static void bindBrowserTitle(Activity host, Object currentFolder) {
        try {
            byte[] bytes = ScreenArgs.serialize(currentFolder);
            if (!isEntry(bytes, 2)) return;
            ProtoNode root = launchRoot(host);
            if (root == null) return;
            String title = folderLabel(root.encode(), host);
            if (title != null) host.setTitle(title);
            String location = ProtoNode.decode(bytes).getString(2);
            Uri uri = Uri.parse(location);
            SmbDocId doc = SmbDocId.decode(DocumentsContract.isDocumentUri(host, uri)
                    ? DocumentsContract.getDocumentId(uri) : DocumentsContract.getTreeDocumentId(uri));
            Browser browser = BROWSERS.get(host);
            if (browser != null && browser.opened != null && browser.id.equals(doc.connectionId)) {
                SmbConnectionStore.setLastPath(browser.opened, doc.path);
            }
        } catch (Throwable t) { hookError(t); }
    }

    public static List<?> fixBreadcrumb(List<?> original, Object hut, Context context) {
        try {
            List<ProtoNode> stack = ProtoNode.decode(ScreenArgs.serialize(hut)).getMessages(2);
            if (stack.isEmpty() || original.isEmpty()) return original;
            String label = folderLabel(stack.get(0).encode(), context);
            if (label == null) return original;
            List<Object> copy = new ArrayList<>(original);
            copy.set(0, label);
            return copy;
        } catch (Throwable t) {
            hookError(t);
            return original;
        }
    }

    private static String folderLabel(byte[] bytes, Context context) {
        if (bytes == null || context == null) return null;
        ProtoNode folder = ProtoNode.decode(bytes);
        return isLocation(context.getPackageName(), folder.getString(2)) ? folder.getString(1) : null;
    }

    private static ProtoNode launchRoot(Activity host) {
        byte[] bytes = ScreenArgs.readExtra(host.getIntent(), ScreenArgs.EXTRA);
        if (bytes == null) return null;
        ProtoNode hut = ProtoNode.decode(bytes).getMessage(1);
        return hut == null ? null : hut.getMessage(1);
    }

    /** Resolves the latest settings before the app parses an SMB browser's launch arguments. */
    public static void prepareBrowser(Activity host, Bundle state) {
        try {
            long revision = revision();
            ProtoNode root = launchRoot(host);
            if (root == null || !isLocation(host.getPackageName(), root.getString(2))) return;
            Uri tree = Uri.parse(root.getString(2));
            SmbDocId doc = SmbDocId.decode(DocumentsContract.getTreeDocumentId(tree));
            SmbConnection connection = SmbConnectionStore.get(doc.connectionId);
            trackActivities(host);
            Browser browser = new Browser(doc.connectionId, connection, revision);
            BROWSERS.put(host, browser);
            if (connection == null) {
                browser.dirty = true;
                return;
            }
            if (state != null) return;
            byte[] original = ScreenArgs.readExtra(host.getIntent(), ScreenArgs.EXTRA);
            ProtoNode request = ProtoNode.decode(original);
            ProtoNode hut = request.getMessage(1);
            List<ProtoNode> stack = hut.getMessages(2);
            root.setString(1, connection.label());
            hut.setMessage(1, root).setMessage(2, root);
            if (stack.size() > 1) {
                for (int i = 1; i < stack.size(); i++) hut.addMessage(2, stack.get(i));
            } else {
                String path = "";
                String parent = tree.toString();
                for (String segment : SmbPath.normalize(connection.initialPath()).split("/")) {
                    if (segment.isEmpty()) break;
                    path = SmbPath.join(path, segment);
                    String child = DocumentsContract.buildDocumentUriUsingTree(tree, SmbDocId.encode(connection.id, path))
                            .toString();
                    hut.addMessage(2, folder(segment, child).setString(3, parent));
                    parent = child;
                }
            }
            byte[] updated = request.setMessage(1, hut).encode();
            host.getIntent().putExtra(ScreenArgs.EXTRA, ScreenArgs.wrap(updated));
        } catch (Throwable t) { hookError(t); }
    }

    public static boolean openFirst(Activity host) {
        try {
            List<SmbConnection> saved = SmbConnectionStore.all();
            if (saved.isEmpty()) {
                Toast.makeText(host, SettingsHooks.isJapanese(host) ? "保存済みの SMB 接続がありません"
                        : "No saved SMB connections", Toast.LENGTH_SHORT).show();
                return false;
            }
            return reopen(host, saved.get(0));
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
