package app.filesplus.extension.foldertabs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
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
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.ModSettings;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
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
    private static final int MOVE_ITEM = 0x7e464d56;
    private static final Uri FILES = MediaStore.Files.getContentUri("external");
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String KNOWN_PREFIX = "tabs.knownFolders.";
    private static final Object KNOWN_LOCK = new Object();
    private static final Map<String, String> TAB_QUERIES = new LinkedHashMap<>();
    private static final Set<String> STANDARD = new HashSet<>(Arrays.asList(
            "dcim", "download", "movies", "pictures", "music", "documents", "alarms",
            "notifications", "podcasts", "ringtones", "recordings", "audiobooks"));

    private final WeakReference<View> root;
    private final WeakReference<Runnable> reload;
    // A null value is also cached: aggregate, ambiguous and inaccessible tabs stay unavailable.
    private final Map<String, String> folders = new HashMap<>();
    private final Set<String> resolving = new HashSet<>();
    private WeakReference<Menu> menu = new WeakReference<>(null);
    // Selection-mode (action mode) menu state for "move to current tab".
    private WeakReference<Menu> selectionMenu = new WeakReference<>(null);
    private WeakReference<Runnable> clearSelection = new WeakReference<>(null);
    private long[] selectedIds = new long[0];
    private List<String> selectedPaths = Collections.emptyList();
    private int selectionEpoch;
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

    /** Native MediaStore query constructors supply their complete category predicates, without I/O. */
    public static void rememberQuery(String filters, int type, String selection) {
        try {
            String category;
            switch (type) {
                case 1: category = "CATEGORY_IMAGE"; break;
                case 2: category = "CATEGORY_AUDIO"; break;
                case 3: category = "CATEGORY_VIDEO"; break;
                case -4: category = "CATEGORY_DOCUMENT"; break;
                case -5: category = "CATEGORY_DOCUMENT_ONLY"; break;
                case -6: category = "CATEGORY_OTHER"; break;
                case -2: category = "CATEGORY_RECENTS"; break;
                case -100: category = "CATEGORY_DOWNLOAD"; break;
                default: return;
            }
            synchronized (TAB_QUERIES) {
                TAB_QUERIES.put(category + ":" + filters, selection);
                while (TAB_QUERIES.size() > 1024) TAB_QUERIES.remove(TAB_QUERIES.keySet().iterator().next());
            }
        } catch (Throwable t) { hookError(t); }
    }

    /** Called at the end of the native background tab builder, even while the feature is off. */
    public static String[] emptyFolders(String category, Bundle[] tabs) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                throw new IllegalStateException("Known-folder resolution must run off the UI thread");
            }
            Context context = ModKit.context();
            SharedPreferences prefs = ModSettings.prefs();
            if (context == null || prefs == null || category == null) return new String[0];
            long started = System.nanoTime();
            synchronized (KNOWN_LOCK) {
                String key = KNOWN_PREFIX + category;
                Set<String> saved = prefs.getStringSet(key, Collections.emptySet());
                Set<String> known = new HashSet<>(saved);
                known.removeIf(path -> !new File(path).isDirectory());
                List<File> volumes = roots(context);
                Set<String> names = new HashSet<>();
                Set<String> present = new HashSet<>();
                Set<String> selections = new HashSet<>();
                for (Bundle tab : tabs) {
                    if (tab == null || tab.getBoolean("invalid")) continue;
                    String name = tab.getString("name");
                    if (name != null) {
                        names.add(name.toLowerCase(Locale.ROOT));
                        synchronized (TAB_QUERIES) {
                            String selection = TAB_QUERIES.get(category + ":" + tab.getString("filters"));
                            if (selection != null) selections.add("(" + selection + ")");
                        }
                    }
                    else {
                        String folder = resolve(context, tab);
                        if (folder != null) present.add(folder);
                    }
                }
                if (!selections.isEmpty()) {
                    // Use the host's exact predicates, including MIME, hidden, trash and Downloads rules.
                    // A single distinct-directory query resolves all native tabs in this category.
                    Map<String, File> byVolume = new HashMap<>();
                    StorageManager manager = context.getSystemService(StorageManager.class);
                    for (StorageVolume volume : manager.getStorageVolumes()) {
                        if (volume.getDirectory() != null && volume.getMediaStoreVolumeName() != null)
                            byVolume.put(volume.getMediaStoreVolumeName(), volume.getDirectory());
                    }
                    Uri distinct = FILES.buildUpon().appendQueryParameter("distinct", "true").build();
                    try (Cursor cursor = context.getContentResolver().query(distinct,
                            new String[]{"relative_path", "volume_name"},
                            String.join(" OR ", selections), null, null)) {
                        if (cursor != null) while (cursor.moveToNext()) {
                            String relative = cursor.getString(0);
                            File volume = byVolume.get(cursor.getString(1));
                            if (relative == null || volume == null) continue;
                            String folder = folderPath(new File(volume, relative), volumes);
                            if (folder != null) present.add(folder);
                        }
                    }
                }
                known.addAll(present);
                if (!known.equals(saved)) prefs.edit().putStringSet(key, known).apply();
                if (!ModSettings.getBool(ModSettings.TABS_SHOW_EMPTY_FOLDERS,
                        ModSettings.DEFAULT_TABS_SHOW_EMPTY_FOLDERS)) return new String[0];
                boolean hidden = ModSettings.getBool(ModSettings.TABS_SHOW_HIDDEN, ModSettings.DEFAULT_TABS_SHOW_HIDDEN);
                List<String> added = new ArrayList<>();
                for (String path : known) {
                    File folder = new File(path);
                    if (present.contains(path) || names.contains(folder.getName().toLowerCase(Locale.ROOT))) continue;
                    if ((!hidden && folder.getName().startsWith(".")) || protectedPath(folder, volumes, false)) continue;
                    // Canonical equality also excludes symlinks and paths that escape mounted storage.
                    if (path.equals(folderPath(folder, volumes))) added.add(path);
                }
                added.sort((left, right) -> {
                    int order = new File(left).getName().toLowerCase(Locale.getDefault())
                            .compareTo(new File(right).getName().toLowerCase(Locale.getDefault()));
                    return order == 0 ? left.compareTo(right) : order;
                });
                long millis = (System.nanoTime() - started) / 1_000_000;
                if (millis > 50) DiagLog.i(AREA, "Known folders for " + category + " took " + millis + " ms");
                return added.toArray(new String[0]);
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot extend folder tabs for " + category, t);
            return new String[0];
        }
    }

    private static void forgetDeletedFolders(Context context, File removed) {
        try {
            if (removed.isDirectory()) return;
            synchronized (KNOWN_LOCK) {
                SharedPreferences prefs = context.getSharedPreferences(ModSettings.PREFS, Context.MODE_PRIVATE);
                SharedPreferences.Editor edit = prefs.edit();
                for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    if (!entry.getKey().startsWith(KNOWN_PREFIX)) continue;
                    Set<String> old = prefs.getStringSet(entry.getKey(), Collections.emptySet());
                    Set<String> next = new HashSet<>(old);
                    next.removeIf(path -> path.equals(removed.getPath()) || path.startsWith(removed.getPath() + File.separator));
                    if (!next.equals(old)) edit.putStringSet(entry.getKey(), next);
                }
                edit.apply();
            }
        } catch (Throwable t) { DiagLog.e(AREA, "Cannot forget deleted known folder " + removed, t); }
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

    /**
     * Called by the bridge after every selection change while the selection action mode exists.
     * The lists hold MediaStore ids (Long) and app-private entries that are ignored here.
     */
    public static void onSelectionMenu(View root, Menu menu, List<?> first, List<?> second, Runnable clear) {
        try {
            if (root == null || menu == null) return;
            Object tag = root.getTag(TAG);
            if (!(tag instanceof FolderTabContext)) return;
            ((FolderTabContext) tag).selectionChanged(menu, first, second, clear);
        } catch (Throwable t) { hookError(t); }
    }

    private void selectionChanged(Menu target, List<?> first, List<?> second, Runnable clear) {
        View view = root.get();
        if (view == null) return;
        selectionMenu = new WeakReference<>(target);
        clearSelection = new WeakReference<>(clear);
        if (target.findItem(MOVE_ITEM) == null) {
            MenuItem item = target.add(Menu.NONE, MOVE_ITEM, Menu.NONE, "");
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            item.setVisible(false);
            item.setOnMenuItemClickListener(clicked -> {
                try { moveSelection(); } catch (Throwable t) { busy = false; hookError(t); updateMenu(); }
                return true;
            });
        }
        Set<Long> unique = new java.util.TreeSet<>();
        for (List<?> list : Arrays.asList(first, second)) {
            if (list == null) continue;
            for (Object entry : list) if (entry instanceof Long) unique.add((Long) entry);
        }
        long[] ids = new long[unique.size()];
        int index = 0;
        for (long id : unique) ids[index++] = id;
        if (!Arrays.equals(ids, selectedIds)) {
            selectedIds = ids;
            final int epoch = ++selectionEpoch;
            final Context app = view.getContext().getApplicationContext();
            if (ids.length == 0) {
                selectedPaths = Collections.emptyList();
            } else {
                WORKER.execute(() -> {
                    List<String> paths = Collections.emptyList();
                    try {
                        paths = TabMove.pathsOf(app, ids);
                    } catch (Throwable t) {
                        DiagLog.e(AREA, "Cannot resolve selected files", t);
                    }
                    final List<String> result = paths;
                    MAIN.post(() -> {
                        try {
                            if (epoch != selectionEpoch) return;
                            selectedPaths = result;
                            updateMenu();
                        } catch (Throwable t) { hookError(t); }
                    });
                });
            }
        }
        updateMenu();
    }

    /** Number of selected files that are not yet directly inside the current tab's folder. */
    private int movable() {
        if (currentFolder == null) return 0;
        int count = 0;
        for (String path : selectedPaths) {
            File parent = new File(path).getParentFile();
            if (parent == null || !parent.getPath().equals(currentFolder)) count++;
        }
        return count;
    }

    private void updateMoveItem() {
        Menu target = selectionMenu.get();
        View view = root.get();
        if (target == null || view == null) return;
        MenuItem item = target.findItem(MOVE_ITEM);
        if (item == null) return;
        boolean available = !busy && movable() > 0;
        if (currentFolder != null) {
            String name = new File(currentFolder).getName();
            item.setTitle(japanese(view.getContext()) ? "\"" + name + "\" へ移動" : "Move to \"" + name + "\"");
        }
        item.setVisible(available);
        item.setEnabled(available);
    }

    private void moveSelection() {
        View view = root.get();
        if (view == null || busy || currentFolder == null) return;
        final Context app = view.getContext().getApplicationContext();
        final File destination = new File(currentFolder);
        final List<String> sources = new ArrayList<>(selectedPaths);
        final Runnable clear = clearSelection.get();
        busy = true;
        updateMenu();
        WORKER.execute(() -> {
            TabMove.Result result = new TabMove.Result();
            try {
                result = TabMove.move(app, sources, destination);
            } catch (Throwable t) {
                result.failed = sources.size();
                DiagLog.e(AREA, "Move to " + destination + " failed", t);
            }
            final TabMove.Result done = result;
            MAIN.post(() -> {
                try {
                    busy = false;
                    selectedPaths = Collections.emptyList();
                    selectedIds = new long[0];
                    selectionEpoch++;
                    if (clear != null) clear.run();
                    toast(app, moveReport(app, destination.getName(), done));
                    refresh();
                } catch (Throwable t) { hookError(t); }
            });
        });
    }

    private static String moveReport(Context context, String tab, TabMove.Result result) {
        boolean ja = japanese(context);
        StringBuilder text = new StringBuilder(ja
                ? result.moved + " 件を \"" + tab + "\" に移動しました"
                : "Moved " + result.moved + " item" + (result.moved == 1 ? "" : "s") + " to \"" + tab + "\"");
        if (result.failed > 0) text.append(ja ? "（失敗 " + result.failed + " 件）" : " (" + result.failed + " failed)");
        if (result.skipped > 0) text.append(ja ? "（移動済み " + result.skipped + " 件を除く）"
                : " (" + result.skipped + " already there)");
        return text.toString();
    }

    private void updateMenu() {
        updateMoveItem();
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
        // The dialog (and so the delete button) only exists after the exact count has finished.
        toast(activity, japanese(activity) ? "ファイルを数えています…" : "Counting files...");
        WORKER.execute(() -> {
            try {
                final long[] census = countFiles(folder.toPath());
                MAIN.post(() -> {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            busy = false;
                            return;
                        }
                        new AlertDialog.Builder(activity)
                                .setTitle(quantity(activity, "confirm_delete_folder_title", 1,
                                        japanese(activity) ? "フォルダを完全に削除しますか？" : "Delete folder permanently?"))
                                .setMessage(folder.getAbsolutePath() + "\n\n" + confirmation(activity, census[0], census[1]))
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
            final int[] refused = {0}; // 0 = allowed, 1 = protected, 2 = changed or removed.
            try {
                // Recheck protection at delete time, not only when the menu item was shown; never follow
                // directory symlinks, including the selected folder. Nothing is touched when it fails.
                refused[0] = deleteCheck(context, folder);
                if (refused[0] != 0) {
                    throw new IOException((refused[0] == 1 ? "Protected folder" : "Folder changed or removed")
                            + ", nothing deleted: " + folder);
                }
                deleteTree(context, folder, deleted, result);
            } catch (Throwable t) {
                result[1]++;
                DiagLog.e(AREA, "Folder deletion failed: " + folder, t);
            } finally {
                syncDeleted(context, deleted, result);
                forgetDeletedFolders(context, folder);
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
                            : refused[0] == 1 ? (japanese(context) ? "保護されたフォルダのため削除しませんでした" : "Protected folder, not deleted")
                            : refused[0] == 2 ? (japanese(context) ? "フォルダが変更または削除されたため、削除しませんでした" : "The folder changed or was removed, not deleted")
                            : (japanese(context) ? "フォルダの削除を完了できませんでした" : "Could not completely delete the folder"));
                    if (refused[0] == 0 || !folder.exists()) removedKey = key;
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

    /**
     * Exact number of files (links count as files, are never followed) and their total size.
     * Any unreadable entry aborts the walk: an incomplete count must never reach the confirmation.
     */
    private static long[] countFiles(Path folder) throws IOException {
        final long[] total = {0, 0}; // Files, bytes.
        Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                total[0]++;
                if (attrs.isRegularFile()) total[1] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return total;
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
                File candidate = prefix.startsWith(volume.getPath() + File.separator) ? new File(prefix)
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
        return folderPath(folder, roots(context));
    }

    private static String folderPath(File folder, List<File> volumes) throws IOException {
        if (Files.isSymbolicLink(folder.toPath()) || !folder.isDirectory()) return null;
        File canonical = folder.getCanonicalFile();
        for (File volume : volumes) {
            if (canonical.equals(volume) || canonical.getPath().startsWith(volume.getPath() + File.separator))
                return canonical.getPath();
        }
        return null;
    }

    private static boolean deletable(Context context, File folder) {
        return deleteCheck(context, folder) == 0;
    }

    /** 0 = may be deleted, 1 = protected folder, 2 = missing, linked, unreadable or not the same folder. */
    private static int deleteCheck(Context context, File folder) {
        try {
            String path = folderPath(context, folder);
            if (path == null || !path.equals(folder.getAbsolutePath())) return 2;
            return protectedPath(new File(path), roots(context), true) ? 1 : 0;
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot validate folder " + folder, t);
            return 2;
        }
    }

    /**
     * Protection applies to the folder itself; files inside a protected folder stay movable.
     * Android/ and .FilesPlus/ are protected including everything below them. The second-level
     * camera/screen-capture rules are for deletion only: with {@code deletion} false (folder tabs)
     * only the volume root, Android/, .FilesPlus and the top-level standard folders match.
     */
    private static boolean protectedPath(File folder, List<File> roots, boolean deletion) {
        for (File volume : roots) {
            String base = volume.getPath();
            String path = folder.getPath();
            if (path.equals(base)) return true;
            if (!path.startsWith(base + File.separator)) continue;
            String relative = path.substring(base.length() + 1).replace(File.separatorChar, '/').toLowerCase(Locale.ROOT);
            if (relative.equals("android") || relative.startsWith("android/")) return true;
            if (relative.equals(".filesplus") || relative.startsWith(".filesplus/")) return true;
            String[] parts = relative.split("/");
            if (parts.length == 1) return STANDARD.contains(relative);
            return deletion && parts.length == 2 && protectedSecondLevel(parts[0], parts[1]);
        }
        return true;
    }

    /** Camera output and screen captures directly below a standard folder (names compared loosely). */
    private static boolean protectedSecondLevel(String parent, String name) {
        // "Screen Recorder", "screen_recorder" and "ScreenRecorder" are the same folder on different ColorOS builds.
        String loose = name.replace(" ", "").replace("_", "").replace("-", "");
        switch (parent) {
            case "dcim":
                return loose.equals("camera") || loose.equals("screenshots") || loose.equals("screenshot")
                        || loose.startsWith("screenrecord");
            case "pictures":
                return loose.equals("screenshots") || loose.equals("screenshot");
            case "movies":
                return loose.startsWith("screenrecord");
            default:
                return false;
        }
    }

    /** The count is exact (no "100+" cap); the total size is appended as its own line. */
    private static CharSequence confirmation(Context context, long count, long bytes) {
        String fallback = japanese(context)
                ? "この操作は取り消せません。フォルダ内のすべてのファイル（" + count + " 件）も完全に削除されます。"
                : "This is permanent and cannot be undone. All " + count
                    + " files inside the folder will also be permanently deleted.";
        String text;
        if (count > 0) text = quantity(context, "confirm_delete_file_inside_folder_subtitle_new",
                (int) Math.min(count, Integer.MAX_VALUE), fallback);
        else text = string(context, "confirm_delete_subtitle_new", fallback);
        if (count > 0) {
            String size = (japanese(context) ? "合計サイズ: " : "Total size: ")
                    + android.text.format.Formatter.formatFileSize(context, bytes);
            text += "<br><br>" + TextUtils.htmlEncode(size);
        }
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
