package app.filesplus.extension.move;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Bottom-aligned plain Dialog that lists pinned and recent move destinations and opens the folder
 * browser. Built from code only (no app resources). Long-press on a history row toggles its pin.
 * Also holds the small UI helpers shared with {@link FolderBrowser}.
 */
public final class DestinationSheet {
    private static final String AREA = "move";

    private DestinationSheet() {}

    /**
     * Shows the sheet. {@code count} is the size of the preview selection; 0 means "the current file".
     * {@code onChosen} gets the destination folder; it is not called when the sheet is dismissed.
     */
    public static void show(Activity activity, int count, Consumer<File> onChosen) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || onChosen == null) return;
            new Sheet(activity, count, onChosen).show();
        } catch (Throwable t) {
            DiagLog.e(AREA, "Destination sheet failed", t);
        }
    }

    private static final class Sheet {
        private final Activity activity;
        private final Consumer<File> onChosen;
        private final Palette palette;
        private final boolean ja;
        private final LinearLayout list;
        private Dialog dialog;

        Sheet(Activity activity, int count, Consumer<File> onChosen) {
            this.activity = activity;
            this.onChosen = onChosen;
            this.palette = new Palette(activity);
            this.ja = japanese(activity);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(sheetBackground(activity, palette));
            root.setPadding(0, dp(activity, 12), 0, dp(activity, 8));

            root.addView(text(activity, ja ? "移動先を選ぶ" : "Send to…", 18, palette.text, true),
                    padded(activity, 20, 4, 20, 0));
            String subtitle = count > 0 ? (ja ? "選択中の " + count + " 件" : count + " selected files")
                    : (ja ? "このファイル" : "This file");
            root.addView(text(activity, subtitle, 13, palette.sub, false), padded(activity, 20, 0, 20, 8));

            MaxHeightScrollView scroll = new MaxHeightScrollView(activity,
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5f));
            list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(list, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            root.addView(divider(activity, palette), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            View choose = row(activity, palette, ja ? "フォルダを選ぶ…" : "Choose folder…", null, palette.accent);
            choose.setOnClickListener(v -> {
                try {
                    dismiss();
                    FolderBrowser.show(activity, onChosen);
                } catch (Throwable t) {
                    DiagLog.e(AREA, "Opening the folder browser failed", t);
                }
            });
            root.addView(choose);

            dialog = newBottomDialog(activity, root, 0, null);
            populate();
        }

        void show() {
            dialog.show();
        }

        private void dismiss() {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
                // Already gone.
            }
        }

        private void populate() {
            list.removeAllViews();
            List<String> all = DestinationHistory.ordered();
            Set<String> pinned = new HashSet<>(DestinationHistory.pinned());
            if (all.isEmpty()) {
                list.addView(text(activity, ja ? "まだ履歴はありません" : "No destinations yet", 14, palette.sub, false),
                        padded(activity, 20, 12, 20, 12));
                return;
            }
            for (String path : all) {
                File folder = new File(path);
                boolean isPinned = pinned.contains(path);
                String name = folder.getName().isEmpty() ? path : folder.getName();
                String parent = folder.getParent();
                View row = row(activity, palette, (isPinned ? "★ " : "") + name, parent == null ? "" : parent, palette.text);
                row.setOnClickListener(v -> {
                    try {
                        dismiss();
                        onChosen.accept(folder);
                    } catch (Throwable t) {
                        DiagLog.e(AREA, "Choosing a destination failed", t);
                    }
                });
                row.setOnLongClickListener(v -> {
                    try {
                        DestinationHistory.setPinned(path, !isPinned);
                        Toast.makeText(activity, isPinned ? (ja ? "ピン留めを外しました" : "Unpinned")
                                : (ja ? "ピン留めしました" : "Pinned"), Toast.LENGTH_SHORT).show();
                        populate();
                    } catch (Throwable t) {
                        DiagLog.e(AREA, "Toggling a pin failed", t);
                    }
                    return true;
                });
                list.addView(row);
            }
            list.addView(text(activity, ja ? "長押しでピン留め" : "Long-press to pin", 12, palette.sub, false),
                    padded(activity, 20, 8, 20, 8));
        }
    }

    // ---- helpers shared with FolderBrowser -------------------------------------------------------

    /** Colors picked from the Activity's night flag; no app styles involved. */
    static final class Palette {
        final int bg;
        final int text;
        final int sub;
        final int accent;
        final int divider;
        final int ripple;
        final boolean night;

        Palette(Context context) {
            night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            bg = night ? 0xFF2B2C2F : 0xFFFFFFFF;
            text = night ? 0xFFEDEDED : 0xFF202124;
            sub = night ? 0xFFA0A4A9 : 0xFF5F6368;
            accent = night ? 0xFF8AB4F8 : 0xFF1A73E8;
            divider = night ? 0x33FFFFFF : 0x1F000000;
            ripple = night ? 0x33FFFFFF : 0x1F000000;
        }
    }

    /** A ScrollView that never grows past a maximum height. */
    static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeight;

        MaxHeightScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }

    static boolean japanese(Context context) {
        return "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    static Drawable sheetBackground(Context context, Palette palette) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(palette.bg);
        float r = dp(context, 20);
        shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return shape;
    }

    static TextView text(Context context, String value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    static LinearLayout.LayoutParams padded(Context context, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom));
        return params;
    }

    static View divider(Context context, Palette palette) {
        View view = new View(context);
        view.setBackgroundColor(palette.divider);
        return view;
    }

    static Drawable pressFeedback(Palette palette) {
        return new RippleDrawable(ColorStateList.valueOf(palette.ripple), null, new ColorDrawable(Color.WHITE));
    }

    /** A tappable two-line row; {@code secondary} may be null or empty. */
    static View row(Context context, Palette palette, String primary, String secondary, int primaryColor) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 48));
        row.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8));
        row.setBackground(pressFeedback(palette));
        TextView first = text(context, "", 16, primaryColor, false);
        first.setSingleLine(true);
        first.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(first);
        TextView second = text(context, "", 12, palette.sub, false);
        second.setSingleLine(true);
        second.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(second);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bindRow(row, primary, secondary, primaryColor);
        return row;
    }

    /** Fills a view made by {@link #row}; used to recycle rows in a ListView. */
    static void bindRow(View row, String primary, String secondary, int primaryColor) {
        ViewGroup group = (ViewGroup) row;
        TextView first = (TextView) group.getChildAt(0);
        TextView second = (TextView) group.getChildAt(1);
        first.setText(primary);
        first.setTextColor(primaryColor);
        boolean hasSecond = secondary != null && !secondary.isEmpty();
        second.setText(hasSecond ? secondary : "");
        second.setVisibility(hasSecond ? View.VISIBLE : View.GONE);
    }

    private static final List<WeakReference<Dialog>> OPEN = new ArrayList<>();

    /** Remembers a dialog (weakly) so it can be dismissed when its Activity goes away. */
    static void track(Dialog dialog) {
        synchronized (OPEN) {
            for (Iterator<WeakReference<Dialog>> it = OPEN.iterator(); it.hasNext(); ) {
                if (it.next().get() == null) it.remove();
            }
            OPEN.add(new WeakReference<>(dialog));
        }
    }

    /** Dismisses every sheet, browser and prompt owned by the Activity (avoids leaked windows). */
    public static void dismissFor(Activity activity) {
        try {
            List<Dialog> mine = new ArrayList<>();
            synchronized (OPEN) {
                for (Iterator<WeakReference<Dialog>> it = OPEN.iterator(); it.hasNext(); ) {
                    Dialog dialog = it.next().get();
                    if (dialog == null) {
                        it.remove();
                    } else if (dialog.getOwnerActivity() == activity) {
                        mine.add(dialog);
                        it.remove();
                    }
                }
            }
            for (Dialog dialog : mine) {
                try {
                    if (dialog.isShowing()) dialog.dismiss();
                } catch (Throwable ignored) {
                    // Already gone.
                }
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Dismissing dialogs failed", t);
        }
    }

    /**
     * Plain framework Dialog glued to the bottom edge; {@code height} 0 = wrap content.
     * {@code backHandler} (may be null) returns true when it consumed the back key.
     */
    static Dialog newBottomDialog(Activity activity, View content, int height, BooleanSupplier backHandler) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar) {
            @Override
            public void onBackPressed() {
                try {
                    if (backHandler != null && backHandler.getAsBoolean()) return;
                } catch (Throwable t) {
                    DiagLog.e(AREA, "Back handling failed", t);
                }
                super.onBackPressed();
            }
        };
        dialog.setOwnerActivity(activity);
        track(dialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    height > 0 ? height : ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }
}
