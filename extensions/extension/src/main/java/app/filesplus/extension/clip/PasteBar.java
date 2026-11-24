package app.filesplus.extension.clip;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/** A native picker button area with reversible browser spacing. */
final class PasteBar implements ViewTreeObserver.OnGlobalLayoutListener {
    private final ViewGroup coordinator;
    private final View area;
    private final TextView paste;
    private final TextView clear;
    private final int listId;
    private final View fab;
    private final Map<View, Offset> lists = new WeakHashMap<>();
    private final Offset fabOffset = new Offset();
    private ViewTreeObserver observer;
    private int inset;

    PasteBar(View root, boolean media, boolean expressive, Runnable onPaste, Runnable onClear) {
        coordinator = (ViewGroup) find(root, "main_coordinator");
        if (coordinator == null) throw new IllegalStateException("Missing browser coordinator");
        Context context = coordinator.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        if (expressive) {
            View picker = inflater.inflate(id(context, "layout", "advanced_browsing_picker_fragment"), coordinator, false);
            area = find(picker, "button_section");
            clear = (TextView) find(area, "new_folder_action");
            paste = (TextView) find(area, "action_button");
            if (clear == null || paste == null) throw new IllegalStateException("Missing picker buttons");
            ((ViewGroup) area.getParent()).removeView(area);
            clear.setId(View.generateViewId());
            paste.setId(View.generateViewId());
            clear.setText(PasteUi.japanese(context) ? "クリア" : "Clear");
            if (!ClipHooks.setPasteIcon(clear, null)) throw new IllegalStateException("Cannot clear picker icon");
            clear.setOnClickListener(v -> onClear.run());
        } else {
            area = inflater.inflate(id(context, "layout", "framed_single_button_view"), coordinator, false);
            paste = (TextView) find(area, "single_action_button");
            if (paste == null) throw new IllegalStateException("Missing framed picker button");
            int done = id(context, "drawable", "quantum_gm_ic_done_vd_theme_24");
            if (done == 0 || !ClipHooks.setPasteIcon(paste, context.getDrawable(done))) {
                throw new IllegalStateException("Cannot set framed picker icon");
            }
            clear = null;
        }
        listId = id(context, "id", media ? "file_list" : "content_list");
        if (listId == 0 || (!media && coordinator.findViewById(listId) == null)) {
            throw new IllegalStateException("Missing browser list");
        }
        fab = find(root, expressive ? "create_new_folder_expressive" : "create_new_folder");
        paste.setOnClickListener(v -> onPaste.run());
        area.setVisibility(View.VISIBLE);
        int originalBottom = area.getPaddingBottom();
        area.setOnApplyWindowInsetsListener((view, insets) -> {
            try {
                inset = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).bottom;
                view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop(),
                        view.getPaddingEnd(), originalBottom + inset);
            } catch (Throwable t) {
                ClipHooks.hookError(t);
                remove();
            }
            return insets;
        });
        try {
            coordinator.addView(area);
            observer = coordinator.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
            area.requestApplyInsets();
        } catch (Throwable t) {
            remove();
            throw t;
        }
    }

    void update(String label, boolean enabled, boolean refusal, boolean busy) {
        paste.setMaxLines(refusal ? 3 : 1);
        paste.setText(label);
        paste.setEnabled(enabled);
        if (clear != null) clear.setEnabled(!busy);
    }

    boolean shown() { return area.getParent() == coordinator; }

    @Override public void onGlobalLayout() {
        try {
            int height = Math.max(0, area.getHeight() - inset);
            for (var iterator = lists.entrySet().iterator(); iterator.hasNext();) {
                var entry = iterator.next();
                View view = entry.getKey();
                if (view != null && !contains(view)) {
                    entry.getValue().padding(view, 0);
                    iterator.remove();
                }
            }
            padLists(coordinator, height);
            if (fab != null) fabOffset.margin(fab, height);
        } catch (Throwable t) {
            ClipHooks.hookError(t);
            remove();
        }
    }

    private boolean contains(View view) {
        for (View current = view; current != null;) {
            if (current == coordinator) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private void padLists(View view, int height) {
        if (view.getId() == listId) {
            lists.computeIfAbsent(view, ignored -> new Offset()).padding(view, height);
        } else if (view instanceof ViewGroup && view != area) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) padLists(group.getChildAt(i), height);
        }
    }

    void remove() {
        if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
        observer = null;
        if (shown()) coordinator.removeView(area);
        for (Map.Entry<View, Offset> entry : lists.entrySet()) {
            View view = entry.getKey();
            if (view != null) entry.getValue().padding(view, 0);
        }
        lists.clear();
        if (fab != null) fabOffset.margin(fab, 0);
    }

    /** Preserve the native inset/layout updates made while the bar is visible. */
    private static final class Offset {
        private int applied;
        private int nativeValue;
        private boolean initialized;

        private int next(int current, int extra) {
            if (!initialized || current != applied) nativeValue = current;
            initialized = true;
            applied = nativeValue + extra;
            return applied;
        }

        void padding(View view, int extra) {
            int bottom = next(view.getPaddingBottom(), extra);
            if (bottom != view.getPaddingBottom()) {
                view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop(), view.getPaddingEnd(), bottom);
            }
        }

        void margin(View view, int extra) {
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            int bottom = next(params.bottomMargin, extra);
            if (bottom != params.bottomMargin) {
                params.bottomMargin = bottom;
                view.setLayoutParams(params);
            }
        }
    }

    static int id(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    private static View find(View root, String name) {
        if (root == null) return null;
        int id = id(root.getContext(), "id", name);
        return id == 0 ? null : root.findViewById(id);
    }
}
