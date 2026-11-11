package app.filesplus.extension.foldertabs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.text.Html;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Per-screen folder identity shared by folder-tab actions. App types stay in the smali bridge. */
public final class FolderTabContext {
    private static final String AREA = "foldertabs";
    private static final int TAG = 0x7e464354;
    private static final int DELETE_ITEM = 0x7e464344;
    private static final Uri FILES = MediaStore.Files.getContentUri("external");
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> STANDARD = new HashSet<>(Arrays.asList(
            "dcim", "download", "movies", "pictures", "music", "documents", "alarms",
            "notifications", "podcasts", "ringtones", "recordings", "audiobooks"));

    private final WeakReference<View> root;
    private final WeakReference<Runnable> reload;
    // A null value is also cached: aggregate, ambiguous and inaccessible tabs stay unavailable.
    private final Map<String, String> folders = new HashMap<>();
    private final Set<String> resolving = new HashSet<>();
    private WeakReference<Menu> menu = new WeakReference<>(null);
    private String currentKey;
    private String currentFolder;
    private String removedKey;
    private int generation;
    private boolean selectionMode;
    private boolean nativeEnabled = true;
    private boolean busy;

    private FolderTabContext(View view, Runnable callback) {
        root = new WeakReference<>(view);
        reload = new WeakReference<>(callback);
    }

    public static FolderTabContext attach(View root, Runnable reload) {
        try {
            FolderTabContext context = new FolderTabContext(root, reload);
            root.setTag(TAG, context);
            return context;
        } catch (Throwable t) {
            hookError(t);
            return null;
        }
    }

    public static void hookError(Throwable t) {
        DiagLog.e(AREA, "Folder-tab hook failed", t);
    }

    /** Called only by the bridge, for AND-connected filters in the app's collection identifier. */
    public static void captureFilter(Bundle out, String field, String operator, Object value) {
        try {
            if (!(value instanceof String)) return;
            String key = null;
            String text = (String) value;
            if ("PARENT_NAME".equals(field) && ("EQUALS".equals(operator)
                    || "EQUALS_IGNORE_CASE".equals(operator))) key = "name";
            if ("PARENT_PATH".equals(field)) {
                if ("EQUALS".equals(operator)) key = "path";
                // Older MediaStore implementations group by a parent path suffix instead.
                if ("ENDS_WITH".equals(operator) || "ENDS_WITH_IGNORE_CASE".equals(operator)) {
                    key = "name";
                    text = new File(text).getName();
                }
            }
            // Special collections such as Download can carry a root-relative path prefix.
            if ("PATH".equals(field) && "STARTS_WITH".equals(operator)) key = "prefix";
            if (key == null) return;
            if (out.containsKey(key) && !text.equals(out.getString(key))) out.putBoolean("invalid", true);
            out.putString(key, text);
        } catch (Throwable t) {
            out.putBoolean("invalid", true);
            hookError(t);
        }
    }

    /** Cache lookup and resolution happen on page changes, never when a menu opens. */
    public void select(String key, boolean all, Bundle descriptor) {
        try {
            currentKey = key;
            currentFolder = folders.get(key);
            updateMenu();
            if (all || key == null || descriptor == null) {
                currentFolder = null;
                updateMenu();
                return;
            }
            if (folders.containsKey(key) || !resolving.add(key)) return;
            final View view = root.get();
            if (view == null) return;
            final Context app = view.getContext().getApplicationContext();
            final int epoch = generation;
            WORKER.execute(() -> {
                String folder = null;
                try {
                    folder = resolve(app, descriptor);
                } catch (Throwable t) {
                    DiagLog.e(AREA, "Cannot resolve folder tab", t);
                }
                final String result = folder;
                MAIN.post(() -> {
                    try {
                        if (generation != epoch) return;
                        resolving.remove(key);
                        folders.put(key, result);
                        if (key.equals(currentKey)) currentFolder = result;
                        updateMenu();
                    } catch (Throwable t) { hookError(t); }
                });
            });
        } catch (Throwable t) { hookError(t); }
    }

    public String getCurrentFolder() {
        return currentFolder;
    }

    public void tabsChanged() {
        try {
            generation++;
            folders.clear();
            resolving.clear();
            currentFolder = null;
            updateMenu();
        } catch (Throwable t) { hookError(t); }
    }

    public void refresh() {
        try {
            tabsChanged();
            Runnable callback = reload.get();
            if (callback != null) callback.run();
        } catch (Throwable t) { hookError(t); }
    }

    /** The native reload bridge calls this before its adapter receives the new tab list. */
    public int refreshIndex(int current, int size) {
        try {
            int target = removedKey != null && removedKey.equals(currentKey) ? current - 1 : current;
            removedKey = null;
            return Math.max(0, Math.min(target, size - 1));
        } catch (Throwable t) {
            hookError(t);
            return current;
        }
    }

    public static void onMenu(View root, Menu menu, boolean selection) {
        try {
            if (root == null) return;
            Object tag = root.getTag(TAG);
            if (!(tag instanceof FolderTabContext)) return;
            FolderTabContext tabs = (FolderTabContext) tag;
            tabs.menu = new WeakReference<>(menu);
            tabs.selectionMode = selection;
            tabs.nativeEnabled = menu.size() == 0 || menu.getItem(0).isEnabled();
            MenuItem item = menu.findItem(DELETE_ITEM);
            if (item == null) {
                item = menu.add(Menu.NONE, DELETE_ITEM, Menu.NONE, title(root.getContext()));
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                item.setOnMenuItemClickListener(clicked -> {
                    try { tabs.confirm(); } catch (Throwable t) { hookError(t); }
                    return true;
                });
            }
            tabs.updateMenu();
        } catch (Throwable t) { hookError(t); }
    }

    private void updateMenu() {
        Menu target = menu.get();
        View view = root.get();
        if (target == null || view == null) return;
        MenuItem item = target.findItem(DELETE_ITEM);
        if (item == null) return;
        boolean available = false;
        try {
            available = currentFolder != null && deletable(view.getContext(), new File(currentFolder));
        } catch (Throwable t) { hookError(t); }
        item.setTitle(title(view.getContext()));
        item.setVisible(!selectionMode && available);
        item.setEnabled(nativeEnabled && !busy && available);
    }

    private void confirm() {
        View view = root.get();
        if (view == null || busy || currentFolder == null) return;
        final Activity activity = activity(view.getContext());
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final File folder = new File(currentFolder);
        final String key = currentKey;
        if (!deletable(activity, folder)) return;
        busy = true;
        updateMenu();
        WORKER.execute(() -> {
            try {
                int count = countFiles(folder.toPath());
                MAIN.post(() -> {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            busy = false;
                            return;
                        }
                        new AlertDialog.Builder(activity)
                                .setTitle(quantity(activity, "confirm_delete_folder_title", 1,
                                        japanese(activity) ? "フォルダを完全に削除しますか？" : "Delete folder permanently?"))
                                .setMessage(folder.getAbsolutePath() + "\n\n" + confirmation(activity, count))
                                .setNegativeButton(string(activity, "cancel", japanese(activity) ? "キャンセル" : "Cancel"), null)
                                .setPositiveButton(string(activity, "delete", japanese(activity) ? "削除" : "Delete"),
                                        (dialog, which) -> {
                                            try { delete(activity.getApplicationContext(), folder, key); }
                                            catch (Throwable t) { busy = false; hookError(t); updateMenu(); }
                                        })
                                .setOnDismissListener(dialog -> {
                                    try { if (!deleting) { busy = false; updateMenu(); } }
                                    catch (Throwable t) { hookError(t); }
                                }).show();
                    } catch (Throwable t) { busy = false; hookError(t); updateMenu(); }
                });
            } catch (Throwable t) {
                DiagLog.e(AREA, "Cannot count every file in " + folder, t);
                MAIN.post(() -> {
                    try {
                        busy = false;
                        toast(activity, japanese(activity) ? "フォルダを読み取れませんでした" : "Could not read the folder");
                        updateMenu();
                    } catch (Throwable error) { hookError(error); }
                });
            }
        });
    }

    private boolean deleting;

    private void delete(Context context, File folder, String key) {
        deleting = true;
        WORKER.execute(() -> {
            final long[] result = new long[3]; // Deleted entries, failed entries, MediaStore failures.
            final List<String> deleted = new ArrayList<>();
            try {
                // Recheck after the dialog; never follow directory symlinks, including the selected folder.
                if (!deletable(context, folder)) throw new IOException("Protected or changed folder: " + folder);
                deleteTree(context, folder, deleted, result);
            } catch (Throwable t) {
                result[1]++;
                DiagLog.e(AREA, "Folder deletion failed: " + folder, t);
            } finally {
                syncDeleted(context, deleted, result);
            }
            boolean success = !Files.exists(folder.toPath()) && result[1] == 0 && result[2] == 0;
            String report = "Folder " + folder + ": deleted=" + result[0] + ", failed=" + result[1]
                    + ", MediaStore failures=" + result[2];
            if (success) DiagLog.i(AREA, report);
            else DiagLog.e(AREA, report, null);
            MAIN.post(() -> {
                try {
                    deleting = false;
                    busy = false;
                    toast(context, success ? (japanese(context) ? "フォルダを削除しました" : "Folder deleted")
                            : (japanese(context) ? "フォルダの削除を完了できませんでした" : "Could not completely delete the folder"));
                    removedKey = key;
                    refresh();
                } catch (Throwable t) { hookError(t); }
            });
        });
    }

    private static void deleteTree(Context context, File folder, List<String> deleted, long[] result) throws IOException {
        final Path boundary = folder.toPath().toAbsolutePath().normalize();
        Files.walkFileTree(boundary, new SimpleFileVisitor<Path>() {
            private void remove(Path path) {
                try {
                    // A link encountered as a file is removed itself; its target is never traversed.
                    if (!path.equals(boundary) && !path.getParent().toRealPath().startsWith(boundary))
                        throw new IOException("Folder ancestry changed: " + path);
                    Files.delete(path);
                    result[0]++;
                    deleted.add(path.toString());
                    if (deleted.size() >= 200) syncDeleted(context, deleted, result);
                } catch (Throwable t) {
                    result[1]++;
                    DiagLog.e(AREA, "Failed to delete " + path, t);
                }
            }

            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                if (!path.toRealPath().startsWith(boundary)) throw new IOException("Folder ancestry changed: " + path);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                remove(path);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path path, IOException error) {
                result[1]++;
                DiagLog.e(AREA, "Cannot access " + path, error);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) {
                if (error != null) {
                    result[1]++;
                    DiagLog.e(AREA, "Cannot enumerate " + path, error);
                }
                remove(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void syncDeleted(Context context, List<String> paths, long[] result) {
        if (paths.isEmpty()) return;
        try {
            String[] args = paths.toArray(new String[0]);
            String placeholders = String.join(",", java.util.Collections.nCopies(args.length, "?"));
            // Exact paths only: a partial filesystem failure must never delete surviving descendants.
            context.getContentResolver().delete(FILES, "_data IN (" + placeholders + ")", args);
        } catch (Throwable t) {
            result[2]++;
            DiagLog.e(AREA, "Failed to update MediaStore for deleted paths", t);
            try { MediaScannerConnection.scanFile(context, paths.toArray(new String[0]), null, null); }
            catch (Throwable error) { DiagLog.e(AREA, "Media scanner fallback failed", error); }
        } finally {
            paths.clear();
        }
    }

    private static int countFiles(Path folder) throws IOException {
        final int[] count = {0};
        Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                return ++count[0] > 100 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    private static String resolve(Context context, Bundle descriptor) throws IOException {
        if (descriptor.getBoolean("invalid")) return null;
        String path = descriptor.getString("path");
        String prefix = descriptor.getString("prefix");
        if (path != null) return folderPath(context, new File(path));
        if (prefix != null) {
            // These are root-relative collections. Require exactly one existing volume candidate.
            String match = null;
            for (File volume : roots(context)) {
                File candidate = prefix.startsWith(volume.getPath() + "/") ? new File(prefix)
                        : new File(volume, prefix.replaceFirst("^/+", ""));
                String found = folderPath(context, candidate);
                if (found == null) continue;
                if (match != null && !match.equals(found)) return null;
                match = found;
            }
            return match;
        }
        String name = descriptor.getString("name");
        if (name == null || name.isEmpty()) return null;
        String match = null;
        // This version uses parent names rather than BUCKET_ID; do not guess between same-name buckets.
        try (Cursor cursor = context.getContentResolver().query(FILES,
                new String[]{"_data", "relative_path", "volume_name"},
                "bucket_display_name = ? COLLATE NOCASE", new String[]{name}, null)) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                String data = cursor.getString(0);
                File parent = data == null ? relativeFolder(context, cursor.getString(1), cursor.getString(2))
                        : new File(data).getParentFile();
                String found = parent == null ? null : folderPath(context, parent);
                if (found == null) return null;
                if (match != null && !match.equals(found)) return null;
                match = found;
            }
        }
        return match;
    }

    private static File relativeFolder(Context context, String relative, String volumeName) {
        if (relative == null || volumeName == null) return null;
        StorageManager manager = context.getSystemService(StorageManager.class);
        for (StorageVolume volume : manager.getStorageVolumes()) {
            if (volumeName.equals(volume.getMediaStoreVolumeName()) && volume.getDirectory() != null)
                return new File(volume.getDirectory(), relative);
        }
        return null;
    }

    private static List<File> roots(Context context) throws IOException {
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory().getCanonicalFile());
        StorageManager manager = context.getSystemService(StorageManager.class);
        for (StorageVolume volume : manager.getStorageVolumes()) {
            File directory = volume.getDirectory();
            if (directory != null && !roots.contains(directory.getCanonicalFile())) roots.add(directory.getCanonicalFile());
        }
        return roots;
    }

    private static String folderPath(Context context, File folder) throws IOException {
        if (Files.isSymbolicLink(folder.toPath()) || !folder.isDirectory()) return null;
        File canonical = folder.getCanonicalFile();
        for (File volume : roots(context)) {
            if (canonical.equals(volume) || canonical.getPath().startsWith(volume.getPath() + "/"))
                return canonical.getPath();
        }
        return null;
    }

    private static boolean deletable(Context context, File folder) {
        try {
            String path = folderPath(context, folder);
            if (path == null || !path.equals(folder.getAbsolutePath())) return false;
            return !protectedPath(new File(path), roots(context));
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot validate folder " + folder, t);
            return false;
        }
    }

    private static boolean protectedPath(File folder, List<File> roots) {
        for (File volume : roots) {
            String base = volume.getPath();
            String path = folder.getPath();
            if (path.equals(base)) return true;
            if (!path.startsWith(base + "/")) continue;
            String relative = path.substring(base.length() + 1).toLowerCase(Locale.ROOT);
            if (relative.equals("android") || relative.startsWith("android/")) return true;
            return !relative.contains("/") && STANDARD.contains(relative);
        }
        return true;
    }

    private static CharSequence confirmation(Context context, int count) {
        String fallback = japanese(context)
                ? "この操作は取り消せません。フォルダ内のすべてのファイル（" + (count > 100 ? "100+" : count)
                    + " 件）も完全に削除されます。"
                : "This is permanent and cannot be undone. All " + (count > 100 ? "100+" : count)
                    + " files inside the folder will also be permanently deleted.";
        String text;
        if (count > 100) text = string(context,
                "confirm_delete_file_inside_folder_exceeding_threshold_subtitle_new", fallback, "100");
        else if (count > 0) text = quantity(context, "confirm_delete_file_inside_folder_subtitle_new", count, fallback);
        else text = string(context, "confirm_delete_subtitle_new", fallback);
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY);
    }

    private static String string(Context context, String name, String fallback, Object... args) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? fallback : context.getString(id, args);
    }

    private static String quantity(Context context, String name, int count, String fallback) {
        int id = context.getResources().getIdentifier(name, "plurals", context.getPackageName());
        return id == 0 ? fallback : context.getResources().getQuantityString(id, count, count);
    }

    private static boolean japanese(Context context) {
        return "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private static String title(Context context) {
        return japanese(context) ? "このフォルダを削除" : "Delete this folder";
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
