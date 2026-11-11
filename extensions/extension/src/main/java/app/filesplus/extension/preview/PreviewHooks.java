package app.filesplus.extension.preview;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Toast;

import android.webkit.MimeTypeMap;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.move.DestinationSheet;
import app.filesplus.extension.move.MoveQueue;

/**
 * Entry points called from the smali bridges of the "Multi-select in preview" patch.
 * App types never appear here: the current file arrives as its MediaStore id plus an opaque entry.
 * Every method catches Throwable and falls back to the original behavior.
 */
public final class PreviewHooks {
    private static final String AREA = "preview";
    // Fixed ids (no app resources); "PS1" / "PS2" in ASCII.
    private static final int SELECT_ITEM = 0x7e505331;
    private static final int COUNT_ITEM = 0x7e505332;
    private static final int MOVE_ITEM = 0x7e505333; // "PS3"

    /** The last gallery root that was shown, to clear its check mark when the store is handed over. */
    private static WeakReference<View> lastRoot;
    /** MediaStore id of the file on the current page (0 = none); tracked for the move queue. */
    private static volatile long currentId;
    /** Set once the "Move queue in preview" patch registered the pager controller: only then "Send to…" is offered. */
    private static volatile boolean moveQueueApplied;
    /** The newest preview controller (weak: it is owned by the preview fragment). */
    private static WeakReference<PagerControl> pager;

    private PreviewHooks() {}

    public static void hookError(Throwable t) {
        DiagLog.e(AREA, "Preview hook failed", t);
    }

    /** Called at the end of the preview controller's constructor by the "Move queue in preview" patch. */
    public static void registerPager(Object controller) {
        try {
            if (!(controller instanceof PagerControl)) return;
            pager = new WeakReference<>((PagerControl) controller);
            if (!moveQueueApplied) {
                moveQueueApplied = true;
                DiagLog.i(AREA, "Move queue pager hook is active");
            }
        } catch (Throwable t) { hookError(t); }
    }

    private static PagerControl pager() {
        WeakReference<PagerControl> ref = pager;
        return ref == null ? null : ref.get();
    }

    /**
     * Called right after the app inflated its preview menu (never for entries where the app
     * returns before inflating). {@code hasId} is false when the file has no MediaStore id.
     */
    public static void onMenuCreated(View root, Menu menu, long id, boolean hasId, Object entry) {
        try {
            if (menu == null) return;
            Context context = root != null ? root.getContext() : ModKit.context();
            ListHandoff.notePreviewActivity(activity(context));
            boolean ja = japanese(context);
            if (hasId && menu.findItem(SELECT_ITEM) == null) {
                String title = SelectionStore.isSelected(id)
                        ? (ja ? "選択を解除" : "Deselect") : (ja ? "選択" : "Select");
                menu.add(Menu.NONE, SELECT_ITEM, Menu.NONE, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            }
            if (moveQueueApplied && hasId && menu.findItem(MOVE_ITEM) == null) {
                menu.add(Menu.NONE, MOVE_ITEM, Menu.NONE, ja ? "移動先へ送る…" : "Send to…")
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            }
            // The first page is shown without a page-change callback, so the menu also syncs the move queue.
            notePage(id, hasId);
            int count = SelectionStore.count();
            if (count > 0 && menu.findItem(COUNT_ITEM) == null) {
                String title = ja ? "選択中 " + count + " 件" : count + " selected";
                menu.add(Menu.NONE, COUNT_ITEM, Menu.NONE, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            }
            // The first page is shown without a page-change callback, so the menu also syncs the check mark.
            updateOverlay(root, id, hasId);
        } catch (Throwable t) { hookError(t); }
    }

    /** Called when the gallery pager settles on another file: moves the check mark with it. */
    public static void onPageSelected(View galleryRoot, long id, boolean hasId) {
        try {
            notePage(id, hasId);
            updateOverlay(galleryRoot, id, hasId);
        } catch (Throwable t) { hookError(t); }
    }

    /** Remembers the current page and lets the move queue release held jobs of files that are no longer shown. */
    private static void notePage(long id, boolean hasId) {
        currentId = hasId ? id : 0L;
        MoveQueue.onPageChanged(currentId);
    }

    /**
     * The store was handed to the list: a preview screen that is still alive must not keep showing
     * the check mark or the "Deselect" / "n selected" items.
     */
    static void onStoreDrained() {
        try {
            View root = lastRoot == null ? null : lastRoot.get();
            if (root == null) return;
            updateOverlay(root, 0L, false);
            Activity activity = activity(root.getContext());
            if (activity != null) activity.invalidateOptionsMenu();
        } catch (Throwable t) { hookError(t); }
    }

    /** Cheap pre-check so the bridge only reads the current file for our own items. */
    public static boolean handles(int itemId) {
        return itemId == SELECT_ITEM || itemId == COUNT_ITEM || itemId == MOVE_ITEM;
    }

    /** Returns true when the click was one of our items and has been handled. */
    public static boolean onMenuItem(View root, int itemId, long id, boolean hasId, Object entry) {
        try {
            if (!handles(itemId)) return false;
            Context context = root != null ? root.getContext() : ModKit.context();
            if (itemId == MOVE_ITEM) {
                openMoveSheet(activity(context), id, hasId);
                return true;
            }
            if (itemId == SELECT_ITEM) {
                if (hasId) {
                    boolean selected = SelectionStore.toggle(id, entry);
                    DiagLog.i(AREA, (selected ? "Selected " : "Deselected ") + id + ", now " + SelectionStore.count());
                }
            } else {
                int cleared = SelectionStore.count();
                SelectionStore.clear();
                DiagLog.i(AREA, "Cleared " + cleared + " selected files");
                if (context != null) {
                    boolean ja = japanese(context);
                    Toast.makeText(context.getApplicationContext(),
                            ja ? cleared + " 件の選択を解除しました" : "Cleared " + cleared,
                            Toast.LENGTH_SHORT).show();
                }
            }
            updateOverlay(root, id, hasId);
            Activity activity = activity(context);
            if (activity != null) activity.invalidateOptionsMenu();
            return true;
        } catch (Throwable t) {
            hookError(t);
            return false;
        }
    }

    /** "Send to…": asks for a destination, then queues the preview selection, or else the current file. */
    private static void openMoveSheet(Activity activity, long id, boolean hasId) {
        if (activity == null) {
            DiagLog.e(AREA, "Send to: no Activity for the sheet", null);
            return;
        }
        int selected = SelectionStore.count();
        if (selected == 0 && !hasId) return;
        DestinationSheet.show(activity, selected, folder -> {
            try {
                boolean fromSelection = SelectionStore.count() > 0;
                long[] ids = fromSelection ? SelectionStore.ids() : (hasId ? new long[]{id} : new long[0]);
                if (ids.length == 0) return;
                if (fromSelection) {
                    SelectionStore.clear();
                    onStoreDrained(); // drop the check mark and the "Deselect" / "n selected" items
                }
                queueMove(ids, folder);
            } catch (Throwable t) { hookError(t); }
        });
    }

    /**
     * Looks up the real MIME types off the main thread, then queues on the main thread so the queue sees the
     * page that is current at that moment (or a closed preview), and updates the pager.
     *
     * <p>Pager rule (exactly one file sent): the file leaves the pager at once only if it is still the file on the
     * current page, it is not held (a playing video/audio is moved after the user leaves its page) and the preview
     * is still open. If it was the only entry, the preview is closed instead (which also releases a held job). If
     * the app has to reload its list from MediaStore to drop the entry, that reload runs after the move finished
     * (and only while the same file is still shown). Several files never change the pager (removing or reloading
     * would shift the page index the app keeps), except that the preview closes when its only entry is among them.
     */
    private static void queueMove(long[] ids, File folder) {
        Context app = ModKit.context();
        if (app == null) {
            DiagLog.e(AREA, "Send to: no application context", null);
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String[] mimes;
            boolean sameFolder = false;
            try {
                String[] paths = new String[ids.length];
                mimes = mimesOf(app, ids, paths);
                sameFolder = allInFolder(paths, folder);
            } catch (Throwable t) {
                hookError(t);
                mimes = new String[ids.length];
                Arrays.fill(mimes, UNKNOWN_MIME);
            }
            String[] result = mimes;
            boolean skipAll = sameFolder;
            main.post(() -> {
                try {
                    // Keep the "never move what is playing" rule: tell the queue which page is current right now.
                    boolean closing = ListHandoff.previewClosing();
                    if (closing) MoveQueue.onPreviewClosed();
                    else MoveQueue.onPageChanged(currentId);
                    // The preview instance known right now (the sheet's Activity may be gone after a rotation).
                    sendAndUpdatePager(app, ListHandoff.knownPreview(), ids, result, folder, closing || skipAll);
                } catch (Throwable t) { hookError(t); }
            });
        }, "filesplus-move-mime").start();
    }

    /** {@code leavePagerAlone}: the preview is closing, or every file is already in the destination (all skipped). */
    private static void sendAndUpdatePager(Context app, Activity activity, long[] ids, String[] mimes, File folder,
                                           boolean leavePagerAlone) {
        long shown = currentId;
        PagerControl control = leavePagerAlone ? null : pager();
        boolean onPage = false;
        for (long id : ids) onPage |= id != 0L && id == shown;
        // Only the file on the page the user is looking at may leave the pager, and only when it is the one file sent.
        boolean touchesPage = control != null && onPage;
        boolean single = ids.length == 1;
        boolean last = touchesPage && control.modKitIsLastEntry();

        AtomicBoolean reloadLater = new AtomicBoolean();
        WeakReference<PagerControl> controlRef = new WeakReference<>(control);
        MoveQueue.Done done = null;
        if (touchesPage && single && !last) {
            done = (moved, notMoved) -> {
                if (!reloadLater.get() || moved == 0) return;
                PagerControl now = controlRef.get();
                // Same preview, and the user still looks at the moved file (so the page index is still right).
                if (now == null || now != pager() || ListHandoff.previewClosing() || currentId != shown) return;
                now.modKitReload();
                DiagLog.i(AREA, "Reloaded the preview list after the move");
            };
        }
        int held = MoveQueue.enqueue(app, ids, mimes, folder, done);
        if (last) {
            // Nothing left to show: close the preview. A held video/audio is moved once the preview is closed.
            if (activity != null && !activity.isFinishing()) activity.finish();
        } else if (touchesPage && single && held == 0) {
            reloadLater.set(control.modKitRemoveCurrent());
        }
        // else: held (moved when its page is left) or several files: the pager keeps its entries.
    }

    /** Treated like video by the queue (held while its page is open): used when the type cannot be determined. */
    private static final String UNKNOWN_MIME = "video/unknown";

    /** MediaStore MIME types in the order of {@code ids}; unknown or generic ones fall back to the file extension. */
    private static String[] mimesOf(Context context, long[] ids, String[] pathsOut) {
        Map<Long, String> known = new HashMap<>();
        Map<Long, String> paths = new HashMap<>();
        Uri files = MediaStore.Files.getContentUri("external");
        for (int from = 0; from < ids.length; from += 400) {
            int to = Math.min(ids.length, from + 400);
            String[] args = new String[to - from];
            for (int i = from; i < to; i++) args[i - from] = Long.toString(ids[i]);
            String selection = "_id IN (" + String.join(",", Collections.nCopies(args.length, "?")) + ")";
            try (Cursor cursor = context.getContentResolver().query(files,
                    new String[]{"_id", "mime_type", "_data"}, selection, args, null)) {
                if (cursor == null) continue;
                while (cursor.moveToNext()) {
                    String mime = cursor.getString(1);
                    if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
                        String path = cursor.getString(2);
                        String extension = path == null ? "" : MimeTypeMap.getFileExtensionFromUrl(path.replace(" ", "%20"));
                        String guessed = extension.isEmpty() ? null
                                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
                        mime = guessed != null ? guessed : UNKNOWN_MIME;
                    }
                    known.put(cursor.getLong(0), mime);
                    String path = cursor.getString(2);
                    if (path != null) paths.put(cursor.getLong(0), path);
                }
            }
        }
        String[] mimes = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            String mime = known.get(ids[i]);
            mimes[i] = mime != null ? mime : UNKNOWN_MIME;
            pathsOut[i] = paths.get(ids[i]);
        }
        return mimes;
    }

    /** True when every file already lives in {@code folder} (the queue would only skip them). Unknown paths: false. */
    private static boolean allInFolder(String[] paths, File folder) {
        try {
            File target = folder.getCanonicalFile();
            for (String path : paths) {
                if (path == null) return false;
                File parent = new File(path).getParentFile();
                if (parent == null || !parent.getCanonicalFile().equals(target)) return false;
            }
            return paths.length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Shows or hides the single check overlay on the gallery root. The pager recycles its pages, so
     * the mark lives on the root and follows the current file. It is created lazily, only when needed.
     */
    private static void updateOverlay(View root, long id, boolean hasId) {
        if (!(root instanceof ViewGroup)) return;
        lastRoot = new WeakReference<>(root);
        ViewGroup group = (ViewGroup) root;
        boolean show = hasId && SelectionStore.isSelected(id);
        CheckBadge badge = null;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            if (group.getChildAt(i) instanceof CheckBadge) {
                badge = (CheckBadge) group.getChildAt(i);
                break;
            }
        }
        if (badge == null) {
            if (!show) return;
            badge = new CheckBadge(group.getContext());
            group.addView(badge, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        badge.setVisibility(show ? View.VISIBLE : View.GONE);
        badge.invalidate();
    }

    /**
     * The check mark: one full-size view that draws a small circle with a tick below the toolbar on the
     * right. It handles no touches and takes no focus, so the original UI behaves as before.
     */
    private static final class CheckBadge extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tickPath = new Path();
        private final float density;
        private final int toolbarId;
        private View toolbar;

        CheckBadge(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            toolbarId = context.getResources().getIdentifier("toolbar", "id", context.getPackageName());
            setClickable(false);
            setLongClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setWillNotDraw(false);
            fill.setColor(0xFF1A73E8);
            ring.setColor(0xFFFFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2 * density);
            tick.setColor(0xFFFFFFFF);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(2.5f * density);
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeJoin(Paint.Join.ROUND);
        }

        private int lastState = Integer.MIN_VALUE;
        private boolean errorLogged;

        // The app hides the toolbar (GONE) on tap-to-hide and moves it with window insets: follow both.
        private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = () -> {
            int state = toolbar == null ? -1 : (toolbar.getVisibility() == VISIBLE ? 1 : 0)
                    + 2 * (toolbar.getBottom() + toolbar.getRight() - toolbar.getPaddingRight());
            if (state != lastState) {
                lastState = state;
                invalidate();
            }
        };

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        }

        @Override
        protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            try {
                if (toolbar == null && toolbarId != 0 && getParent() instanceof View) {
                    toolbar = ((View) getParent()).findViewById(toolbarId);
                }
                if (toolbar != null && toolbar.getVisibility() != VISIBLE) return;
                float radius = 13 * density;
                float top;
                float cx;
                if (toolbar != null) {
                    top = toolbar.getBottom();
                    cx = toolbar.getRight() - toolbar.getPaddingRight() - 12 * density - radius;
                } else {
                    // Toolbar not found: assume a standard bar below the status bar and the right system inset.
                    WindowInsets insets = getRootWindowInsets();
                    top = 56 * density + (insets != null ? insets.getSystemWindowInsetTop() : 0);
                    cx = getWidth() - (insets != null ? insets.getSystemWindowInsetRight() : 0) - 12 * density - radius;
                }
                float cy = top + 8 * density + radius;
                canvas.drawCircle(cx, cy, radius, fill);
                canvas.drawCircle(cx, cy, radius, ring);
                tickPath.reset();
                tickPath.moveTo(cx - radius * 0.42f, cy + radius * 0.02f);
                tickPath.lineTo(cx - radius * 0.1f, cy + radius * 0.36f);
                tickPath.lineTo(cx + radius * 0.46f, cy - radius * 0.32f);
                canvas.drawPath(tickPath, tick);
            } catch (Throwable t) {
                if (!errorLogged) {
                    errorLogged = true;
                    hookError(t);
                }
            }
        }
    }

    private static boolean japanese(Context context) {
        if (context == null) return false;
        return "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
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
}
