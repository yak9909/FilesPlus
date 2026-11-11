package app.filesplus.extension.move;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.foldertabs.TabMove;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background move queue that outlives the preview Activity. Files are moved one job per id on a single
 * thread. Audio/video that is still shown on the current preview page is held until the user leaves
 * that page or the preview closes. Holds only the application context, never an Activity or View.
 */
public final class MoveQueue {
    private static final String AREA = "move";
    private static final Object LOCK = new Object();
    private static final long NO_PAGE = Long.MIN_VALUE;

    private static ExecutorService executor;
    private static long currentId = NO_PAGE;
    private static final List<Job> deferred = new ArrayList<>();

    private MoveQueue() {}

    /** One enqueue call: collects the results of its jobs and reports them once. */
    private static final class Batch {
        final Context app;
        final File destination;
        int pending;
        int moved;
        int skipped;
        int failed;

        Batch(Context app, File destination, int pending) {
            this.app = app;
            this.destination = destination;
            this.pending = pending;
        }
    }

    private static final class Job {
        final Batch batch;
        final long id;
        final String mime;

        Job(Batch batch, long id, String mime) {
            this.batch = batch;
            this.id = id;
            this.mime = mime;
        }
    }

    /** Queues one move job per id. Call {@link #onPageChanged} with the current id first. */
    public static void enqueue(Context app, long[] ids, String[] mimes, File destination) {
        try {
            Context context = app != null ? app.getApplicationContext() : null;
            if (context == null) context = app != null ? app : ModKit.context();
            if (context == null || ids == null || ids.length == 0 || destination == null) return;
            Batch batch = new Batch(context, destination, ids.length);
            List<Job> now = new ArrayList<>();
            synchronized (LOCK) {
                for (int i = 0; i < ids.length; i++) {
                    String mime = mimes != null && i < mimes.length ? mimes[i] : null;
                    Job job = new Job(batch, ids[i], mime);
                    if (isDeferrable(job) && job.id == currentId) deferred.add(job);
                    else now.add(job);
                }
            }
            DiagLog.i(AREA, "Enqueued " + ids.length + " item(s) to " + destination);
            for (Job job : now) submit(job);
        } catch (Throwable t) {
            DiagLog.e(AREA, "enqueue failed", t);
        }
    }

    /** The preview page changed: release held jobs whose file is no longer the current page. */
    public static void onPageChanged(long currentId) {
        try {
            List<Job> release = new ArrayList<>();
            synchronized (LOCK) {
                MoveQueue.currentId = currentId;
                for (Iterator<Job> it = deferred.iterator(); it.hasNext(); ) {
                    Job job = it.next();
                    if (job.id != currentId) {
                        release.add(job);
                        it.remove();
                    }
                }
            }
            for (Job job : release) submit(job);
        } catch (Throwable t) {
            DiagLog.e(AREA, "onPageChanged failed", t);
        }
    }

    /** The preview closed: release every held job. */
    public static void onPreviewClosed() {
        try {
            List<Job> release;
            synchronized (LOCK) {
                currentId = NO_PAGE;
                release = new ArrayList<>(deferred);
                deferred.clear();
            }
            for (Job job : release) submit(job);
        } catch (Throwable t) {
            DiagLog.e(AREA, "onPreviewClosed failed", t);
        }
    }

    private static boolean isDeferrable(Job job) {
        String mime = job.mime;
        return mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"));
    }

    private static void submit(Job job) {
        try {
            executor().execute(() -> run(job));
        } catch (Throwable t) {
            DiagLog.e(AREA, "submit failed", t);
            finish(job, 0, 0, 1);
        }
    }

    private static ExecutorService executor() {
        synchronized (LOCK) {
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "filesplus-move-queue"));
            }
            return executor;
        }
    }

    private static void run(Job job) {
        int moved = 0, skipped = 0, failed = 0;
        try {
            File destination = job.batch.destination;
            if (!destination.isDirectory() || !destination.canWrite()) {
                DiagLog.e(AREA, "Destination unusable: " + destination, null);
                failed = 1;
            } else {
                List<String> paths = TabMove.pathsOf(job.batch.app, new long[]{job.id});
                if (paths.isEmpty()) {
                    DiagLog.e(AREA, "No path for id " + job.id, null);
                    failed = 1;
                } else {
                    TabMove.Result result = TabMove.move(job.batch.app, paths, destination);
                    moved = result.moved;
                    skipped = result.skipped;
                    failed = result.failed;
                }
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Job failed for id " + job.id, t);
            failed = 1;
        }
        finish(job, moved, skipped, failed);
    }

    private static void finish(Job job, int moved, int skipped, int failed) {
        try {
            Batch batch = job.batch;
            boolean done;
            int totalMoved, totalSkipped, totalFailed;
            synchronized (batch) {
                batch.moved += moved;
                batch.skipped += skipped;
                batch.failed += failed;
                batch.pending--;
                done = batch.pending == 0;
                totalMoved = batch.moved;
                totalSkipped = batch.skipped;
                totalFailed = batch.failed;
            }
            if (!done) return;
            if (totalMoved > 0) DestinationHistory.record(batch.destination.getPath());
            toast(batch.app, message(batch.destination, totalMoved, totalSkipped, totalFailed));
        } catch (Throwable t) {
            DiagLog.e(AREA, "finish failed", t);
        }
    }

    private static String message(File destination, int moved, int skipped, int failed) {
        boolean ja = "ja".equals(Locale.getDefault().getLanguage());
        String folder = destination.getName().isEmpty() ? destination.getPath() : destination.getName();
        StringBuilder sb = new StringBuilder();
        if (ja) {
            sb.append(moved).append(" 件を \"").append(folder).append("\" に移動しました");
            if (skipped > 0) sb.append("（").append(skipped).append(" 件は同じフォルダのためスキップ）");
            if (failed > 0) sb.append("（").append(failed).append(" 件は失敗）");
        } else {
            sb.append("Moved ").append(moved).append(moved == 1 ? " item" : " items")
                    .append(" to \"").append(folder).append('"');
            if (skipped > 0) sb.append(" (").append(skipped).append(" skipped: same folder)");
            if (failed > 0) sb.append(" (").append(failed).append(" failed)");
        }
        return sb.toString();
    }

    private static void toast(Context app, String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                DiagLog.e(AREA, "toast failed", t);
            }
        });
    }
}
