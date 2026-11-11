package app.filesplus.extension.preview;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;

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

    private PreviewHooks() {}

    public static void hookError(Throwable t) {
        DiagLog.e(AREA, "Preview hook failed", t);
    }

    /**
     * Called right after the app inflated its preview menu (never for entries where the app
     * returns before inflating). {@code hasId} is false when the file has no MediaStore id.
     */
    public static void onMenuCreated(View root, Menu menu, long id, boolean hasId, Object entry) {
        try {
            if (menu == null) return;
            Context context = root != null ? root.getContext() : ModKit.context();
            boolean ja = japanese(context);
            if (hasId && menu.findItem(SELECT_ITEM) == null) {
                String title = SelectionStore.isSelected(id)
                        ? (ja ? "選択を解除" : "Deselect") : (ja ? "選択" : "Select");
                menu.add(Menu.NONE, SELECT_ITEM, Menu.NONE, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            }
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
            updateOverlay(galleryRoot, id, hasId);
        } catch (Throwable t) { hookError(t); }
    }

    /**
     * Hands the collected entries to the caller (the list screen's selection model) and forgets them,
     * so nothing stale is left for the next preview visit. Empty when nothing was selected.
     */
    public static List<Object> takePendingForList() {
        try {
            return SelectionStore.drain();
        } catch (Throwable t) {
            hookError(t);
            return new ArrayList<>();
        }
    }

    /** Cheap pre-check so the bridge only reads the current file for our own items. */
    public static boolean handles(int itemId) {
        return itemId == SELECT_ITEM || itemId == COUNT_ITEM;
    }

    /** Returns true when the click was one of our items and has been handled. */
    public static boolean onMenuItem(View root, int itemId, long id, boolean hasId, Object entry) {
        try {
            if (!handles(itemId)) return false;
            Context context = root != null ? root.getContext() : ModKit.context();
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

    /**
     * Shows or hides the single check overlay on the gallery root. The pager recycles its pages, so
     * the mark lives on the root and follows the current file. It is created lazily, only when needed.
     */
    private static void updateOverlay(View root, long id, boolean hasId) {
        if (!(root instanceof ViewGroup)) return;
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
