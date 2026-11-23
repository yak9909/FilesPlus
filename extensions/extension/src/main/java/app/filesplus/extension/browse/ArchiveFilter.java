package app.filesplus.extension.browse;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.settings.SettingsHooks;

/** Uses the Downloads pager's native collection IDs to give each tab its own chip state. */
public final class ArchiveFilter {
    private static final String[] SUFFIXES = {
            ".zip", ".rar", ".7z", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".zst", ".lz4"
    };
    private static final Object CHIP_TAG = new Object();
    private static final Map<View, WeakReference<State>> PAGERS = new WeakHashMap<>();

    private ArchiveFilter() {}

    public static boolean installed() {
        return false; // Replaced when the patch is applied.
    }

    public static String[] suffixes() {
        return SUFFIXES.clone();
    }

    public static boolean matchesArchive(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String suffix = name.substring(dot).toLowerCase(Locale.ROOT);
        for (String archive : SUFFIXES) {
            if (archive.equals(suffix)) return true;
        }
        return false;
    }

    // Implemented by the native tab-list observer using its latest unfiltered tab catalogue.
    public interface Reload {
        boolean modKitReloadArchives();
    }

    /** Only used on the UI thread, by the catalogue observer and its tabs. */
    public static final class State {
        private final Reload reload;
        private final Map<String, String> queries = new HashMap<>();

        private State(Reload reload) {
            this.reload = reload;
        }

        public boolean hasRequests() {
            return !queries.isEmpty();
        }

        public boolean isRequested(String key) {
            return queries.containsKey(key);
        }

        public void recordVariant(String original, String variant) {
            queries.put(original, variant);
        }

        public void clear() {
            queries.clear();
        }

        private String originalKey(String query) {
            for (Map.Entry<String, String> entry : queries.entrySet()) {
                if (entry.getValue().equals(query)) return entry.getKey();
            }
            return query;
        }

        private void toggle(String original) {
            Map<String, String> before = new HashMap<>(queries);
            boolean applied = false;
            try {
                if (isRequested(original)) queries.remove(original);
                else queries.put(original, original);
                applied = reload.modKitReloadArchives();
            } finally {
                if (!applied) {
                    queries.clear();
                    queries.putAll(before);
                }
            }
        }
    }

    public static State bind(View pager, Reload reload, State state) {
        try {
            if (pager == null) return null;
            if (state == null) state = new State(reload);
            PAGERS.put(pager, new WeakReference<>(state));
            return state;
        } catch (Throwable failure) {
            hookError(failure);
            return null;
        }
    }

    public static void attach(View root, View pager, String query, int selectionCount,
                              int containerId, int groupId, int layoutId) {
        try {
            if (root == null || query == null || query.isEmpty()) return;
            WeakReference<State> binding = PAGERS.get(pager);
            State state = binding == null ? null : binding.get();
            if (state == null || root.findViewWithTag(CHIP_TAG) != null) return;

            View container = root.findViewById(containerId);
            ViewGroup group = root.findViewById(groupId);
            if (container == null || group == null || group.getChildCount() != 0) return;
            View chip = LayoutInflater.from(root.getContext()).inflate(layoutId, group, false);
            Checkable checked = (Checkable) chip;
            String original = state.originalKey(query);
            String label = SettingsHooks.isJapanese(root.getContext()) ? "圧縮ファイル" : "Archives";
            ((TextView) chip).setText(label);
            chip.setContentDescription(label);
            chip.setTag(CHIP_TAG);
            checked.setChecked(state.isRequested(original));
            chip.setEnabled(selectionCount == 0);
            chip.setOnClickListener(view -> {
                try {
                    if (view.isAttachedToWindow() && view.isEnabled()) state.toggle(original);
                    checked.setChecked(state.isRequested(original));
                } catch (Throwable failure) {
                    hookError(failure);
                }
            });

            int oldVisibility = container.getVisibility();
            try {
                group.addView(chip);
                container.setVisibility(View.VISIBLE);
            } catch (Throwable failure) {
                group.removeView(chip);
                container.setVisibility(oldVisibility);
                throw failure;
            }
        } catch (Throwable failure) {
            hookError(failure);
        }
    }

    public static void selectionChanged(View root, int count) {
        try {
            View chip = root == null ? null : root.findViewWithTag(CHIP_TAG);
            if (chip != null) chip.setEnabled(count == 0);
        } catch (Throwable failure) {
            hookError(failure);
        }
    }

    public static void hookError(Throwable failure) {
        try {
            DiagLog.e("archives", "Archives filter hook failed", failure);
        } catch (Throwable ignored) {
            // Logging must not interfere with the app's fallback.
        }
    }
}
