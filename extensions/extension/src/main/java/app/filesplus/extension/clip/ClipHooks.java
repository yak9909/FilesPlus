package app.filesplus.extension.clip;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.browse.LocalFolders;
import app.filesplus.extension.foldertabs.FolderTabContext;
import app.filesplus.extension.foldertabs.TabMove;
import app.filesplus.extension.preview.SelectionStore;
import app.filesplus.extension.ui.ProtoNode;
import app.filesplus.extension.ui.ScreenArgs;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Clipboard patch entry points. App protobuf objects are serialized immediately. */
public final class ClipHooks {
    private static final int CUT = 0x7e434c31;
    private static final int ADD = 0x7e434c32;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Map<Menu, Object> REQUESTS = new WeakHashMap<>();
    private ClipHooks() {}

    public static void hookError(Throwable error) { DiagLog.e("clip", "Clipboard hook failed", error); }

    /** Replaced at patch time; diagnostics report installation, even before a screen has been opened. */
    public static String installedHooks() { return "Clipboard: not installed"; }
    public static boolean installed() { return false; }

    /** Patched to call the same MaterialButton icon setter as the native folder picker. */
    public static boolean setPasteIcon(View button, Drawable icon) { return false; }

    public static void attachStoragePaste(View root, byte[] folder, boolean expressive) {
        try {
            if (!app.filesplus.extension.smb.SmbRoots.isFolder(folder)) PasteUi.attachStorage(root, folder, expressive);
        }
        catch (Throwable error) { hookError(error); }
    }

    public static void attachMediaPaste(View root, String category) {
        try { PasteUi.attachMedia(root, category); }
        catch (Throwable error) { hookError(error); }
    }

    public static void pasteMenu(View root, Menu menu) {
        try { PasteUi.menu(root, menu); }
        catch (Throwable error) { hookError(error); }
    }

    public static void pasteTabChanged(View root) {
        try { PasteUi.tabChanged(root); }
        catch (Throwable error) { hookError(error); }
    }

    public static void pasteSettingChanged() {
        try { Clipboard.notifyListeners(); }
        catch (Throwable error) { hookError(error); }
    }

    public static void pasteBarFlag(boolean expressive) {
        try { PasteUi.nativeFlag(expressive); }
        catch (Throwable error) { hookError(error); }
    }

    public static void observedPasteBarFlag(boolean expressive) {
        try { PasteUi.observedFlag(expressive); }
        catch (Throwable error) { hookError(error); }
    }

    static void work(Runnable task) { WORKER.execute(task); }

    public static String previewPagePath(Object entry) {
        try {
            if (!installed()) return null;
            byte[] raw = bytes(entry);
            return raw == null ? null : ClipSources.file(ProtoNode.decode(raw)).path;
        } catch (Throwable t) { hookError(t); return null; }
    }

    public static byte[] bytes(Object message) {
        try { return ScreenArgs.serialize(message); }
        catch (Throwable error) { hookError(error); return null; }
    }

    private static boolean japanese(Context context) {
        return "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private static String addLabel(Context context) {
        return japanese(context) ? "クリップボードに追加" : "Add to clipboard";
    }

    private interface Sources { List<ClipSources.Source> read() throws Exception; }

    private static void remove(Menu menu) {
        menu.removeItem(CUT);
        menu.removeItem(ADD);
    }

    private static void menu(Context context, Menu menu, Sources sources, boolean available) {
        remove(menu);
        if (!available) return;
        int restoreId = context.getResources().getIdentifier("restore_action", "id", context.getPackageName());
        MenuItem restore = restoreId == 0 ? null : menu.findItem(restoreId);
        if (restore != null && restore.isVisible()) return;
        // Native selection refresh disables every item while busy. Do not reintroduce enabled actions.
        boolean enabled = false;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            enabled |= item.isVisible() && item.isEnabled();
        }
        if (!enabled) return;
        addMenuItem(context, menu, CUT, context.getString(android.R.string.cut), sources, false);
        if (!Clipboard.get(context).isEmpty()) addMenuItem(context, menu, ADD, addLabel(context), sources, true);
    }

    private static void addMenuItem(Context context, Menu menu, int id, String label, Sources sources, boolean add) {
        menu.add(Menu.NONE, id, Menu.NONE, label).setOnMenuItemClickListener(item -> {
            try { enqueue(context, sources, add); return true; }
            catch (Throwable error) { hookError(error); return false; }
        }).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    }

    /** Reuses the hidden media selection capture; queries are never performed on the UI thread. */
    public static void media(View root, Menu menu) {
        try {
            if (menu == null) return;
            remove(menu);
            FolderTabContext tabs = FolderTabContext.from(root);
            if (tabs == null) return;
            long[] ids = tabs.selectedIds();
            Object request = new Object();
            REQUESTS.put(menu, request);
            if (ids.length == 0) return;
            Context context = root.getContext().getApplicationContext();
            java.lang.ref.WeakReference<Menu> weakMenu = new java.lang.ref.WeakReference<>(menu);
            java.lang.ref.WeakReference<View> weakRoot = new java.lang.ref.WeakReference<>(root);
            WORKER.execute(() -> {
                try {
                    List<ClipSources.Source> resolved = mediaSources(context, ids);
                    boolean local = false;
                    for (ClipSources.Source source : resolved) local |= source.eligible();
                    final boolean available = local;
                    MAIN.post(() -> {
                        try {
                            Menu target = weakMenu.get();
                            View view = weakRoot.get();
                            if (target == null || view == null || REQUESTS.get(target) != request) return;
                            FolderTabContext latest = FolderTabContext.from(view);
                            if (latest == null || !java.util.Arrays.equals(ids, latest.selectedIds())) return;
                            menu(context, target, () -> resolved, available);
                        } catch (Throwable error) { hookError(error); }
                    });
                } catch (Throwable error) { hookError(error); }
            });
        } catch (Throwable error) { hookError(error); }
    }

    private static List<ClipSources.Source> mediaSources(Context context, long[] ids) {
        Map<String, Long> known = new LinkedHashMap<>();
        List<String> paths = TabMove.pathsOf(context, ids, known);
        List<ClipSources.Source> out = new ArrayList<>();
        for (String path : paths) out.add(new ClipSources.Source(path, known.getOrDefault(path, 0L)));
        for (int i = paths.size(); i < ids.length; i++) out.add(new ClipSources.Source(null, 0));
        return out;
    }

    public static void storage(Menu menu, byte[] selection) {
        try {
            if (menu == null) return;
            remove(menu);
            if (selection == null) return;
            Context context = ModKit.context();
            ProtoNode node = ProtoNode.decode(selection);
            menu(context, menu, () -> ClipSources.expand(node), ClipSources.hasLocalSelection(node));
        } catch (Throwable error) { hookError(error); }
    }

    public static void popup(Menu menu, byte[] entry, boolean folder) {
        try {
            if (menu == null) return;
            remove(menu);
            if (entry == null) return;
            ClipSources.Source source = folder ? ClipSources.folder(ProtoNode.decode(entry))
                    : ClipSources.file(ProtoNode.decode(entry));
            menu(ModKit.context(), menu, () -> Collections.singletonList(source), source.eligible());
        } catch (Throwable error) { hookError(error); }
    }

    /** Returns an augmented copy, leaving the app's list intact if any constructor/decoder fails. */
    public static List<?> list(List<?> original, byte[] entry, boolean folder, ClipMenuFactory factory) {
        try {
            if (entry == null || original == null) return original;
            ClipSources.Source source = folder ? ClipSources.folder(ProtoNode.decode(entry))
                    : ClipSources.file(ProtoNode.decode(entry));
            if (!source.eligible()) return original;
            Context context = ModKit.context();
            List<Object> out = new ArrayList<>(original);
            Sources sources = () -> Collections.singletonList(source);
            out.add(factory.clipMenuRow(context.getString(android.R.string.cut), action(context, sources, false)));
            if (!Clipboard.get(context).isEmpty())
                out.add(factory.clipMenuRow(addLabel(context), action(context, sources, true)));
            return out;
        } catch (Throwable error) { hookError(error); return original; }
    }

    private static Runnable action(Context context, Sources sources, boolean add) {
        return () -> { try { enqueue(context, sources, add); } catch (Throwable error) { hookError(error); } };
    }

    public static void preview(View root, Menu menu, long id, boolean hasId, Object current) {
        try {
            if (!installed()) return;
            Context context = root == null ? ModKit.context() : root.getContext();
            List<ClipSources.Source> sources = new ArrayList<>();
            List<Object> entries = SelectionStore.entries();
            long[] ids = SelectionStore.ids();
            if (entries.isEmpty()) {
                entries = Collections.singletonList(current);
                ids = new long[]{hasId ? id : 0};
            }
            boolean available = false;
            for (int i = 0; i < entries.size(); i++) {
                byte[] data = bytes(entries.get(i));
                ClipSources.Source source = data == null ? new ClipSources.Source(null, 0)
                        : ClipSources.file(ProtoNode.decode(data));
                if (source.eligible() && source.id <= 0 && ids[i] > 0) source = new ClipSources.Source(source.path, ids[i]);
                available |= source.eligible();
                sources.add(source);
            }
            menu(context, menu, () -> sources, available);
        } catch (Throwable error) { hookError(error); }
    }

    private static void enqueue(Context context, Sources sources, boolean add) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            int skipped = 0;
            List<ClipItem> measured = new ArrayList<>();
            try {
                List<ClipSources.Source> inputs = sources.read();
                Map<String, ClipItem> unique = new LinkedHashMap<>();
                List<File> roots = LocalFolders.roots(app);
                for (ClipSources.Source source : inputs) {
                    try {
                        if (!source.eligible()) { skipped++; continue; }
                        File file = new File(source.path);
                        if (ClipSources.protectedPath(file, roots) || !file.exists()
                                || (file.isDirectory() && file.listFiles() == null)) { skipped++; continue; }
                        ClipItem item = ClipItem.measure(file, source.id);
                        unique.put(item.path().toLowerCase(java.util.Locale.ROOT), item);
                    } catch (Throwable error) { skipped++; hookError(error); }
                }
                measured.addAll(unique.values());
                // An all-skipped request must not destroy the previous clipboard.
                if (!measured.isEmpty()) {
                    Clipboard.get(app); // expire old contents before Add
                    if (add) Clipboard.add(app, measured);
                    else Clipboard.cut(app, measured);
                }
            } catch (Throwable error) {
                hookError(error);
                skipped += Math.max(1, measured.size());
                measured.clear();
            }
            final int count = measured.size();
            final int ignored = skipped;
            MAIN.post(() -> {
                try {
                    boolean ja = japanese(app);
                    String noun = count == 1 ? " item" : " items";
                    String text = add ? (ja ? count + " 件をクリップボードに追加しました" : "Added " + count + noun + " to clipboard")
                            : (ja ? count + " 件を切り取りました" : "Cut " + count + noun);
                    if (ignored > 0) text += ja ? "（" + ignored + " 件をスキップ）" : " (" + ignored + " skipped)";
                    Toast.makeText(app, text, Toast.LENGTH_SHORT).show();
                } catch (Throwable error) { hookError(error); }
            });
        });
    }
}
