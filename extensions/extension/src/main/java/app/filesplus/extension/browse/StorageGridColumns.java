package app.filesplus.extension.browse;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.View;
import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModSettings;

/** Column arithmetic for the app's advanced storage browser. */
public final class StorageGridColumns {
    static final int SPANS = 60;
    public static final int ORIGINAL = 0;
    static final int FULL_WIDTH = 0;
    static final int FOLDER = 1;
    static final int FILE = 2;
    static final int SECTION_SPACER = 3;

    private StorageGridColumns() {}

    public static boolean installed() { return false; } // Replaced by the patch.

    public static int choice(int columns) {
        return columns >= 1 && columns <= 5 ? columns : ORIGINAL;
    }

    public static final class Plan {
        final int folders;
        final int files;

        private Plan(int folders, int files) {
            this.folders = folders;
            this.files = files;
        }
    }

    static Plan resolve(int original, int folders, int files) {
        folders = choice(folders);
        files = choice(files);
        if (folders == ORIGINAL && files == ORIGINAL) return null;
        if (original < 1 || ((folders == ORIGINAL || files == ORIGINAL) && SPANS % original != 0)) return null;
        return new Plan(folders == ORIGINAL ? original : folders, files == ORIGINAL ? original : files);
    }

    public static Plan plan(Object mode, int original) {
        try {
            if (!(mode instanceof Enum<?>) || !"GRID_MODE".equals(((Enum<?>) mode).name())) return null;
            SharedPreferences prefs = ModSettings.prefs();
            if (prefs == null) return null;
            return resolve(original, prefs.getInt(ModSettings.GRID_STORAGE_FOLDER_COLUMNS, ORIGINAL),
                    prefs.getInt(ModSettings.GRID_STORAGE_FILE_COLUMNS, ORIGINAL));
        } catch (Throwable error) {
            hookError(error);
            return null;
        }
    }

    public static boolean changed(Plan previous, Plan next) {
        try {
            if (previous == next) return false;
            return previous == null || next == null || previous.folders != next.folders || previous.files != next.files;
        } catch (Throwable error) {
            hookError(error);
            return false;
        }
    }

    public static int span(Plan plan, int kind, int position) {
        try {
            if (plan == null) return 0;
            switch (kind) {
                case FOLDER: return SPANS / plan.folders;
                case FILE: return SPANS / plan.files;
                case SECTION_SPACER: return SPANS - (position % plan.folders) * (SPANS / plan.folders);
                default: return SPANS;
            }
        } catch (Throwable error) {
            hookError(error);
            return 0;
        }
    }

    /** The native adapter has one model for the spacer, so it adds one to file offsets. */
    public static int gap(Plan plan, int folders, int nativeRemainder) {
        try {
            return plan == null ? nativeRemainder : (folders % plan.folders == 0 ? 0 : 1);
        } catch (Throwable error) {
            hookError(error);
            return nativeRemainder;
        }
    }

    public static int phase(Plan plan, int offset, int folders, int nativeRemainder) {
        try {
            if (plan == null) return nativeRemainder;
            if (offset < folders) return offset % plan.folders;
            // Keep the spacer when a folder window is trimmed up to the section boundary.
            if (offset == folders) return 0;
            return (offset - folders - gap(plan, folders, 0)) % plan.files;
        } catch (Throwable error) {
            hookError(error);
            return nativeRemainder;
        }
    }

    public static boolean includeSpacer(Plan plan, int offset, int loadedFolders, int folders) {
        try {
            return plan == null || loadedFolders >= folders - offset;
        } catch (Throwable error) {
            hookError(error);
            return true;
        }
    }

    public static boolean decorate(Rect out, View child, int spacing, int spanIndex, int spanSize,
            boolean expressive, boolean tablet) {
        try {
            // The browser's spacer binder creates a plain, empty View.
            if (child.getClass() == View.class) {
                out.set(0, 0, 0, 0);
                return true;
            }
            if (spanSize < 1 || SPANS % spanSize != 0 || spanIndex < 0 || spanIndex % spanSize != 0) return false;
            int columns = SPANS / spanSize;
            int column = spanIndex / spanSize;
            if (expressive || tablet) {
                int width = Math.round((1f + 1f / columns) * spacing);
                int right = Math.round(((float) (column + 1) / columns) * spacing);
                int top = expressive ? spacing / 2 : 0;
                out.set(width - right, top, right, expressive ? top : spacing);
            } else {
                int width = Math.round((1f - 1f / columns) * spacing);
                int left = Math.round(((float) column / columns) * spacing);
                out.set(left, 0, width - left, spacing);
            }
            return true;
        } catch (Throwable error) {
            hookError(error);
            return false;
        }
    }

    public static void hookError(Throwable error) {
        DiagLog.e("gridColumns", "Storage grid columns hook failed", error);
    }
}
