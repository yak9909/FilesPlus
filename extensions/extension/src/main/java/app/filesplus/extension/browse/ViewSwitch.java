package app.filesplus.extension.browse;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModSettings;
import app.filesplus.extension.foldertabs.FolderTabContext;
import app.filesplus.extension.settings.SettingsHooks;
import app.filesplus.extension.settings.SettingsPages;
import app.filesplus.extension.ui.OriginalScreens;
import app.filesplus.extension.ui.ProtoNode;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Switches the native browser activity from the normal top overflow. */
public final class ViewSwitch {
    private static final int TAG = 0x7e535701;
    private static final int ITEM = 0x7e535702;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private static final class Browser {
        final File folder;
        final Category category;
        WeakReference<Menu> menu = new WeakReference<>(null);

        Browser(File folder, Category category) {
            this.folder = folder;
            this.category = category;
        }
    }

    private ViewSwitch() {}

    /** Replaced by the named patch. */
    public static boolean installed() { return false; }

    public static String hookStatus(boolean japanese) {
        if (installed()) return japanese ? "適用済み（両ブラウザの上部メニュー）" : "Installed (both browser overflows)";
        return japanese ? "未適用" : "Not installed";
    }

    static void storageView(View root, byte[] snapshot) {
        try {
            if (!installed() || root == null || snapshot == null) return;
            if (!OriginalScreens.isStorageBrowser(activity(root.getContext()))) return;
            File folder = storageFolder(ProtoNode.decode(snapshot).getString(2));
            if (folder == null) return;
            root.setTag(TAG, new Browser(folder, null));
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { remember(folder); }
                @Override public void onViewDetachedFromWindow(View view) {}
            });
            if (root.isAttachedToWindow()) remember(folder);
        } catch (Throwable error) { log(error); }
    }

    /** The native folder field can carry either an absolute path or a local file URI. */
    static File storageFolder(String value) throws IOException {
        if (value == null) return null;
        if (!value.startsWith("file:")) return value.startsWith("/") ? new File(value) : null;
        String path = value.substring("file:".length());
        if (path.startsWith("//")) path = path.substring(2);
        if (!path.startsWith("/")) return null;
        // Keep raw spaces and plus signs while still decoding the app's percent-encoded file paths.
        String encoded = path.replace("+", "%2B").replaceAll("%(?![0-9a-fA-F]{2})", "%25");
        return new File(URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()));
    }

    private static void remember(File folder) {
        try { ModSettings.rememberSwitchFolder(folder.getAbsolutePath()); }
        catch (Throwable error) { log(error); }
    }

    static void mediaView(View root, String nativeCategory) {
        try {
            if (!installed() || root == null || !OriginalScreens.isMediaBrowser(activity(root.getContext()))) return;
            Category category = Category.ofAppCategory(nativeCategory);
            if (category != null) root.setTag(TAG, new Browser(null, category));
        } catch (Throwable error) { log(error); }
    }

    static void menu(View root, Menu menu) {
        try {
            if (!installed() || root == null || menu == null) return;
            Browser browser = browser(root);
            if (browser == null) return;
            menu.removeItem(ITEM);
            // Storage's options creator can also inflate the single-selection menu.
            if (!hasVisibleItem(root.getContext(), menu, "view_mode_switch")
                    || !hasVisibleItem(root.getContext(), menu, "sort")) {
                if (browser.menu.get() == menu) browser.menu.clear();
                return;
            }
            browser.menu = new WeakReference<>(menu);
            updateMenu(root, browser, menu);
        } catch (Throwable error) { log(error); }
    }

    public static void tabChanged(View root) {
        try {
            if (!installed()) return;
            Browser browser = browser(root);
            if (browser == null || browser.category == null) return;
            Menu menu = browser.menu.get();
            if (menu != null) updateMenu(root, browser, menu);
        } catch (Throwable error) { log(error); }
    }

    private static void updateMenu(View root, Browser browser, Menu menu) {
        FolderTabContext tab = browser.category == null ? null : FolderTabContext.from(root);
        Activity host = activity(root.getContext());
        boolean available = allowedHost(host, browser)
                && (browser.folder != null || (tab != null && (tab.isAllTab() || tab.getCurrentFolder() != null)));
        MenuItem item = menu.findItem(ITEM);
        if (!available) {
            if (item != null) menu.removeItem(ITEM);
            return;
        }
        if (item != null) return;
        boolean japanese = SettingsHooks.isJapanese(root.getContext());
        item = menu.add(Menu.NONE, ITEM, Menu.NONE, browser.folder != null
                ? (japanese ? "メディアで表示" : "Show as media")
                : (japanese ? "フォルダで表示" : "Show in folders"));
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        item.setOnMenuItemClickListener(clicked -> {
            try {
                if (!allowedHost(host, browser) || !alive(host) || !root.isAttachedToWindow()) return true;
                if (browser.folder != null) {
                    toMedia(host, browser.folder);
                } else {
                    String folder = tab.getCurrentFolder();
                    if (folder != null || tab.isAllTab()) toStorage(host, browser.category, folder);
                    else failed(host, new IOException("Folder tab is no longer resolved"));
                }
            } catch (Throwable error) { failed(host, error); }
            return true;
        });
    }

    public static void toMedia(Activity host, File folder) {
        try {
            if (!OriginalScreens.isStorageBrowser(host) || !alive(host)) return;
            boolean ask = "ask".equals(ModSettings.switchToMediaCategory());
            if (ask) {
                background(host, () -> LocalSource.categoryCounts(folder),
                        counts -> chooseMedia(host, folder, counts));
            } else {
                background(host, () -> LocalSource.dominantCategory(folder),
                        category -> openMedia(host, folder, category));
            }
        } catch (Throwable error) { failed(host, error); }
    }

    private static void chooseMedia(Activity host, File folder, Map<Category, Integer> counts) {
        if (counts.isEmpty()) {
            openMedia(host, folder, null);
            return;
        }
        boolean japanese = SettingsHooks.isJapanese(host);
        List<Category> categories = new ArrayList<>(counts.keySet());
        CharSequence[] labels = new CharSequence[categories.size()];
        for (int index = 0; index < labels.length; index++) {
            Category category = categories.get(index);
            int count = counts.get(category);
            labels[index] = japanese ? label(category, true) + "（" + count + "）"
                    : label(category, false) + " (" + count + ")";
        }
        SettingsPages.nativeChoices(host, japanese ? "メディアで表示" : "Show as media", labels, -1,
                (dialog, which) -> {
                    try {
                        dialog.dismiss();
                        if (!alive(host) || which < 0 || which >= categories.size()) return;
                        Category category = categories.get(which);
                        background(host, () -> LocalSource.categoryCounts(folder).containsKey(category)
                                ? category : null, result -> openMedia(host, folder, result));
                    } catch (Throwable error) { failed(host, error); }
                }).show();
    }

    private static String label(Category category, boolean japanese) {
        switch (category) {
            case IMAGES: return japanese ? "画像" : "Images";
            case VIDEOS: return japanese ? "動画" : "Videos";
            case AUDIO: return japanese ? "音声" : "Audio";
            case DOCUMENTS: return japanese ? "ドキュメント" : "Documents";
            default: throw new IllegalArgumentException("Unsupported media choice");
        }
    }

    private static void openMedia(Activity host, File folder, Category category) {
        Context app = host.getApplicationContext();
        background(host, () -> mediaTabName(folder, LocalFolders.roots(app)),
                tab -> OriginalScreens.switchToMedia(host, category == null ? Category.DOWNLOADS : category, tab));
    }

    static String mediaTabName(File folder, List<File> volumeRoots) throws IOException {
        return volumeRoots.contains(folder.getCanonicalFile()) ? null : folder.getName();
    }

    public static void toStorage(Activity host, Category category, String folderOrNull) {
        try {
            if (!OriginalScreens.isMediaBrowser(host) || !alive(host) || category == null) return;
            String setting = ModSettings.switchAllTabTarget();
            Context app = host.getApplicationContext();
            background(host, () -> {
                File target;
                if (folderOrNull != null) {
                    target = new File(folderOrNull);
                    if (!target.isAbsolute() || !target.isDirectory()) throw new IOException("Folder tab is unavailable");
                } else {
                    File root = Environment.getExternalStorageDirectory();
                    File latest = null;
                    if ("latest".equals(setting)) {
                        try { latest = new LocalSource(app).latestFolder(category); }
                        catch (Throwable error) { log(error); }
                    }
                    String last = "last".equals(setting) ? ModSettings.switchLastFolder() : null;
                    target = resolveAllTab(category, setting, root, last == null ? null : new File(last), latest);
                }
                return OriginalScreens.stackFor(app, target);
            }, stack -> OriginalScreens.switchToStorage(host, stack));
        } catch (Throwable error) { failed(host, error); }
    }

    /** Pure filesystem policy with the last directory and the latest MediaStore result supplied by the caller. */
    static File resolveAllTab(Category category, String setting, File root, File last, File latest) {
        if ("root".equals(setting)) return root;
        if ("last".equals(setting) && directory(last)) return last;
        if ("latest".equals(setting) && directory(latest)) return latest;
        File standard = new File(root, category.defaultDirectory);
        return standard.isDirectory() ? standard : root;
    }

    private static boolean directory(File folder) {
        return folder != null && folder.isAbsolute() && folder.isDirectory();
    }

    private interface Read<T> { T run() throws Exception; }

    private static <T> void background(Activity host, Read<T> read, Consumer<T> use) {
        WORKER.execute(() -> {
            try {
                T result = read.run();
                host.runOnUiThread(() -> {
                    try {
                        if (alive(host)) use.accept(result);
                    } catch (Throwable error) { failed(host, error); }
                });
            } catch (Throwable error) {
                log(error);
                try { host.runOnUiThread(() -> failed(host, null)); }
                catch (Throwable dispatchError) { log(dispatchError); }
            }
        });
    }

    private static Browser browser(View root) {
        Object tag = root == null ? null : root.getTag(TAG);
        return tag instanceof Browser ? (Browser) tag : null;
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context next = ((ContextWrapper) context).getBaseContext();
            if (context == next) return null;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private static boolean hasVisibleItem(Context context, Menu menu, String name) {
        int id = context.getResources().getIdentifier(name, "id", context.getPackageName());
        MenuItem item = id == 0 ? null : menu.findItem(id);
        return item != null && item.isVisible();
    }

    private static boolean allowedHost(Activity host, Browser browser) {
        return browser.folder != null ? OriginalScreens.isStorageBrowser(host) : OriginalScreens.isMediaBrowser(host);
    }

    private static boolean alive(Activity host) {
        return host != null && !host.isFinishing() && !host.isDestroyed();
    }

    private static void log(Throwable error) {
        DiagLog.e("switch", "Browser switch failed", error);
    }

    private static void failed(Activity host, Throwable error) {
        if (error != null) log(error);
        try {
            if (!alive(host)) return;
            Toast.makeText(host, SettingsHooks.isJapanese(host) ? "画面を開けませんでした" : "Could not open the screen",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable toastError) { log(toastError); }
    }
}
