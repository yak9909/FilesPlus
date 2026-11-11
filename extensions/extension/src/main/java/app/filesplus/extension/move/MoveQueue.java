package app.filesplus.extension.move;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.foldertabs.TabMove;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /** Called once on the main thread when every job of one enqueue call has finished (moved, skipped or failed). */
    public interface Done {
        void onDone(int moved, int notMoved);
    }

    /** One enqueue call: collects the results of its jobs and reports them once. */
    private static final class Batch {
        final Context app;
        final File destination;
        final Done done;
        int pending;
        int moved;
        int skipped;
        int failed;
        boolean destinationLogged;

        Batch(Context app, File destination, Done done, int pending) {
            this.app = app;
            this.destination = destination;
            this.done = done;
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
        enqueue(app, ids, mimes, destination, null);
    }

    /**
     * Same, with a completion callback (main thread; must not capture an Activity or View strongly).
     * Returns how many jobs are held until the user leaves their page (0 when nothing was queued).
     */
    public static int enqueue(Context app, long[] ids, String[] mimes, File destination, Done done) {
        try {
            // Prefer the application context so no Activity is kept alive by a queued job.
            Context context = ModKit.context();
            if (context == null && app != null) context = app.getApplicationContext();
            if (context == null) context = app;
            if (context == null || ids == null || ids.length == 0 || destination == null) return 0;
            // One job per distinct id (first mime wins).
            Map<Long, String> unique = new LinkedHashMap<>();
            for (int i = 0; i < ids.length; i++) {
                if (!unique.containsKey(ids[i])) unique.put(ids[i], mimes != null && i < mimes.length ? mimes[i] : null);
            }
            Batch batch = new Batch(context, destination, done, unique.size());
            List<Job> now = new ArrayList<>();
            int held = 0;
            synchronized (LOCK) {
                for (Map.Entry<Long, String> entry : unique.entrySet()) {
                    Job job = new Job(batch, entry.getKey(), entry.getValue());
                    if (isDeferrable(job) && job.id == currentId) {
                        deferred.add(job);
                        held++;
                    } else {
                        now.add(job);
                    }
                }
            }
            DiagLog.i(AREA, "Enqueued " + unique.size() + " item(s) to " + destination + ", held " + held);
            for (Job job : now) submit(job);
            return held;
        } catch (Throwable t) {
            DiagLog.e(AREA, "enqueue failed", t);
            return 0;
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
        if (mime == null) return false;
        if (mime.startsWith("video/") || mime.startsWith("audio/")) return true;
        // Odd types such as application/ogg: decide by the extension the type stands for.
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return extension != null && MEDIA_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    private static final Set<String> MEDIA_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "mp3", "m4a", "aac", "ogg", "oga", "ogv", "opus",
            "flac", "wav", "ts"));

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
                executor = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (Throwable ignored) {
                        // Priority is best effort.
                    }
                    r.run();
                }, "filesplus-move-queue"));
            }
            return executor;
        }
    }

    private static void run(Job job) {
        int moved = 0, skipped = 0, failed = 0;
        try {
            File destination = job.batch.destination;
            if (!destination.isDirectory() || !destination.canWrite()) {
                boolean first;
                synchronized (job.batch) {
                    first = !job.batch.destinationLogged;
                    job.batch.destinationLogged = true;
                }
                if (first) DiagLog.e(AREA, "Destination unusable: " + destination, null);
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
            if (batch.done != null) {
                // MediaStore was synced inside TabMove.move, so a reload from here no longer lists the moved files.
                int notMoved = totalSkipped + totalFailed;
                int movedCount = totalMoved;
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        batch.done.onDone(movedCount, notMoved);
                    } catch (Throwable t) {
                        DiagLog.e(AREA, "completion callback failed", t);
                    }
                });
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "finish failed", t);
        }
    }

    private static String message(File destination, int moved, int skipped, int failed) {
        boolean ja = "ja".equals(Locale.getDefault().getLanguage());
        String folder = destination.getName().isEmpty() ? destination.getPath() : destination.getName();
        StringBuilder sb = new StringBuilder();
        if (moved == 0 && failed == 0 && skipped > 0) {
            return ja ? "すでにそのフォルダにあります" : "Already in this folder";
        }
        if (moved == 0 && failed > 0) {
            // Everything failed: lead with the failure instead of "Moved 0 items".
            if (ja) {
                sb.append("移動できませんでした（").append(failed).append(" 件）");
                if (skipped > 0) sb.append("（").append(skipped).append(" 件は同じフォルダのためスキップ）");
            } else {
                sb.append("Could not move ").append(failed).append(failed == 1 ? " item" : " items");
                if (skipped > 0) sb.append(" (").append(skipped).append(" skipped: same folder)");
            }
            return sb.toString();
        }
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
