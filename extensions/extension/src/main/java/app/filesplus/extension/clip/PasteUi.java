package app.filesplus.extension.clip;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import app.filesplus.extension.ModSettings;
import app.filesplus.extension.browse.Category;
import app.filesplus.extension.foldertabs.FolderTabContext;
import app.filesplus.extension.move.MoveQueue;

import java.io.File;
import java.lang.ref.WeakReference;

/** One controller per browser view. A process-wide request prevents overlapping pastes and picker launches. */
final class PasteUi implements View.OnAttachStateChangeListener {
    private static final int TAG = 0x7e434c33;
    private static final int PASTE = 0x7e434c34;
    private static final int CLEAR = 0x7e434c35;
    private static final String PICKER = "com.google.android.apps.nbu.files.advancedbrowsing.picker.AdvancedBrowsingPickerActivity";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Boolean nativeFlag;
    private static volatile boolean observedFlag;
    private static long nextToken = System.nanoTime();
    private static Request active;

    private final View root;
    private final boolean media;
    private final Category category;
    private final PasteTarget storageTarget;
    private final boolean storageExpressive;
    private final Runnable listener = this::invalidate;
    private WeakReference<Menu> menu = new WeakReference<>(null);
    private PasteBar bar;
    private boolean barExpressive;
    private boolean barFailed;
    private Clipboard.State state;
    private PasteTarget target;
    private PasteRules.Result decision;
    private int revision;
    private boolean posted;

    private PasteUi(View root, boolean media, Category category, PasteTarget storageTarget, boolean expressive) {
        this.root = root;
        this.media = media;
        this.category = category;
        this.storageTarget = storageTarget;
        this.storageExpressive = expressive;
    }

    static void attachStorage(View root, byte[] folder, boolean expressive) {
        PasteTarget target;
        try {
            target = PasteTarget.ofStorageFolder(folder);
        } catch (Throwable t) {
            ClipHooks.hookError(t);
            target = PasteTarget.ofFolder(null);
        }
        attach(root, false, null, target, expressive);
    }

    static void attachMedia(View root, String category) {
        attach(root, true, Category.ofAppCategory(category), null, false);
    }

    private static void attach(View root, boolean media, Category category, PasteTarget target, boolean expressive) {
        if (root == null || root.getTag(TAG) != null) return;
        Activity host = activity(root.getContext());
        if (host == null || PICKER.equals(host.getClass().getName())) return;
        PasteUi ui = new PasteUi(root, media, category, target, expressive);
        root.setTag(TAG, ui);
        root.addOnAttachStateChangeListener(ui);
        if (root.isAttachedToWindow()) ui.onViewAttachedToWindow(root);
    }

    private static PasteUi from(View root) {
        Object tag = root == null ? null : root.getTag(TAG);
        return tag instanceof PasteUi ? (PasteUi) tag : null;
    }

    static void nativeFlag(boolean expressive) {
        Boolean previous = nativeFlag;
        nativeFlag = expressive;
        if ((previous != null ? previous : observedFlag) != expressive) Clipboard.notifyListeners();
    }

    static void observedFlag(boolean expressive) {
        if (nativeFlag == null && observedFlag != expressive) {
            observedFlag = expressive;
            Clipboard.notifyListeners();
        }
    }

    static void tabChanged(View root) {
        PasteUi ui = from(root);
        if (ui != null && ui.media) ui.invalidate();
    }

    static void menu(View root, Menu menu) {
        PasteUi ui = from(root);
        if (ui == null || menu == null) return;
        Context context = root.getContext();
        // The creators also inflate selection menus. Only the normal overflow has both anchors.
        if (menu.findItem(PasteBar.id(context, "id", "view_mode_switch")) == null
                || menu.findItem(PasteBar.id(context, "id", "sort")) == null) {
            if (ui.menu.get() == menu) ui.menu.clear();
            removeItems(menu);
            return;
        }
        ui.menu = new WeakReference<>(menu);
        ui.renderMenu();
    }

    @Override public void onViewAttachedToWindow(View view) {
        try {
            barFailed = false;
            Clipboard.addListener(listener);
            invalidate();
        } catch (Throwable t) {
            Clipboard.removeListener(listener);
            ClipHooks.hookError(t);
        }
    }

    @Override public void onViewDetachedFromWindow(View view) {
        Clipboard.removeListener(listener);
        revision++;
        decision = null;
        menu.clear();
        try { hideBar(); } catch (Throwable t) { ClipHooks.hookError(t); }
    }

    /** Merge the native tab callback's intermediate and final states into one main-thread update. */
    private void invalidate() {
        revision++;
        decision = null;
        if (!root.isAttachedToWindow() || posted) return;
        posted = true;
        MAIN.post(() -> {
            posted = false;
            if (!root.isAttachedToWindow()) return;
            try { refresh(); } catch (Throwable t) {
                ClipHooks.hookError(t);
                hideBar();
            }
        });
    }

    private void refresh() {
        state = Clipboard.get(root.getContext());
        render();
        if (state.isEmpty() || active != null) return;

        FolderTabContext tabs = media ? FolderTabContext.from(root) : null;
        String folder = tabs == null ? null : tabs.getCurrentFolder();
        String allSetting = tabs != null && tabs.isAllTab() ? ModSettings.pasteAllTab() : null;
        Clipboard.State snapshot = state;
        int requested = revision;
        Category currentCategory = category;
        PasteTarget storage = storageTarget;
        WeakReference<PasteUi> receiver = new WeakReference<>(this);
        ClipHooks.work(() -> {
            try {
                PasteTarget destination = storage;
                if (destination == null) {
                    destination = allSetting != null ? PasteTarget.ofAllTab(currentCategory, allSetting)
                            : PasteTarget.ofFolder(folder == null ? null : new File(folder));
                }
                PasteRules.Result result = PasteRules.check(snapshot, destination);
                PasteTarget checked = destination;
                MAIN.post(() -> {
                    PasteUi ui = receiver.get();
                    if (ui == null || ui.revision != requested || !ui.root.isAttachedToWindow()) return;
                    ui.target = checked;
                    ui.decision = result;
                    try { ui.render(); } catch (Throwable t) { ClipHooks.hookError(t); }
                });
            } catch (Throwable t) { ClipHooks.hookError(t); }
        });
    }

    private void render() {
        boolean visible = state != null && !state.isEmpty();
        if (!visible || barFailed) {
            hideBar();
        } else {
            try {
                Boolean flag = nativeFlag;
                boolean expressive = media ? (flag != null ? flag : observedFlag) : storageExpressive;
                if (bar != null && !bar.shown()) {
                    bar = null;
                    throw new IllegalStateException("Clipboard bar no longer attached");
                }
                if (bar == null || expressive != barExpressive) {
                    hideBar();
                    bar = new PasteBar(root, media, expressive, this::start, this::clear);
                    barExpressive = expressive;
                }
                Context context = root.getContext();
                boolean refused = decision != null && !decision.ok && active == null;
                int count = state.items.size();
                String label = refused ? PasteRules.message(context, decision.reason, count, folderName(target))
                        : PasteLabels.here(japanese(context), count);
                bar.update(label, enabled(), refused, active != null);
            } catch (Throwable t) {
                barFailed = true;
                ClipHooks.hookError(t);
                hideBar();
            }
        }
        renderMenu();
    }

    private void hideBar() {
        PasteBar old = bar;
        bar = null;
        if (old != null) {
            try { old.remove(); } catch (Throwable t) { ClipHooks.hookError(t); }
        }
    }

    private boolean enabled() {
        return active == null && decision != null && decision.ok && root.isAttachedToWindow();
    }

    private void renderMenu() {
        Menu targetMenu = menu.get();
        if (targetMenu == null) return;
        try {
            if (state == null || state.isEmpty() || !root.isAttachedToWindow()) {
                removeItems(targetMenu);
                return;
            }
            Context context = root.getContext();
            MenuItem sort = targetMenu.findItem(PasteBar.id(context, "id", "sort"));
            if (sort == null || targetMenu.findItem(PasteBar.id(context, "id", "view_mode_switch")) == null) {
                removeItems(targetMenu);
                return;
            }
            boolean ja = japanese(context);
            item(targetMenu, PASTE, PasteLabels.menu(ja, context.getString(android.R.string.paste), state.items.size()),
                    this::start).setEnabled(sort.isEnabled() && enabled());
            item(targetMenu, CLEAR, ja ? "クリップボードをクリア" : "Clear clipboard",
                    this::clear).setEnabled(sort.isEnabled() && active == null);
        } catch (Throwable t) {
            ClipHooks.hookError(t);
            removeItems(targetMenu);
        }
    }

    private static MenuItem item(Menu menu, int id, String label, Runnable action) {
        MenuItem item = menu.findItem(id);
        if (item == null) {
            item = menu.add(Menu.NONE, id, Menu.NONE, label);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        } else if (!label.contentEquals(item.getTitle())) {
            item.setTitle(label);
        }
        item.setOnMenuItemClickListener(clicked -> {
            action.run();
            return true;
        });
        return item;
    }

    private static void removeItems(Menu menu) {
        menu.removeItem(PASTE);
        menu.removeItem(CLEAR);
    }

    private void clear() {
        try {
            if (active == null && root.isAttachedToWindow()) Clipboard.clear(root.getContext());
        } catch (Throwable t) { ClipHooks.hookError(t); }
    }

    private void start() {
        Request request = null;
        try {
            if (!enabled()) return;
            Context context = root.getContext();
            Clipboard.State latest = Clipboard.get(context);
            if (latest.isEmpty() || latest.createdAt != state.createdAt || !enabled()) {
                invalidate();
                return;
            }
            Activity host = activity(context);
            if (host == null || host.isFinishing() || host.isDestroyed()) return;
            PasteTarget chosen = target;
            request = new Request(++nextToken, latest, decision.needsPicker);
            active = request;
            Clipboard.notifyListeners();
            render();
            if (request.waitingForPicker) {
                Intent intent = new Intent(host, PastePickerActivity.class);
                intent.putExtra(PastePickerActivity.TOKEN, request.token);
                host.startActivity(intent);
            } else {
                submit(context.getApplicationContext(), request, chosen);
            }
        } catch (Throwable t) {
            ClipHooks.hookError(t);
            done(request);
        }
    }

    static boolean waitingForPicker(long token) {
        return active != null && active.token == token && active.waitingForPicker;
    }

    static void pickerResult(Context context, long token, String path) {
        if (!waitingForPicker(token)) return;
        Request request = active;
        try {
            if (path == null || !path.startsWith("/")) {
                done(request);
                return;
            }
            Clipboard.State latest = Clipboard.get(context);
            if (latest.isEmpty() || latest.createdAt != request.snapshot.createdAt) {
                done(request);
                return;
            }
            submit(context.getApplicationContext(), request, PasteTarget.ofFolder(new File(path)));
        } catch (Throwable t) {
            ClipHooks.hookError(t);
            done(request);
        }
    }

    static void pickerCancelled(long token) {
        if (waitingForPicker(token)) done(active);
    }

    private static void submit(Context app, Request request, PasteTarget destination) {
        request.waitingForPicker = false;
        ClipHooks.work(() -> {
            try {
                MoveQueue.paste(app, request.snapshot, destination.folder(), destination.createIfMissing(),
                        result -> done(request));
            } catch (Throwable t) {
                ClipHooks.hookError(t);
                MAIN.post(() -> done(request));
            }
        });
    }

    private static void done(Request request) {
        if (request != null && active == request) {
            active = null;
            Clipboard.notifyListeners();
        }
    }

    private static String folderName(PasteTarget target) {
        File folder = target == null ? null : target.folder();
        if (folder == null) return "";
        return folder.getName().isEmpty() ? folder.getPath() : folder.getName();
    }

    static boolean japanese(Context context) {
        return "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }

    private static final class Request {
        final long token;
        final Clipboard.State snapshot;
        boolean waitingForPicker;

        Request(long token, Clipboard.State snapshot, boolean waitingForPicker) {
            this.token = token;
            this.snapshot = snapshot;
            this.waitingForPicker = waitingForPicker;
        }
    }
}
