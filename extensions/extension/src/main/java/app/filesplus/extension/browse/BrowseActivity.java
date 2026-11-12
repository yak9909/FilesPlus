package app.filesplus.extension.browse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.settings.SettingsHooks;
import app.filesplus.extension.ui.FilesLook;
import app.filesplus.extension.ui.ThumbLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The destination browser (DESIGN 5.11.4): a full-screen picker modelled on the app's own advanced browsing picker.
 * The frame is the app's {@code advanced_browsing_picker_fragment} (view classes substituted by {@link FilesLook}),
 * the rows are the app's row layouts; when a layout cannot be used a look-alike built in code takes its place.
 * It only picks a destination: the chosen {@link Location} goes back through {@link PickRequests}, nothing is moved
 * here. Local (pick) mode only for now.
 */
public class BrowseActivity extends Activity {
    private static final String AREA = "browse";
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CAPSULE = 1;
    private static final int TYPE_ROW = 2;
    private static final int TYPE_GRID = 3;
    private static final int TYPE_MESSAGE = 4;

    private int requestId;
    private PickRequests.Pending pending;
    private LocalSource source;
    private boolean japanese;
    private int textPrimary;
    private int textSecondary;
    private int placeholderColor;

    private final ArrayList<Screen> stack = new ArrayList<>();
    private ExecutorService io;
    /** Bumped on every screen change: a load that finishes for an older screen is dropped. */
    private int token;
    private boolean destroyed;
    private boolean loaded;
    /** The current screen loaded without error: only then are the folder buttons and the breadcrumb shown. */
    private boolean actionable;
    private AlertDialog dialog;
    /** An android.window.OnBackInvokedCallback (API 33+), typed Object so the class is never touched below 33. */
    private Object backCallback;
    /** The app's picker frame is in use (it has the bar at the bottom that needs list padding). */
    private boolean originalFrame;

    private FilesLook.Toolbar toolbar;
    private ListView list;
    private View progress;
    private View crumbScroll;
    private LinearLayout crumbs;
    private View buttons;
    private TextView newFolderButton;
    private TextView actionButton;

    /** The API 33 back callback, in its own class so that it is never loaded on Android 12 (API 31/32). */
    private static final class BackApi {
        static Object register(final BrowseActivity activity) {
            android.window.OnBackInvokedCallback callback = activity::goBack;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(BrowseActivity activity, Object callback) {
            try {
                activity.getOnBackInvokedDispatcher()
                        .unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) callback);
            } catch (Throwable t) {
                // The window is going away anyway.
            }
        }
    }

    /** The views of one list row, kept in the row view's tag. */
    private static final class Holder {
        ImageView icon;
        TextView title;
        TextView subtitle;
        /** GRID rows: one entry per column. */
        View[] cells;
        ImageView[] thumbs;
        TextView[] titles;
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        // Same theme the Mod settings screen runs under (the app's preference theme), set before onCreate.
        int preferencesTheme = FilesLook.style(this, "PreferencesTheme");
        if (preferencesTheme != 0) setTheme(preferencesTheme);
        super.onCreate(state);
        requestId = getIntent().getIntExtra(PickRequests.EXTRA_REQUEST, 0);
        pending = PickRequests.peek(requestId, getIntent().getLongExtra(PickRequests.EXTRA_NONCE, 0));
        if (pending == null) {
            // The request is gone (answered, cancelled, or the process was recreated): nothing to pick for.
            DiagLog.i(AREA, "Browser closed: unknown request " + requestId);
            finish();
            return;
        }
        try {
            ModKit.init(this);
            japanese = SettingsHooks.isJapanese(this);
            source = new LocalSource(this);
            io = Executors.newFixedThreadPool(3);
            textPrimary = FilesLook.themeColor(this, "colorOnSurface", Color.DKGRAY);
            textSecondary = FilesLook.themeColor(this, "colorOnSurfaceVariant", Color.GRAY);
            placeholderColor = FilesLook.themeColor(this, "colorSurfaceVariant", Color.LTGRAY);
            buildShell();
            // The app opts in to predictive back, so on 33+ onBackPressed is not called: register a callback there.
            // Below 33 (minSdk is 31) onBackPressed is the only path.
            if (Build.VERSION.SDK_INT >= 33) backCallback = BackApi.register(this);
            // A recreation (dark mode, language) keeps the request but restarts at the root screen: accepted.
            open(new RootScreen());
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot open the destination browser", t);
            PickRequests.cancel(requestId);
            finish();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        try {
            if (loaded && !stack.isEmpty()) {
                // The grid has a different number of columns now: lay the loaded screen out again.
                Screen screen = top();
                saveScroll(screen);
                showRows(screen, screen.layout(this, columns()));
                list.setSelectionFromTop(screen.firstPosition, screen.firstTop);
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot relayout after a configuration change", t);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (dialog != null) dialog.dismiss();
        dialog = null;
        if (backCallback != null && Build.VERSION.SDK_INT >= 33) BackApi.unregister(this, backCallback);
        cancelThumbnails();
        if (io != null) io.shutdownNow();
        // A recreation (dark mode, language) keeps the request; leaving the browser any other way drops it.
        if (isFinishing()) PickRequests.cancel(requestId);
        super.onDestroy();
    }

    // ---- what screens use -------------------------------------------------------------------------------------

    LocalSource source() {
        return source;
    }

    PickRequests.PickSpec spec() {
        return pending.spec;
    }

    String tr(String ja, String en) {
        return japanese ? ja : en;
    }

    /** The app's own string of that name (translated by the app), or {@code fallback}. */
    String app(String name, String fallback) {
        return FilesLook.string(this, name, fallback);
    }

    String pickTitle() {
        String title = pending.spec.title;
        return title != null ? title : app("menu_item_move_to", tr("移動先を選ぶ", "Move to"));
    }

    /** "This item is already in %s" (app plural, formatted with the folder name). */
    String sameDirectory(String folderName, int count) {
        try {
            int id = FilesLook.resource(this, "error_move_to_same_directory", "plurals");
            if (id != 0) return getResources().getQuantityString(id, count, folderName);
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot format error_move_to_same_directory", t);
        }
        return tr("すでにこのフォルダにあります", "Already in this folder");
    }

    boolean isGone() {
        return destroyed || isFinishing();
    }

    /** Runs on the UI thread unless the browser is gone. */
    void post(final Runnable task) {
        runOnUiThread(() -> {
            if (isGone()) return;
            try {
                task.run();
            } catch (Throwable t) {
                DiagLog.e(AREA, "UI task failed", t);
            }
        });
    }

    void background(Runnable task) {
        try {
            io.execute(task);
        } catch (RuntimeException e) {
            // Shut down: the browser is closing.
        }
    }

    /** Called on the UI thread when a screen's rows changed in place (the counts of the root screen). */
    void changed(Screen screen) {
        if (!stack.isEmpty() && top() == screen && list.getAdapter() instanceof BaseAdapter) {
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        }
    }

    /** Shows a new screen on top of the current one. */
    void open(Screen screen) {
        if (!stack.isEmpty()) saveScroll(top());
        stack.add(screen);
        showTop();
    }

    private Screen top() {
        return stack.get(stack.size() - 1);
    }

    private void goBack() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            showTop();
        } else {
            // The top screen: cancel. Nothing is moved.
            PickRequests.cancel(requestId);
            finish();
        }
    }

    /**
     * A tap on a breadcrumb. Folders keep the category they were reached from, except the storage root, which is
     * the plain storage screen (5). An earlier screen for the same folder and category is returned to.
     */
    private void goTo(Location target, boolean storageRoot) {
        Screen current = top();
        Category wanted = storageRoot || !(current instanceof FolderScreen) ? null : ((FolderScreen) current).category;
        for (int i = stack.size() - 2; i >= 0; i--) {
            Screen screen = stack.get(i);
            if (screen instanceof FolderScreen && ((FolderScreen) screen).dir.equals(target)
                    && ((FolderScreen) screen).category == wanted) {
                while (stack.size() > i + 1) stack.remove(stack.size() - 1);
                showTop();
                return;
            }
        }
        open(new FolderScreen(target, wanted));
    }

    private void saveScroll(Screen screen) {
        if (!loaded || list == null) return;
        screen.firstPosition = list.getFirstVisiblePosition();
        View first = list.getChildAt(0);
        screen.firstTop = first == null ? 0 : first.getTop() - list.getPaddingTop();
    }

    // ---- loading and showing ----------------------------------------------------------------------------------

    private void showTop() {
        final Screen screen = top();
        final int mine = ++token;
        loaded = false;
        actionable = false;
        boolean unnamed = screen instanceof FolderScreen && !((FolderScreen) screen).hasName();
        toolbar.title.setText(unnamed ? "" : screen.title(this));
        setCrumbs(null);
        buttons.setVisibility(View.GONE);
        list.setPadding(0, 0, 0, 0);
        cancelThumbnails();
        list.setAdapter(null);
        progress.setVisibility(View.VISIBLE);
        final long started = System.nanoTime();
        background(() -> {
            Throwable failure = null;
            Object result = null;
            try {
                result = screen.load(this);
            } catch (Throwable t) {
                failure = t;
            }
            final Throwable error = failure;
            final Object data = result;
            runOnUiThread(() -> {
                // A stale load (the user already moved on) is dropped before it can touch the screen.
                if (isGone() || mine != token) return;
                DiagLog.i(AREA, "Loaded " + screen.getClass().getSimpleName() + " in "
                        + (System.nanoTime() - started) / 1_000_000 + " ms" + (error == null ? "" : " (failed)"));
                progress.setVisibility(View.GONE);
                List<Row> rows;
                boolean failed = false;
                if (error != null) {
                    DiagLog.e(AREA, "Cannot load " + screen.getClass().getSimpleName(), error);
                    rows = new ArrayList<>();
                    rows.add(Row.message(tr("読み込めませんでした。", "Could not load this.")));
                } else {
                    try {
                        screen.apply(this, data);
                        rows = screen.layout(this, columns());
                    } catch (Throwable t) {
                        DiagLog.e(AREA, "Cannot lay out " + screen.getClass().getSimpleName(), t);
                        rows = new ArrayList<>();
                        rows.add(Row.message(tr("表示できませんでした。", "Could not show this.")));
                        failed = true;
                    }
                }
                loaded = true;
                actionable = error == null && !failed;
                showRows(screen, rows);
                list.setSelectionFromTop(screen.firstPosition, screen.firstTop);
            });
        });
    }

    /** Cancels the thumbnail requests of the rows on screen: the loader is static and must not keep old views. */
    private void cancelThumbnails() {
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            Object tag = list.getChildAt(i).getTag();
            if (tag instanceof Holder) {
                Holder holder = (Holder) tag;
                if (holder.icon != null) ThumbLoader.cancel(holder.icon);
                if (holder.thumbs != null) for (ImageView thumb : holder.thumbs) ThumbLoader.cancel(thumb);
            }
        }
    }

    private void showRows(Screen screen, List<Row> rows) {
        cancelThumbnails();
        list.setAdapter(new RowAdapter(rows, columns()));
        toolbar.title.setText(screen.title(this));
        FolderScreen folder = actionable && screen instanceof FolderScreen ? (FolderScreen) screen : null;
        setCrumbs(folder);
        boolean buttonsShown = folder != null && source.canPickDestination();
        buttons.setVisibility(buttonsShown ? View.VISIBLE : View.GONE);
        // In the app's frame the bar floats over the list: leave room, like the app's own picker (120 dp). The
        // fallback frame puts the bar below the list instead.
        list.setPadding(0, 0, 0, buttonsShown && originalFrame ? FilesLook.dp(this, 120) : 0);
        if (buttonsShown) actionButton.setAlpha(folder.refusal() == null ? 1f : 0.38f);
    }

    /**
     * Number of grid columns (at least 3) from the window width in dp. Uses the configuration rather than the
     * list's width, which is stale until the next layout pass (rotation, multi-window).
     */
    private int columns() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp <= 0) {
            widthDp = Math.round(getResources().getDisplayMetrics().widthPixels
                    / getResources().getDisplayMetrics().density);
        }
        return Math.max(3, widthDp / 110);
    }

    // ---- breadcrumb -------------------------------------------------------------------------------------------

    private void setCrumbs(FolderScreen folder) {
        crumbs.removeAllViews();
        if (folder == null || folder.crumbNames().isEmpty()) {
            crumbScroll.setVisibility(View.GONE);
            return;
        }
        crumbScroll.setVisibility(View.VISIBLE);
        List<String> names = folder.crumbNames();
        final List<Location> targets = folder.crumbTargets();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) crumbs.addView(crumbArrow());
            TextView text = crumbText(names.get(i));
            if (i < names.size() - 1) {
                final Location target = targets.get(i);
                final boolean storageRoot = i == 0;
                text.setOnClickListener(v -> safely(() -> goTo(target, storageRoot)));
            } else {
                // The current folder reads as the strongest crumb.
                text.setTextColor(textPrimary);
            }
            crumbs.addView(text);
        }
        crumbScroll.post(() -> {
            if (crumbScroll instanceof HorizontalScrollView) ((HorizontalScrollView) crumbScroll).fullScroll(View.FOCUS_RIGHT);
        });
    }

    private TextView crumbText(String label) {
        View view = FilesLook.inflate(this, "breadcrumb_item_text", crumbs);
        TextView text;
        if (view instanceof TextView) {
            text = (TextView) view;
        } else {
            text = new TextView(this);
            text.setTextColor(textSecondary);
            text.setSingleLine(true);
            text.setPadding(FilesLook.dp(this, 8), FilesLook.dp(this, 16), FilesLook.dp(this, 8),
                    FilesLook.dp(this, 16));
        }
        text.setText(label);
        // A tap target with feedback, like the app's breadcrumb.
        text.setBackground(selectableBackground());
        return text;
    }

    private View crumbArrow() {
        View view = FilesLook.inflate(this, "breadcrumb_item_arrow", crumbs);
        if (view != null) return view;
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(textSecondary);
        arrow.setGravity(Gravity.CENTER);
        return arrow;
    }

    private Drawable selectableBackground() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true) && value.resourceId != 0) {
            return getDrawable(value.resourceId);
        }
        return null;
    }

    // ---- shell ------------------------------------------------------------------------------------------------

    private void buildShell() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(FilesLook.themeColor(this, "colorSurface", Color.WHITE));
        setContentView(root);
        // The window draws edge to edge: keep the content out of the system bars (as the Mod settings do).
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
        boolean original = false;
        try {
            original = inflateShell(root);
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot use the app's picker layout", t);
        }
        if (!original) {
            root.removeAllViews();
            buildFallbackShell(root);
        }
        originalFrame = original;
        DiagLog.i(AREA, "Browser frame: " + (original ? "original advanced_browsing_picker_fragment" : "fallback"));
        configureList();
        newFolderButton.setOnClickListener(v -> safely(() -> showNewFolderDialog()));
        actionButton.setOnClickListener(v -> safely(() -> moveHere()));
    }

    /** The app's picker frame. Returns false (and leaves nothing half built) when its parts are not what we expect. */
    private boolean inflateShell(FrameLayout root) {
        View main = FilesLook.inflate(this, "advanced_browsing_picker_fragment", root);
        if (main == null) return false;
        View appbar = find(main, "appbar_layout");
        View oldToolbar = find(main, "toolbar");
        View crumb = find(main, "breadcrumb_view");
        View content = find(main, "content_list");
        View wait = find(main, "progress_bar");
        View section = find(main, "button_section");
        View newFolder = find(main, "new_folder_action");
        View action = find(main, "action_button");
        if (!(appbar instanceof ViewGroup) || !(content instanceof ListView) || wait == null || section == null
                || !(newFolder instanceof TextView) || !(action instanceof TextView)
                || !(crumb instanceof HorizontalScrollView)) {
            return false;
        }
        // The app's own single-button bar is visible by default (the app hides it from code): not used here.
        View single = find(main, "action_button_container");
        if (single != null) single.setVisibility(View.GONE);
        // MaterialToolbar's methods cannot be called from here: replace it with an equivalent plain bar.
        if (oldToolbar != null && oldToolbar.getParent() == appbar) ((ViewGroup) appbar).removeView(oldToolbar);
        toolbar = FilesLook.toolbar(this, "", tr("戻る", "Back"), v -> safely(this::goBack), textPrimary);
        ((ViewGroup) appbar).addView(toolbar.view, 0);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        ((HorizontalScrollView) crumb).addView(inner, new FrameLayout.LayoutParams(-2, -2));
        ((HorizontalScrollView) crumb).setHorizontalScrollBarEnabled(false);
        crumbs = inner;
        crumbScroll = crumb;
        list = (ListView) content;
        progress = wait;
        buttons = section;
        newFolderButton = (TextView) newFolder;
        actionButton = (TextView) action;
        actionButton.setText(app("move_here", tr("ここに移動", "Move here")));
        root.addView(main);
        return true;
    }

    /** A plain look-alike (framework views only) for when the app's picker layout cannot be used. */
    private void buildFallbackShell(FrameLayout root) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        toolbar = FilesLook.toolbar(this, "", tr("戻る", "Back"), v -> safely(this::goBack), textPrimary);
        column.addView(toolbar.view);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        crumbs = new LinearLayout(this);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(crumbs, new FrameLayout.LayoutParams(-2, -2));
        crumbScroll = scroll;
        column.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        ProgressBar wait = new ProgressBar(this);
        wait.setVisibility(View.GONE);
        body.addView(wait, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        progress = wait;
        column.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bar = new LinearLayout(this);
        int pad = FilesLook.dp(this, 12);
        bar.setPadding(pad, pad, pad, pad);
        Button newFolder = new Button(this);
        newFolder.setText(app("new_folder_button_title", tr("新しいフォルダ", "New folder")));
        Button action = new Button(this);
        action.setText(app("move_here", tr("ここに移動", "Move here")));
        bar.addView(newFolder, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(action, new LinearLayout.LayoutParams(0, -2, 1));
        buttons = bar;
        newFolderButton = newFolder;
        actionButton = action;
        column.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
    }

    private void configureList() {
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        buttons.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Row row = rowAt(position);
            if (row != null && row.onClick != null) safely(row.onClick);
        });
        // A row that goes back to the recycler must not receive a late thumbnail.
        list.setRecyclerListener(view -> {
            Object tag = view.getTag();
            if (tag instanceof Holder) {
                Holder holder = (Holder) tag;
                if (holder.icon != null) ThumbLoader.cancel(holder.icon);
                if (holder.thumbs != null) for (ImageView thumb : holder.thumbs) ThumbLoader.cancel(thumb);
            }
        });
    }

    private Row rowAt(int position) {
        Object adapter = list.getAdapter();
        return adapter instanceof RowAdapter ? ((RowAdapter) adapter).rows.get(position) : null;
    }

    private View find(View root, String idName) {
        int id = FilesLook.id(this, idName);
        return id == 0 ? null : root.findViewById(id);
    }

    private void safely(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            DiagLog.e(AREA, "Browser action failed", t);
            Toast.makeText(this, tr("操作に失敗しました。診断ログを確認してください。", "Action failed. See diagnostic log."),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---- buttons ----------------------------------------------------------------------------------------------

    private void moveHere() {
        Screen screen = top();
        if (!actionable || !(screen instanceof FolderScreen)) return;
        FolderScreen folder = (FolderScreen) screen;
        String refusal = folder.refusal();
        if (refusal != null) {
            Toast.makeText(this, refusal, Toast.LENGTH_LONG).show();
            return;
        }
        Location destination = folder.dir;
        DiagLog.i(AREA, "Destination picked: " + destination);
        // Close first, then answer: the caller (confirmation, queue) runs once the browser is on its way out.
        finish();
        PickRequests.deliver(requestId, destination);
    }

    private void showNewFolderDialog() {
        Screen screen = top();
        if (isGone() || !actionable || !(screen instanceof FolderScreen)) return;
        final FolderScreen folder = (FolderScreen) screen;
        final Location parent = folder.dir;
        final int shownToken = token;
        View content = FilesLook.inflate(this, "new_folder_content", null);
        View field = content == null ? null : find(content, "folder_name_edit_text");
        final EditText edit;
        if (field instanceof EditText) {
            edit = (EditText) field;
        } else {
            LinearLayout box = new LinearLayout(this);
            int pad = FilesLook.dp(this, 24);
            box.setPadding(pad, FilesLook.dp(this, 10), pad, 0);
            edit = new EditText(this);
            edit.setSingleLine(true);
            edit.setHint(app("new_folder_hint", tr("新しいフォルダの名前", "Enter new folder name")));
            box.addView(edit, new LinearLayout.LayoutParams(-1, -2));
            content = box;
        }
        if (dialog != null) dialog.dismiss();
        final AlertDialog created = new AlertDialog.Builder(this)
                .setTitle(app("new_folder_title", tr("新しいフォルダ", "New folder")))
                .setView(content)
                .setPositiveButton(app("new_folder_create_folder", tr("作成", "Create")), null)
                .setNegativeButton(app("cancel", tr("キャンセル", "Cancel")), null)
                .create();
        dialog = created;
        FilesLook.styleDialog(this, created);
        if (created.getWindow() != null) {
            created.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        created.show();
        final Button create = created.getButton(AlertDialog.BUTTON_POSITIVE);
        // Set after show() so that an error keeps the dialog open.
        create.setOnClickListener(v -> safely(() ->
                createFolder(created, create, edit, parent, folder.category, shownToken)));
        edit.requestFocus();
    }

    private void createFolder(final AlertDialog created, final Button create, final EditText edit,
            final Location parent, final Category category, final int shownToken) {
        final String name = edit.getText().toString().trim();
        if (name.isEmpty()) {
            edit.setError(app("new_folder_warning_empty_folder_name",
                    tr("フォルダ名を入力してください", "This folder needs a name")));
            return;
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > 255) {
            edit.setError(app("new_folder_create_error_too_long", tr("名前を短くしてください", "Please make the name shorter")));
            return;
        }
        create.setEnabled(false);
        background(() -> {
            Location made = null;
            String error = null;
            try {
                // One level only, never overwrites: an existing name fails here.
                made = source.mkdir(parent, name);
            } catch (FileAlreadyExistsException e) {
                error = app("folder_name_already_exists_error_message",
                        tr("この名前はすでに使われています", "This name is already being used. Try a different name."));
            } catch (Throwable t) {
                DiagLog.e(AREA, "mkdir failed in " + parent, t);
                error = app("new_folder_warning_cannot_create_folder", tr("フォルダを作成できません", "Cannot create folder"));
            }
            final Location result = made;
            final String message = error;
            post(() -> {
                // Cancelled or navigated away meanwhile (the folder may exist now): do not jump anywhere.
                if (!created.isShowing() || shownToken != token) {
                    if (message == null) DiagLog.i(AREA, "Folder created after the dialog was left: " + result);
                    return;
                }
                create.setEnabled(true);
                if (message != null) {
                    edit.setError(message);
                    return;
                }
                created.dismiss();
                // Into the new folder, like the app's own picker.
                open(new FolderScreen(result, category));
            });
        });
    }

    // ---- rows -------------------------------------------------------------------------------------------------

    private final class RowAdapter extends BaseAdapter {
        final List<Row> rows;
        private final int columns;

        RowAdapter(List<Row> rows, int columns) {
            this.rows = rows;
            this.columns = columns;
        }

        @Override public int getCount() {
            return rows.size();
        }

        @Override public Object getItem(int position) {
            return rows.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public int getViewTypeCount() {
            return 5;
        }

        @Override public int getItemViewType(int position) {
            switch (rows.get(position).kind) {
                case HEADER: return TYPE_HEADER;
                case CAPSULE: return TYPE_CAPSULE;
                case GRID: return TYPE_GRID;
                case MESSAGE: return TYPE_MESSAGE;
                default: return TYPE_ROW;
            }
        }

        @Override public boolean areAllItemsEnabled() {
            return false;
        }

        @Override public boolean isEnabled(int position) {
            return rows.get(position).onClick != null;
        }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            Row row = rows.get(position);
            View view = convert;
            try {
                if (view == null) view = create(getItemViewType(position), parent);
                bind(row, view);
            } catch (Throwable t) {
                DiagLog.e(AREA, "Cannot show a row", t);
                if (view == null) view = new View(parent.getContext());
            }
            return view;
        }

        private View create(int type, ViewGroup parent) {
            switch (type) {
                case TYPE_HEADER: return createHeader();
                case TYPE_CAPSULE: return createCapsule(parent);
                case TYPE_GRID: return createGrid(parent, columns);
                case TYPE_MESSAGE: return createMessage(parent);
                default: return createRow(parent);
            }
        }

        private void bind(Row row, View view) {
            Holder holder = (Holder) view.getTag();
            switch (row.kind) {
                case HEADER:
                    ((TextView) view).setText(row.title);
                    break;
                case CAPSULE:
                    holder.icon.setImageDrawable(FilesLook.drawable(BrowseActivity.this, row.icon));
                    holder.title.setText(row.title);
                    setSubtitle(holder.subtitle, row.subtitle);
                    break;
                case GRID:
                    bindGrid(row, holder);
                    break;
                case MESSAGE:
                    if (row.title != null) holder.title.setText(row.title);
                    break;
                default:
                    bindRow(row, holder, view);
            }
        }

        private void bindGrid(Row row, Holder holder) {
            int px = Math.max(64, getResources().getDisplayMetrics().widthPixels / columns);
            for (int i = 0; i < holder.cells.length; i++) {
                if (i < row.cells.size()) {
                    Entry entry = row.cells.get(i);
                    holder.cells[i].setVisibility(View.VISIBLE);
                    holder.titles[i].setText(entry.name);
                    holder.thumbs[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
                    ThumbLoader.load(holder.thumbs[i], source, entry, px, new ColorDrawable(placeholderColor));
                } else {
                    // The last line of a grid: keep the cell's width, show nothing.
                    holder.cells[i].setVisibility(View.INVISIBLE);
                    ThumbLoader.cancel(holder.thumbs[i]);
                }
            }
        }

        private void bindRow(Row row, Holder holder, View view) {
            holder.title.setText(row.title);
            setSubtitle(holder.subtitle, row.subtitle);
            int px = FilesLook.dimenPx(BrowseActivity.this, "advanced_browsing_list_item_image_size", 48);
            Entry entry = row.entry;
            boolean thumbnail = entry != null && entry.mediaId != 0 && entry.category != null
                    && entry.category != Category.DOCUMENTS && entry.category != Category.DOWNLOADS;
            if (thumbnail) {
                holder.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ThumbLoader.load(holder.icon, source, entry, px, new ColorDrawable(placeholderColor));
            } else {
                // Folders, and files that have no thumbnail: the app's icon (this also cancels a pending load).
                holder.icon.setScaleType(ImageView.ScaleType.CENTER);
                ThumbLoader.load(holder.icon, null, null, px, FilesLook.drawable(BrowseActivity.this, row.icon));
            }
            view.setAlpha(row.kind == Row.Kind.FILE ? 0.45f : 1f);
        }
    }

    private static void setSubtitle(TextView view, CharSequence text) {
        view.setText(text);
        view.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private TextView createHeader() {
        int style = FilesLook.style(this, "BrowseTabGm3TitleStyle");
        TextView header = new TextView(this, null, 0, style);
        if (style == 0) {
            header.setTextSize(16);
            header.setTextColor(textPrimary);
        }
        header.setPadding(FilesLook.dp(this, 16), FilesLook.dp(this, 14), FilesLook.dp(this, 16),
                FilesLook.dp(this, 14));
        header.setAccessibilityHeading(true);
        header.setTag(new Holder());
        header.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
        return header;
    }

    /** A category or storage line: the app's browse_capsule_item_view. */
    private View createCapsule(ViewGroup parent) {
        Holder holder = new Holder();
        View item = FilesLook.inflate(this, "browse_capsule_item_view", parent);
        if (item != null) {
            holder.icon = (ImageView) find(item, "icon");
            holder.title = (TextView) find(item, "title");
            holder.subtitle = (TextView) find(item, "subtitle");
            if (holder.icon == null || holder.title == null || holder.subtitle == null) item = null;
        }
        if (item == null) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(FilesLook.dp(this, 64));
            Drawable ripple = FilesLook.drawable(this, "capsule_item_ripple");
            if (ripple != null) row.setBackground(ripple);
            holder.icon = new ImageView(this);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(FilesLook.dp(this, 34),
                    FilesLook.dp(this, 34));
            iconParams.setMarginStart(FilesLook.dp(this, 16));
            iconParams.setMarginEnd(FilesLook.dp(this, 16));
            row.addView(holder.icon, iconParams);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            holder.title = styledText("CapsuleItemGm3TitleStyle", 14, textPrimary);
            holder.subtitle = styledText("CapsuleItemGm3SubtitleStyle", 12, textSecondary);
            texts.addView(holder.title);
            texts.addView(holder.subtitle);
            row.addView(texts);
            item = row;
        }
        // BrowseCapsuleItemStyle has a 8 dp end margin that a list cannot apply: the wrapper supplies it.
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(0, 0, FilesLook.dp(this, 8), 0);
        item.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));
        wrapper.addView(item);
        wrapper.setTag(holder);
        wrapper.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
        return wrapper;
    }

    /** A folder or file line: the app's row_item_view. */
    private View createRow(ViewGroup parent) {
        Holder holder = new Holder();
        View item = FilesLook.inflate(this, "row_item_view", parent);
        if (item != null) {
            holder.icon = (ImageView) find(item, "thumbnail");
            holder.title = (TextView) find(item, "title");
            holder.subtitle = (TextView) find(item, "subtitle");
            if (holder.icon == null || holder.title == null || holder.subtitle == null) {
                item = null;
            } else {
                // The app hides these per row when it binds one; nothing binds them here.
                hide(item, "top_shadow");
                hide(item, "thumbnail_top_end_icon");
                hide(item, "saved_position_progressbar_container");
                holder.subtitle.setMaxLines(3);
            }
        }
        if (item == null) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int icon = FilesLook.dimenPx(this, "advanced_browsing_list_item_image_size", 48);
            row.setPadding(FilesLook.dp(this, 8), FilesLook.dp(this, 4), FilesLook.dp(this, 8), FilesLook.dp(this, 4));
            holder.icon = new ImageView(this);
            row.addView(holder.icon, new LinearLayout.LayoutParams(icon, icon));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            holder.title = styledText("ListItemTitleText", 16, textPrimary);
            holder.title.setSingleLine(true);
            holder.subtitle = styledText("ListItemSubtitleTextBlack", 14, textSecondary);
            texts.addView(holder.title);
            texts.addView(holder.subtitle);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
            textParams.setMarginStart(FilesLook.dp(this, 16));
            row.addView(texts, textParams);
            item = row;
        }
        // The layout's own margins (RowItemView) are ignored by a list: the wrapper supplies them.
        FrameLayout wrapper = new FrameLayout(this);
        int horizontal = FilesLook.dimenPx(this, "row_item_horizontal_margin", 8);
        int vertical = FilesLook.dimenPx(this, "row_item_vertical_margin", 4);
        wrapper.setPadding(horizontal, vertical, horizontal, vertical);
        item.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));
        wrapper.addView(item);
        wrapper.setTag(holder);
        wrapper.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
        return wrapper;
    }

    /** One line of the picture grid: {@code columns} square_item_view cells. */
    private View createGrid(ViewGroup parent, int columns) {
        Holder holder = new Holder();
        holder.cells = new View[columns];
        holder.thumbs = new ImageView[columns];
        holder.titles = new TextView[columns];
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        int gap = FilesLook.dp(this, 2);
        line.setPadding(gap * 2, 0, gap * 2, 0);
        int cellPx = Math.max(64, getResources().getDisplayMetrics().widthPixels / columns);
        for (int i = 0; i < columns; i++) {
            View cell = FilesLook.inflate(this, "square_item_view", line);
            if (cell != null) {
                holder.thumbs[i] = (ImageView) find(cell, "thumbnail");
                holder.titles[i] = (TextView) find(cell, "title");
                if (holder.thumbs[i] == null || holder.titles[i] == null) cell = null;
            }
            if (cell == null) {
                FrameLayout box = new FrameLayout(this);
                box.setBackground(new ColorDrawable(placeholderColor));
                holder.thumbs[i] = new ImageView(this);
                box.addView(holder.thumbs[i], new FrameLayout.LayoutParams(-1, -1));
                holder.titles[i] = styledText("GridItemFileNameText", 12, textSecondary);
                holder.titles[i].setSingleLine(true);
                box.addView(holder.titles[i], new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                cell = box;
                line.addView(cell, new LinearLayout.LayoutParams(0, cellPx, 1));
            } else {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                params.setMargins(gap, gap, gap, gap);
                line.addView(cell, params);
            }
            holder.cells[i] = cell;
        }
        line.setTag(holder);
        line.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
        return line;
    }

    private View createMessage(ViewGroup parent) {
        Holder holder = new Holder();
        View blob = FilesLook.inflate(this, "no_files_blob", parent);
        TextView text = blob == null ? null : (TextView) find(blob, "empty_state_text");
        if (blob == null || text == null) {
            text = styledText("EmptyStateText", 14, textSecondary);
            text.setText(app("empty_state_text", tr("ここには何もありません", "There's nothing here.")));
            text.setGravity(Gravity.CENTER);
            blob = text;
        }
        holder.title = text;
        // The layout fills its parent; in a list it gets a fixed, roomy height instead.
        FrameLayout wrapper = new FrameLayout(this);
        blob.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        wrapper.addView(blob);
        wrapper.setTag(holder);
        wrapper.setLayoutParams(new AbsListView.LayoutParams(-1, FilesLook.dp(this, 320)));
        return wrapper;
    }

    private void hide(View root, String idName) {
        View view = find(root, idName);
        if (view != null) view.setVisibility(View.GONE);
    }

    /** A TextView with the app's named style; a missing style gives a plain look in {@code color}. */
    private TextView styledText(String styleName, int fallbackSp, int color) {
        int style = FilesLook.style(this, styleName);
        TextView view = new TextView(this, null, 0, style);
        if (style == 0) {
            view.setTextSize(fallbackSp);
            view.setTextColor(color);
        }
        return view;
    }
}
