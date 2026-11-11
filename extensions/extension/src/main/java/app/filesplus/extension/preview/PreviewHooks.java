package app.filesplus.extension.preview;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

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
        } catch (Throwable t) { hookError(t); }
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
            Activity activity = activity(context);
            if (activity != null) activity.invalidateOptionsMenu();
            return true;
        } catch (Throwable t) {
            hookError(t);
            return false;
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
