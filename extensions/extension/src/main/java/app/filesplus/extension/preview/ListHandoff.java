package app.filesplus.extension.preview;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.move.DestinationSheet;
import app.filesplus.extension.move.MoveQueue;

/**
 * Hands the files collected in the preview over to the list screen's selection model.
 *
 * <p>The app creates one selection model per Activity. Each model registers itself from its
 * constructor (weak references only). When an Activity resumes while the preview is closing, the
 * store is drained into the models owned by that Activity. A model's owner is the Activity that
 * was current when it was constructed.
 *
 * <p>Everything runs on the main thread (lifecycle callbacks and the app's constructors).
 */
public final class ListHandoff implements Application.ActivityLifecycleCallbacks {
    private static final String AREA = "preview";

    private static final class Registered {
        final WeakReference<Object> model;
        WeakReference<Activity> owner;

        Registered(Object model, Activity owner) {
            this.model = new WeakReference<>(model);
            this.owner = owner == null ? null : new WeakReference<>(owner);
        }
    }

    private static final List<Registered> MODELS = new ArrayList<>();
    private static boolean installed;
    private static WeakReference<Activity> current;
    /** The preview instance we last saw (weak: only for the finishing check) and its class (kept). */
    private static WeakReference<Activity> preview;
    private static Class<?> previewClass;

    private ListHandoff() {}

    /** Called at the end of the selection model's constructor. */
    public static void registerModel(Object model) {
        try {
            if (!(model instanceof SelectionTarget)) return;
            install();
            synchronized (MODELS) {
                for (Iterator<Registered> it = MODELS.iterator(); it.hasNext(); ) {
                    Object existing = it.next().model.get();
                    if (existing == null || existing == model) it.remove();
                }
                // Unknown owner (created before the callbacks were registered) is adopted on the next resume.
                MODELS.add(new Registered(model, current == null ? null : current.get()));
            }
            // A list recreated while the preview was open builds its model after it resumed: retry then.
            if (SelectionStore.count() > 0 && previewClosing()) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        Activity activity = current == null ? null : current.get();
                        if (activity != null && SelectionStore.count() > 0 && !isPreview(activity)) handOver(activity);
                    } catch (Throwable t) { PreviewHooks.hookError(t); }
                });
            }
        } catch (Throwable t) {
            PreviewHooks.hookError(t);
        }
    }

    /** Remembers the Activity that hosts the gallery fragment; found from a view, never by name. */
    static void notePreviewActivity(Activity activity) {
        if (activity == null) return;
        previewClass = activity.getClass();
        preview = new WeakReference<>(activity);
        // A preview opened from outside has no list model, yet the move queue still needs the close callbacks.
        install();
    }

    /** True when the preview instance we know of is finishing or gone (back / up pressed). */
    static boolean previewClosing() {
        Activity known = preview == null ? null : preview.get();
        return known == null || known.isFinishing() || known.isDestroyed();
    }

    private static synchronized void install() {
        if (installed) return;
        Context context = ModKit.context();
        if (!(context instanceof Application)) return;
        installed = true;
        ((Application) context).registerActivityLifecycleCallbacks(new ListHandoff());
        DiagLog.i(AREA, "List hand-off installed");
    }

    /** By class, which is kept even when the instance is gone (a recreated preview resumes before its menu). */
    private static boolean isPreview(Activity activity) {
        return previewClass != null && previewClass == activity.getClass();
    }

    @Override
    public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
        try {
            current = new WeakReference<>(activity);
            // A recreated preview (rotation) becomes the known instance at once, before its menu is built.
            if (isPreview(activity)) preview = new WeakReference<>(activity);
        } catch (Throwable t) { PreviewHooks.hookError(t); }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        try {
            current = new WeakReference<>(activity);
            adoptUnowned(activity);
            if (SelectionStore.count() == 0 || isPreview(activity)) return;
            handOver(activity);
        } catch (Throwable t) { PreviewHooks.hookError(t); }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        try {
            DestinationSheet.dismissFor(activity);
        } catch (Throwable t) { PreviewHooks.hookError(t); }
        try {
            // Anything not handed over by the time the preview closes (e.g. it was opened from outside) is dropped.
            // Only the instance we last saw counts: an older preview going away must not clear a newer one's picks.
            Activity known = preview == null ? null : preview.get();
            if (activity == known && activity.isFinishing() && SelectionStore.count() > 0) {
                DiagLog.i(AREA, "Preview closed, dropping " + SelectionStore.count() + " unclaimed selected files");
                SelectionStore.clear();
            }
            if (activity == known) {
                if (activity.isFinishing()) {
                    MoveQueue.onPreviewClosed();
                } else {
                    // Destroyed without finishing: a rotation recreates the preview at once (and marks the new
                    // instance as known), anything else means it is really gone. Decide after a short wait.
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            if (previewClosing()) MoveQueue.onPreviewClosed();
                        } catch (Throwable t) { PreviewHooks.hookError(t); }
                    }, 2000);
                }
            }
        } catch (Throwable t) { PreviewHooks.hookError(t); }
    }

    /** Whether the MediaStore row still exists (trashed rows are hidden from plain queries). Unsure counts as yes. */
    private static boolean exists(long id) {
        try {
            Context context = ModKit.context();
            if (context == null) return true;
            try (Cursor cursor = context.getContentResolver().query(
                    MediaStore.Files.getContentUri("external", id), new String[] {MediaStore.MediaColumns._ID}, null, null, null)) {
                return cursor == null || cursor.getCount() > 0;
            }
        } catch (Throwable t) {
            return true;
        }
    }

    private static void adoptUnowned(Activity activity) {
        synchronized (MODELS) {
            for (Registered registered : MODELS) {
                if (registered.owner == null) registered.owner = new WeakReference<>(activity);
            }
        }
    }

    private static void handOver(Activity activity) {
        // We came back from the preview only when it is closing (back / up pressed: finish() runs before the
        // previous Activity resumes). An Activity opened on top of a live preview (file info ...) is not a target.
        if (!previewClosing()) return;

        List<SelectionTarget> targets = new ArrayList<>();
        synchronized (MODELS) {
            for (Iterator<Registered> it = MODELS.iterator(); it.hasNext(); ) {
                Registered registered = it.next();
                Object model = registered.model.get();
                if (model == null) {
                    it.remove();
                    continue;
                }
                Activity owner = registered.owner == null ? null : registered.owner.get();
                if (owner == activity && model instanceof SelectionTarget) targets.add((SelectionTarget) model);
            }
        }
        if (targets.isEmpty()) return;

        // Files deleted or trashed while previewing no longer exist in MediaStore: leave them out.
        List<Object> entries = SelectionStore.drain(ListHandoff::exists);
        if (entries.isEmpty()) return;
        int delivered = 0;
        for (SelectionTarget target : targets) {
            try {
                target.modKitAddSelected(new ArrayList<>(entries));
                delivered++;
            } catch (Throwable t) { PreviewHooks.hookError(t); }
        }
        DiagLog.i(AREA, "Handed " + entries.size() + " selected files to " + delivered + " list model(s)");
        PreviewHooks.onStoreDrained();
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
}
