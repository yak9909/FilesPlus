package app.filesplus.extension.browse;

import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;
import app.filesplus.extension.DiagLog;
import java.util.WeakHashMap;

/** Keeps folder names readable when the native storage grid tile has little horizontal space. */
public final class StorageGridFolderLabels {
    // Binding and measurement both run on the UI thread. The values do not retain the views.
    private static final WeakHashMap<Object, Boolean> storageBinders = new WeakHashMap<>();
    private static final WeakHashMap<View, CellLayout> cells = new WeakHashMap<>();

    private StorageGridFolderLabels() {}

    public static void registerBinder(Object binder) {
        try {
            storageBinders.put(binder, Boolean.TRUE);
        } catch (Throwable error) {
            log(error);
        }
    }

    public static void bind(Object binder, View cell, int imageId, int selectionId, int titleId, int lockId) {
        try {
            if (!storageBinders.containsKey(binder) || cells.containsKey(cell)) return;
            cells.put(cell, new CellLayout(cell, imageId, selectionId, titleId, lockId));
        } catch (Throwable error) {
            log(error);
        }
    }

    public static void beforeMeasure(View cell, int widthSpec) {
        CellLayout layout = null;
        try {
            layout = cells.get(cell);
            if (layout == null) return;
            float density = cell.getResources().getDisplayMetrics().density;
            boolean narrow = View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.EXACTLY
                    && needsStack(View.MeasureSpec.getSize(widthSpec), layout.horizontalOverhead(cell), density);
            if (narrow == layout.narrow) return;
            if (narrow) {
                layout.stack(cell, density);
            } else {
                layout.restore(cell);
            }
        } catch (Throwable error) {
            recover(cell, layout, error);
        }
    }

    static boolean needsStack(int cellWidth, int horizontalOverhead, float density) {
        return cellWidth > 0 && cellWidth - horizontalOverhead < 40 * density;
    }

    private static void recover(View cell, CellLayout layout, Throwable error) {
        log(error);
        try {
            if (layout != null && layout.narrow) layout.restore(cell);
            cells.remove(cell);
        } catch (Throwable restoreError) {
            log(restoreError);
        }
    }

    private static void log(Throwable error) {
        try {
            DiagLog.e("gridColumns", "Storage grid folder label hook failed", error);
        } catch (Throwable ignored) {
            // Diagnostics must not interfere with native binding or measurement.
        }
    }

    private static final class CellLayout {
        final int imageId;
        final int selectionId;
        final int titleId;
        final int lockId;
        final RelativeLayout.LayoutParams imageParams;
        final RelativeLayout.LayoutParams selectionParams;
        final RelativeLayout.LayoutParams titleParams;
        final RelativeLayout.LayoutParams lockParams;
        final int titleGravity;
        final int titleHyphenation;
        final int titleBreakStrategy;
        boolean narrow;

        CellLayout(View cell, int imageId, int selectionId, int titleId, int lockId) {
            this.imageId = imageId;
            this.selectionId = selectionId;
            this.titleId = titleId;
            this.lockId = lockId;
            imageParams = originalParams(cell, imageId);
            selectionParams = originalParams(cell, selectionId);
            titleParams = originalParams(cell, titleId);
            lockParams = originalParams(cell, lockId);
            TextView title = title(cell);
            titleGravity = title.getGravity();
            titleHyphenation = title.getHyphenationFrequency();
            titleBreakStrategy = title.getBreakStrategy();
        }

        TextView title(View cell) {
            return (TextView) cell.findViewById(titleId);
        }

        int horizontalOverhead(View cell) {
            TextView title = title(cell);
            return cell.getPaddingLeft() + cell.getPaddingRight()
                    + imageParams.getMarginStart() + imageParams.width + imageParams.getMarginEnd()
                    + titleParams.getMarginStart() + titleParams.getMarginEnd()
                    + title.getCompoundPaddingLeft() + title.getCompoundPaddingRight();
        }

        void stack(View cell, float density) {
            int padding = Math.round(4 * density);
            int sideMargin = Math.round(2 * density);
            View image = cell.findViewById(imageId);
            View selection = cell.findViewById(selectionId);
            TextView title = title(cell);
            View lock = cell.findViewById(lockId);

            RelativeLayout.LayoutParams icon = topCentered(imageParams, padding);
            RelativeLayout.LayoutParams check = topCentered(selectionParams,
                    padding + (imageParams.height - selectionParams.height) / 2);
            RelativeLayout.LayoutParams label = new RelativeLayout.LayoutParams(titleParams.width, titleParams.height);
            label.addRule(RelativeLayout.BELOW, imageId);
            label.addRule(RelativeLayout.ALIGN_PARENT_START);
            label.addRule(RelativeLayout.ALIGN_PARENT_END);
            label.setMarginStart(sideMargin);
            label.setMarginEnd(sideMargin);
            label.topMargin = sideMargin;
            label.bottomMargin = padding;

            RelativeLayout.LayoutParams overlay = new RelativeLayout.LayoutParams(lockParams);
            overlay.addRule(RelativeLayout.ALIGN_BOTTOM, titleId);

            // RelativeLayout measures next. The existing image, selection, title, and lock order
            // already satisfies the new dependencies: the title follows the image and the lock follows the title.
            narrow = true;
            image.setLayoutParams(icon);
            selection.setLayoutParams(check);
            title.setLayoutParams(label);
            title.setGravity(Gravity.CENTER_HORIZONTAL | (titleGravity & Gravity.VERTICAL_GRAVITY_MASK));
            title.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL);
            title.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
            lock.setLayoutParams(overlay);
        }

        void restore(View cell) {
            cell.findViewById(imageId).setLayoutParams(imageParams);
            cell.findViewById(selectionId).setLayoutParams(selectionParams);
            TextView title = title(cell);
            title.setLayoutParams(titleParams);
            title.setGravity(titleGravity);
            title.setHyphenationFrequency(titleHyphenation);
            title.setBreakStrategy(titleBreakStrategy);
            cell.findViewById(lockId).setLayoutParams(lockParams);
            narrow = false;
        }

        private static RelativeLayout.LayoutParams originalParams(View cell, int id) {
            return (RelativeLayout.LayoutParams) cell.findViewById(id).getLayoutParams();
        }

        private static RelativeLayout.LayoutParams topCentered(RelativeLayout.LayoutParams original, int top) {
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(original.width, original.height);
            params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            params.addRule(RelativeLayout.CENTER_HORIZONTAL);
            params.topMargin = top;
            return params;
        }
    }
}
