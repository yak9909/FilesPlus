package app.filesplus.extension.move;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.clip.ClipItem;
import app.filesplus.extension.clip.Clipboard;
import app.filesplus.extension.clip.PasteRules;
import app.filesplus.extension.clip.PasteTarget;
import app.filesplus.extension.foldertabs.TabMove;
import app.filesplus.extension.ops.VerifiedCopy;
import app.filesplus.extension.ops.VerifiedFolderMove;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Background move queue that outlives the preview Activity. Files are moved one job per id on a single
 * thread. Audio/video that is still shown on the current preview page is held until the user leaves
 * that page or the preview closes. Holds only the application context, never an Activity or View.
 *
 * <p>{@link #paste} runs Cut and Copy through TabMove on the single file
 * thread, including verified cross-volume file moves.
 */
public final class MoveQueue {
    private static final String AREA = "move";
    private static final Object LOCK = new Object();
    private static final long NO_PAGE = Long.MIN_VALUE;

    private static long currentId = NO_PAGE;
    private static String currentPath;
    private static final List<Job> deferred = new ArrayList<>();
    /** Sources (lower case) of unfinished paste jobs: the same source is never in two jobs (DESIGN 10.8). */
    private static final Set<String> inFlight = new HashSet<>();

    private MoveQueue() {}

    /** Called once on the main thread when every job of one enqueue call has finished (moved, skipped or failed). */
    public interface Done {
        void onDone(int moved, int notMoved);
    }

    /** Called once on the main thread when a {@link #paste} has finished or was refused. */
    public interface PasteCallback {
        void onDone(PasteResult result);
    }

    /** Outcome of one {@link #paste}. */
    public static final class PasteResult {
        /** Non-null when nothing was started: the rule that refused the paste. */
        public PasteRules.Reason refused;
        public File destination;
        public int moved;
        public int copied;
        /** Items that no longer existed (deleted or renamed elsewhere, SD card removed). */
        public int missing;
        /** Items already in the destination folder (skipped). */
        public int sameFolder;
        /** Items that are part of another unfinished paste (skipped, not touched). */
        public int busy;
        public int failed;
        /** Items held until the preview page they play on is left. */
        public int held;
        private TabMove.Result moveDetails;
    }

    /** One paste: its clipboard snapshot, callback and counters. */
    private static final class PasteJob {
        final Clipboard.State state;
        final PasteCallback callback;
        final PasteResult result = new PasteResult();
        final List<ClipItem> present = new ArrayList<>();
        final VerifiedCopy.Job copyJob = new VerifiedCopy.Job();
        VerifiedFolderMove.PasteBudget budget;
        /** The record of this paste in {@link Clipboard#beginPaste}. */
        long token;

        PasteJob(Clipboard.State state, PasteCallback callback) {
            this.state = state;
            this.callback = callback;
        }
    }

    /** One enqueue call: collects the results of its jobs and reports them once. */
    private static final class Batch {
        final Context app;
        final File destination;
        final Done done;
        final PasteJob paste;
        int pending;
        final TabMove.Result moves = new TabMove.Result();
        boolean destinationLogged;

        Batch(Context app, File destination, Done done, int pending) {
            this(app, destination, done, null, pending);
        }

        Batch(Context app, File destination, Done done, PasteJob paste, int pending) {
            this.app = app;
            this.destination = destination;
            this.done = done;
            this.paste = paste;
            this.pending = pending;
        }
    }

    private static final class Job {
        final Batch batch;
        final long id;
        final String mime;
        /** Paste jobs know their paths; queue jobs resolve the MediaStore id when they run. */
        final List<String> paths;

        Job(Batch batch, long id, String mime) {
            this(batch, id, mime, null);
        }

        Job(Batch batch, long id, String mime, List<String> paths) {
            this.batch = batch;
            this.id = id;
            this.mime = mime;
            this.paths = paths;
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
    public static void onPageChanged(long currentId, String path) {
        try {
            List<Job> release = new ArrayList<>();
            synchronized (LOCK) {
                MoveQueue.currentId = currentId;
                currentPath = path;
                for (Iterator<Job> it = deferred.iterator(); it.hasNext(); ) {
                    Job job = it.next();
                    boolean same = job.paths == null ? job.id == currentId
                            : samePreviewFile(job.id, job.paths.get(0), currentId, path);
                    if (!same) {
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
                currentPath = null;
                release = new ArrayList<>(deferred);
                deferred.clear();
            }
            for (Job job : release) submit(job);
        } catch (Throwable t) {
            DiagLog.e(AREA, "onPreviewClosed failed", t);
        }
    }

    private static boolean isDeferrable(Job job) {
        return isDeferrable(job.mime);
    }

    static boolean samePreviewFile(long id, String path, long pageId, String pagePath) {
        if (id > 0 && pageId > 0) return id == pageId;
        return path != null && path.equals(pagePath);
    }

    private static boolean isDeferrable(String mime) {
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
            // The shared file-operation thread: queue jobs and "move to current tab" never run concurrently.
            TabMove.fileExecutor().execute(() -> run(job));
        } catch (Throwable t) {
            DiagLog.e(AREA, "submit failed", t);
            TabMove.Result result = new TabMove.Result();
            result.failed = 1;
            finish(job, result);
        }
    }

    private static void run(Job job) {
        TabMove.Result result = new TabMove.Result();
        try {
            File destination = job.batch.destination;
            if (!destination.isDirectory() || !destination.canWrite()) {
                boolean first;
                synchronized (job.batch) {
                    first = !job.batch.destinationLogged;
                    job.batch.destinationLogged = true;
                }
                if (first) DiagLog.e(AREA, "Destination unusable: " + destination, null);
                result.failed = 1;
            } else {
                List<String> paths = job.paths != null ? job.paths : TabMove.pathsOf(job.batch.app, new long[]{job.id});
                if (paths.isEmpty()) {
                    DiagLog.e(AREA, "No path for id " + job.id, null);
                    result.failed = 1;
                } else {
                    Batch batch = job.batch;
                    TabMove.ReservationListener listener = null;
                    if (batch.paste != null) {
                        long token = batch.paste.token;
                        listener = new TabMove.ReservationListener() {
                            @Override public void reserved(File target) {
                                Clipboard.reserve(batch.app, token, target.getPath());
                            }

                            @Override public void done(File target) {
                                Clipboard.unreserve(batch.app, token, target.getPath());
                            }

                            @Override public void partStarted(File part, boolean directory) throws IOException {
                                if (!Clipboard.recordPart(batch.app, token, part.getPath(), directory)) {
                                    throw new IOException("Cannot record temporary path: " + part);
                                }
                            }

                            @Override public void partFinished(File part, boolean directory) {
                                Clipboard.forgetPart(batch.app, token, part.getPath(), directory);
                            }
                        };
                    }
                    if (batch.paste == null) {
                        result = TabMove.move(batch.app, paths, destination);
                    } else if (batch.paste.state.mode == Clipboard.Mode.COPY) {
                        result = TabMove.copy(batch.app, paths, destination, listener, batch.paste.copyJob,
                                batch.paste.budget);
                    } else {
                        result = TabMove.move(batch.app, paths, destination, listener, batch.paste.copyJob,
                                batch.paste.budget);
                    }
                }
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Job failed for id " + job.id, t);
            result.failed = 1;
        }
        finish(job, result);
    }

    private static void finish(Job job, TabMove.Result result) {
        try {
            Batch batch = job.batch;
            if (job.paths != null) release(job.paths);
            TabMove.Result total = batch.moves;
            synchronized (batch) {
                total.moved += result.moved;
                total.copied += result.copied;
                total.skipped += result.skipped;
                total.failed += result.failed;
                total.keptSources.addAll(result.keptSources);
                total.reasons.addAll(result.reasons);
                total.hashStopped |= result.hashStopped;
                total.sourceKept |= result.sourceKept;
                if (--batch.pending != 0) return;
            }
            if (batch.paste != null) {
                finishPaste(batch);
                return;
            }
            if (total.moved > 0) DestinationHistory.recordLast(batch.destination);
            String summary = message(batch.destination, total.moved, total.skipped, total.failed);
            boolean ja = "ja".equals(batch.app.getResources().getConfiguration().getLocales().get(0).getLanguage());
            toast(batch.app, withMoveNotes(summary, ja, total));
            if (batch.done != null) {
                // MediaStore was synced inside TabMove.move, so a reload from here no longer lists the moved files.
                int notMoved = total.skipped + total.failed;
                int movedCount = total.moved;
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

    // ---- paste (clipboard -> folder) ---------------------------------------------------------------------

    /** Pastes the clipboard into an existing folder. See {@link #paste(Context, Clipboard.State, File, boolean, PasteCallback)}. */
    public static void paste(Context app, Clipboard.State state, File dest, PasteCallback callback) {
        paste(app, state, dest, false, callback);
    }

    /**
     * Pastes the clipboard items into {@code dest}, one batch on the single file thread. Returns at once;
     * the callback runs on the main thread when everything is done (items that play on the current preview page
     * wait until the page is left, as in the move queue). Safe to call twice: the rules are checked again here, a
     * source that is already part of an unfinished paste is skipped (counted as busy), and items that no longer
     * exist are skipped and counted as missing.
     *
     * <p>COPY keeps the clipboard for reuse. A successful CUT paste clears it, and after failures it retains
     * the remaining items. The destination becomes the "last pasted folder". With {@code createIfMissing} a missing
     * destination folder is created (one level, below an existing writable folder).
     */
    public static void paste(Context app, Clipboard.State state, File dest, boolean createIfMissing,
                             PasteCallback callback) {
        PasteJob paste = new PasteJob(state, callback);
        paste.result.destination = dest;
        Context context = null;
        List<ClipItem> claimed = new ArrayList<>();
        try {
            context = ModKit.context();
            if (context == null && app != null) context = app.getApplicationContext();
            if (context == null) context = app;
            if (context == null || state == null || state.items.isEmpty()) {
                paste.result.refused = PasteRules.Reason.EMPTY;
                reportPaste(context, paste);
                return;
            }
            if (dest == null) {
                paste.result.refused = PasteRules.Reason.NOT_WRITABLE;
                reportPaste(context, paste);
                return;
            }
            // Claim the sources first, on the caller's thread, so a double tap cannot start a second job for them.
            Set<String> seen = new HashSet<>();
            synchronized (LOCK) {
                for (ClipItem item : state.items) {
                    String key = item.path().toLowerCase(Locale.ROOT);
                    if (!seen.add(key)) continue; // The same path twice in one clipboard is one item.
                    if (inFlight.add(key)) claimed.add(item);
                    else paste.result.busy++;
                }
            }
            if (claimed.isEmpty()) {
                reportPaste(context, paste);
                return;
            }
            Context ctx = context;
            boolean create = createIfMissing;
            List<ClipItem> toPlan = claimed;
            TabMove.fileExecutor().execute(() -> plan(ctx, paste, dest, create, toPlan));
        } catch (Throwable t) {
            DiagLog.e(AREA, "paste failed", t);
            for (ClipItem item : claimed) release(Collections.singletonList(item.path()));
            paste.result.refused = PasteRules.Reason.NOT_WRITABLE;
            try {
                reportPaste(context, paste);
            } catch (Throwable ignored) {
                // Nothing more to do.
            }
        }
    }

    /** Runs on the file thread: re-checks the rules, drops missing items, defers playing ones, transfers the rest. */
    private static void plan(Context context, PasteJob paste, File dest, boolean createIfMissing,
                             List<ClipItem> claimed) {
        boolean started = false;
        try {
            warnInterrupted(context);
            PasteRules.Result check = PasteRules.check(paste.state, PasteTarget.ofFolder(dest, createIfMissing));
            if (!check.ok) {
                release(claimed);
                paste.result.refused = check.reason;
                reportPaste(context, paste);
                return;
            }
            // Count current data before creating the destination or starting any item, including deferred ones.
            List<String> presentPaths = new ArrayList<>();
            List<String> missingPaths = new ArrayList<>();
            for (ClipItem item : claimed) {
                if (Files.exists(new File(item.path()).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    paste.present.add(item);
                    presentPaths.add(item.path());
                } else {
                    paste.result.missing++;
                    missingPaths.add(item.path());
                }
            }
            paste.budget = TabMove.measurePaste(presentPaths);
            if (paste.budget == null) {
                release(claimed);
                paste.result.refused = PasteRules.Reason.TOO_LARGE;
                DiagLog.i(AREA, "Paste exceeds live entry or byte limits: " + dest);
                reportPaste(context, paste);
                return;
            }
            if (!dest.isDirectory() && createIfMissing && !dest.mkdir() && !dest.isDirectory()) {
                release(claimed);
                paste.result.refused = PasteRules.Reason.NOT_WRITABLE;
                reportPaste(context, paste);
                return;
            }
            release(missingPaths);
            paste.token = Clipboard.beginPaste(context, dest.getCanonicalPath());
            started = true;

            // Decide in one step which items wait for the preview and set the counter before anything can finish.
            Batch batch;
            List<ClipItem> now = new ArrayList<>();
            List<Job> held = new ArrayList<>();
            synchronized (LOCK) {
                List<ClipItem> waiting = new ArrayList<>();
                for (ClipItem item : paste.present) {
                    boolean playing = samePreviewFile(item.mediaId(), item.path(), currentId, currentPath)
                            && isDeferrable(mimeOf(item.name()));
                    if (playing) waiting.add(item);
                    else now.add(item);
                }
                batch = new Batch(context, dest, null, paste, (now.isEmpty() ? 0 : 1) + waiting.size());
                for (ClipItem item : waiting) {
                    Job job = new Job(batch, item.mediaId(), mimeOf(item.name()),
                            Collections.singletonList(item.path()));
                    deferred.add(job);
                    held.add(job);
                }
            }
            paste.result.held = held.size();
            DiagLog.i(AREA, "Paste " + paste.present.size() + " item(s) to " + dest + ", held " + held.size()
                    + ", missing " + paste.result.missing + ", busy " + paste.result.busy);
            if (batch.pending == 0) {
                finishPaste(batch);
                return;
            }
            if (!held.isEmpty()) toast(context, heldText(context, held.size()));
            if (!now.isEmpty()) {
                List<String> paths = new ArrayList<>();
                for (ClipItem item : now) paths.add(item.path());
                run(new Job(batch, 0, null, paths)); // Already on the file thread.
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "paste plan failed", t);
            release(claimed);
            if (started) Clipboard.endPaste(context, paste.token);
            paste.result.refused = PasteRules.Reason.NOT_WRITABLE;
            reportPaste(context, paste);
        }
    }

    /**
     * A paste that was cut short by the process dying may have left a 0-byte placeholder (or an empty folder) at
     * the name it had reserved. Say so once, at the next paste; the record stays in Diagnostics until dismissed.
     * Nothing is deleted or resumed.
     */
    private static void warnInterrupted(Context context) {
        List<Clipboard.Interrupted> fresh = new ArrayList<>();
        for (Clipboard.Interrupted record : Clipboard.interrupted(context)) {
            if (!record.toasted) fresh.add(record);
        }
        if (fresh.isEmpty()) return;
        Clipboard.markInterruptedToasted(context);
        String folder = fresh.get(fresh.size() - 1).destination;
        boolean ja = "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        String more = fresh.size() > 1 ? (ja ? "（ほか " + (fresh.size() - 1) + " 件）" : " (and " + (fresh.size() - 1) + " more)") : "";
        toast(context, ja ? "前回の貼り付けが途中で止まりました: " + folder + more
                + "。0 バイトのファイルが残っていないか確かめてください（診断に記録があります）"
                : "The previous paste into " + folder + more + " was interrupted; check for leftover 0-byte files"
                + " (see Diagnostics)");
    }

    private static void release(List<?> paths) {
        synchronized (LOCK) {
            for (Object path : paths) {
                inFlight.remove((path instanceof ClipItem ? ((ClipItem) path).path() : (String) path)
                        .toLowerCase(Locale.ROOT));
            }
        }
    }

    private static String mimeOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Last unit of a paste is done: update the clipboard and the last folder, tell the user and the caller. */
    private static void finishPaste(Batch batch) {
        PasteJob paste = batch.paste;
        TabMove.Result moves = batch.moves;
        try {
            PasteResult result = paste.result;
            result.moved = moves.moved;
            result.copied = moves.copied;
            result.sameFolder = moves.skipped;
            result.failed = moves.failed;
            result.moveDetails = moves;
            if (moves.moved > 0 || moves.copied > 0) DestinationHistory.recordLast(batch.destination);
            if (paste.state.mode == Clipboard.Mode.CUT) {
                List<ClipItem> remaining = new ArrayList<>();
                if (moves.failed > 0) {
                    for (ClipItem item : paste.present) {
                        if (!moves.keptSources.contains(item.path()) && stillThere(item, batch.destination)) remaining.add(item);
                    }
                }
                Clipboard.finishCut(batch.app, paste.state, remaining);
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "paste finish failed", t);
        } finally {
            try {
                Clipboard.endPaste(batch.app, paste.token);
            } catch (Throwable t) {
                DiagLog.e(AREA, "paste marker not cleared", t);
            }
        }
        reportPaste(batch.app, paste);
    }

    /** True when the item still exists at its old place and that place is not the destination folder. */
    private static boolean stillThere(ClipItem item, File destination) {
        try {
            File source = new File(item.path());
            if (!Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
            File parent = source.getAbsoluteFile().getParentFile();
            return parent == null || !parent.getCanonicalPath().toLowerCase(Locale.ROOT)
                    .equals(destination.getCanonicalPath().toLowerCase(Locale.ROOT));
        } catch (IOException e) {
            return true; // Cannot tell: keep it so the user can try again.
        }
    }

    /** Toast with the full destination path and the counts, then the callback on the main thread. */
    private static void reportPaste(Context context, PasteJob paste) {
        PasteResult result = paste.result;
        if (context != null) {
            toast(context, result.refused != null
                    ? PasteRules.message(context, result.refused, paste.state == null ? 0 : paste.state.items.size(),
                            result.destination == null ? "" : result.destination.getName())
                    : pasteMessage(context, result));
        }
        if (paste.callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                paste.callback.onDone(result);
            } catch (Throwable t) {
                DiagLog.e(AREA, "paste callback failed", t);
            }
        });
    }

    private static String heldText(Context context, int count) {
        boolean ja = "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        return ja ? count + " 件は再生が終わるまで待ちます" : count + (count == 1 ? " item waits" : " items wait")
                + " until playback is left";
    }

    private static String pasteMessage(Context context, PasteResult r) {
        boolean ja = "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        String path = r.destination.getAbsolutePath();
        StringBuilder sb = new StringBuilder();
        int completed = r.moved + r.copied;
        if (completed == 0 && r.failed > 0) {
            sb.append(ja ? "貼り付けできませんでした（" + r.failed + " 件）" : "Could not paste " + r.failed
                    + (r.failed == 1 ? " item" : " items"));
            sb.append(ja ? " 貼り付け先: " : " to ").append(path);
        } else if (completed == 0) {
            sb.append(ja ? "貼り付けるものがありませんでした 貼り付け先: " : "Nothing to paste to ").append(path);
        } else if (r.copied > 0) {
            sb.append(copySummary(r.copied, path, ja));
        } else if (ja) {
            sb.append(r.moved).append(" 件を ").append(path).append(" に貼り付けました");
        } else {
            sb.append("Pasted ").append(r.moved).append(r.moved == 1 ? " item" : " items").append(" to ").append(path);
        }
        if (ja) {
            if (r.missing > 0) sb.append("（").append(r.missing).append(" 件はなくなっていたためスキップ）");
            if (r.sameFolder > 0) sb.append("（").append(r.sameFolder).append(" 件はすでにそのフォルダにあります）");
            if (r.busy > 0) sb.append("（").append(r.busy).append(" 件は処理中のためスキップ）");
            if (r.failed > 0 && completed > 0) sb.append("（").append(r.failed).append(" 件は失敗）");
        } else {
            if (r.missing > 0) sb.append(" (").append(r.missing).append(" missing, skipped)");
            if (r.sameFolder > 0) sb.append(" (").append(r.sameFolder).append(" already in the folder)");
            if (r.busy > 0) sb.append(" (").append(r.busy).append(" busy, skipped)");
            if (r.failed > 0 && completed > 0) sb.append(" (").append(r.failed).append(" failed)");
        }
        return withMoveNotes(sb.toString(), ja, r.moveDetails);
    }

    static String copySummary(int count, String path, boolean ja) {
        return ja ? count + " 件を " + path + " にコピーしました" : "Copied " + count + " item(s) to " + path;
    }

    static String withMoveNotes(String summary, boolean ja, TabMove.Result result) {
        if (result == null) return summary;
        // Text toasts can truncate the summary, so put the reason before the destination path.
        StringBuilder text = new StringBuilder();
        if (result.reasons.contains(VerifiedCopy.Reason.TOP_LEVEL_LINK)) {
            text.append(ja ? "リンクは移動できません" : "Links cannot be moved").append('\n');
        }
        if (result.reasons.contains(VerifiedCopy.Reason.LINK)) {
            text.append(ja ? "リンクや別のストレージを含むフォルダは移動できません"
                    : "Folders that contain links or other storage cannot be moved").append('\n');
        }
        if (result.reasons.contains(VerifiedCopy.Reason.TOO_LARGE)) {
            text.append(ja ? "フォルダの項目が多すぎるか深すぎるため移動できません"
                    : "The folder has too many items or is nested too deeply to move").append('\n');
        }
        if (result.reasons.contains(VerifiedCopy.Reason.NAME_INVALID)) {
            text.append(ja
                    ? "SD カードで使えない文字（\" * : < > ? \\ |）や末尾の点・空白を含む名前は移動できません"
                    : "Names with characters the SD card cannot store (\" * : < > ? \\ |) or a trailing dot/space cannot be moved").append('\n');
        }
        if (result.reasons.contains(VerifiedCopy.Reason.NO_SPACE)) {
            text.append(ja ? "移動先の空き容量が足りません" : "Not enough free space at the destination").append('\n');
        }
        if (result.hashStopped) {
            text.append(ja ? "書き込みの検証に失敗したため残りを止めました（SD カードを確かめてください）"
                    : "Stopped: written data failed verification (check the SD card)").append('\n');
        }
        if (result.sourceKept) {
            text.append(ja ? "元のファイル・フォルダは残しました" : "The original was kept").append('\n');
        }
        return text.length() == 0 ? summary : text.append(summary).toString();
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
