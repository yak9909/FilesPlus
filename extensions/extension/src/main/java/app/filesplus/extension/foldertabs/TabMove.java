package app.filesplus.extension.foldertabs;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.browse.LocalFolders;
import app.filesplus.extension.clip.Clipboard;
import app.filesplus.extension.clip.PasteRules;
import app.filesplus.extension.ops.Fs;
import app.filesplus.extension.ops.LocalFs;
import app.filesplus.extension.ops.VerifiedCopy;
import app.filesplus.extension.ops.VerifiedFolderMove;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * File operations for "move selected files to the current folder tab". No app types, no UI.
 *
 * Same-volume moves reserve a target and rename onto it. Cross-volume files and folders use verified
 * copy followed by a rename to the FilesPlus trash on the source volume.
 */
public final class TabMove {
    private static final String AREA = "foldertabs";
    private static final Uri FILES = MediaStore.Files.getContentUri("external");
    private static final int CHUNK = 400;
    private static final int SCAN_BATCH = 500;
    private static final LocalFs LOCAL = new LocalFs();
    private static volatile String lastCrossVolumeCopy;

    private static ExecutorService fileExecutor;

    public static final class Result {
        public int moved;
        public int skipped;
        public int failed;
        public final EnumSet<VerifiedCopy.Reason> reasons = EnumSet.noneOf(VerifiedCopy.Reason.class);
        public boolean hashStopped;
        public boolean sourceKept;
        public final List<String> keptSources = new ArrayList<>();
    }

    private static final class CopyFailure extends IOException {
        final VerifiedCopy.Reason reason;

        CopyFailure(VerifiedCopy.Reason reason) {
            super(reason.name());
            this.reason = reason;
        }
    }

    private TabMove() {}

    /**
     * The one thread that changes files (moves and trash renames of both the folder tabs and the move
     * queue). Using a single thread means two moves can never run at the same time.
     */
    public static synchronized ExecutorService fileExecutor() {
        if (fileExecutor == null) {
            fileExecutor = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                    // Priority is best effort.
                }
                r.run();
            }, "filesplus-file-ops"));
        }
        return fileExecutor;
    }

    public static String lastCrossVolumeCopy() { return lastCrossVolumeCopy; }

    /** Resolves MediaStore ids to file paths. Blocking: call off the UI thread. */
    public static List<String> pathsOf(Context context, long[] ids) {
        return pathsOf(context, ids, null);
    }

    /** Also retains each known MediaStore id for clipboard consumers. Blocking, like pathsOf. */
    public static List<String> pathsOf(Context context, long[] ids, java.util.Map<String, Long> known) {
        List<String> paths = new ArrayList<>();
        for (int from = 0; from < ids.length; from += CHUNK) {
            int to = Math.min(ids.length, from + CHUNK);
            String[] args = new String[to - from];
            for (int i = from; i < to; i++) args[i - from] = Long.toString(ids[i]);
            String selection = "_id IN (" + String.join(",", Collections.nCopies(args.length, "?")) + ")";
            try (Cursor cursor = context.getContentResolver().query(FILES, new String[]{"_data", "_id"},
                    selection, args, null)) {
                if (cursor != null) while (cursor.moveToNext()) {
                    String data = cursor.getString(0);
                    if (data != null) {
                        paths.add(data);
                        if (known != null) known.put(data, cursor.getLong(1));
                    }
                }
            }
        }
        return paths;
    }

    /**
     * Moves every source into the destination folder, keeping both on a name clash. Blocking: call it
     * on {@link #fileExecutor()} only.
     */
    public static Result move(Context context, List<String> sources, File destination) {
        return move(context, sources, destination, null);
    }

    /**
     * {@link ReservationListener#reserved(File)} runs before attempting to create the placeholder, so the
     * listener can persist the name first. {@link ReservationListener#done(File)} runs when a candidate is done,
     * including a taken name or a failed reservation. A recorded name may never have been created.
     */
    public interface ReservationListener {
        void reserved(File target);

        void done(File target);

        default void partStarted(File part, boolean directory) throws IOException {}

        default void partFinished(File part, boolean directory) {}
    }

    /** Same as {@link #move(Context, List, File)}, reporting every reserved name to {@code listener} (may be null). */
    public static Result move(Context context, List<String> sources, File destination,
                              ReservationListener listener) {
        return move(context, sources, destination, listener, new VerifiedCopy.Job(), null);
    }

    /** Recounts present items on the file executor before the paste touches the destination. */
    public static VerifiedFolderMove.PasteBudget measurePaste(List<String> sources) throws IOException {
        return VerifiedFolderMove.measurePaste(LOCAL, sources, PasteRules.MAX_ITEMS, PasteRules.MAX_BYTES);
    }

    public static Result move(Context context, List<String> sources, File destination,
                              ReservationListener listener, VerifiedCopy.Job job, VerifiedFolderMove.PasteBudget budget) {
        // Preview moves only need a running record if they start a cross-volume copy.
        PasteRecord standalone = listener == null ? new PasteRecord(context) : null;
        ReservationListener recorder = standalone == null ? listener : standalone;
        Result result = new Result();
        List<String> oldPaths = new ArrayList<>();
        List<String> newPaths = new ArrayList<>();
        for (String path : sources) {
            try {
                if (job.stopped()) {
                    result.failed++;
                    DiagLog.e(AREA, "HASH_MISMATCH: job stopped, leaving " + path, null);
                    continue;
                }
                File source = new File(path);
                Fs.Stat sourceStat = LOCAL.lstat(source.getPath());
                if (sourceStat == null) throw new IOException("Source is missing");
                if (VerifiedCopy.mediaStoreName(source.getName())) {
                    throw new CopyFailure(VerifiedCopy.Reason.NAME_INVALID);
                }
                File parent = source.getParentFile();
                // Case-insensitive on purpose: FAT / FUSE treat names that differ in case as the same.
                if (parent != null && parent.getCanonicalPath().toLowerCase(Locale.ROOT)
                        .equals(destination.getCanonicalPath().toLowerCase(Locale.ROOT))) {
                    result.skipped++;
                    continue;
                }
                boolean directory = sourceStat.type == Fs.Type.DIRECTORY;
                List<String> childPaths = new ArrayList<>();
                if (directory) {
                    File canonical = source.getCanonicalFile();
                    if (FolderTabContext.protectedPath(canonical, LocalFolders.roots(context), true))
                        throw new IOException("Protected folder cannot be moved: " + canonical);
                    // Case-insensitive on purpose: FAT / FUSE treat names that differ in case as the same.
                    String src = canonical.getPath().toLowerCase(Locale.ROOT) + File.separator;
                    String dst = destination.getCanonicalPath().toLowerCase(Locale.ROOT) + File.separator;
                    if (dst.startsWith(src)) throw new IOException("Cannot move a folder into itself or below itself");
                }
                File target;
                Fs.Stat destStat = LOCAL.lstat(destination.getPath());
                if (destStat != null && sourceStat.device != destStat.device) {
                    // Some SD names fail at reservation time, before rename could report EXDEV.
                    target = copyAcrossVolumes(context, source, sourceStat, destination, recorder, job, result,
                            childPaths, budget);
                } else {
                    if (directory) childPaths.addAll(FolderTrash.mediaPaths(context, source));
                    try {
                        target = transfer(source, destination, directory, recorder);
                    } catch (ErrnoException e) {
                        childPaths.clear();
                        target = copyAcrossVolumes(context, source, sourceStat, destination, recorder, job, result,
                                childPaths, budget);
                    }
                }
                result.moved++;
                oldPaths.add(source.getPath());
                newPaths.add(target.getPath());
                String oldPrefix = source.getPath() + File.separator;
                for (String child : childPaths) {
                    oldPaths.add(child);
                    newPaths.add(target.getPath() + File.separator + child.substring(oldPrefix.length()));
                }
                if (!target.getName().equals(source.getName()))
                    DiagLog.i(AREA, "Renamed on conflict: " + source.getName() + " -> " + target.getName());
            } catch (CopyFailure e) {
                result.failed++;
                result.reasons.add(e.reason);
                DiagLog.e(AREA, "Failed to move " + path + " (" + e.reason + ")", e);
            } catch (Throwable t) {
                result.failed++;
                DiagLog.e(AREA, "Failed to move " + path, t);
            }
        }
        result.hashStopped = job.stopped();
        if (standalone != null) standalone.end();
        syncMediaStore(context, oldPaths, newPaths);
        DiagLog.i(AREA, "Move to " + destination + ": moved=" + result.moved + ", skipped=" + result.skipped
                + ", failed=" + result.failed);
        return result;
    }

    private static File copyAcrossVolumes(Context context, File source, Fs.Stat sourceStat, File destination,
                                          ReservationListener listener, VerifiedCopy.Job job, Result result,
                                          List<String> childPaths, VerifiedFolderMove.PasteBudget budget)
            throws IOException {
        long started = System.nanoTime();
        long bytes = 0;
        String outcome = "FAILED/" + VerifiedCopy.Reason.IO;
        try {
            File folder = destination.getAbsoluteFile();
            File parent = source.getAbsoluteFile().getParentFile();
            if (parent == null || !parent.equals(parent.getCanonicalFile()) || !folder.equals(folder.getCanonicalFile())) {
                throw new IOException("Linked parent or destination");
            }
            boolean directory = sourceStat.type == Fs.Type.DIRECTORY;
            if (!directory && sourceStat.type != Fs.Type.FILE) {
                outcome = "FAILED/" + VerifiedCopy.Reason.LINK;
                throw new CopyFailure(VerifiedCopy.Reason.TOP_LEVEL_LINK);
            }
            if (directory && (!folder.canWrite() || !Environment.isExternalStorageManager())) {
                throw new IOException("Destination is not writable or all-files access is missing: " + folder);
            }
            StorageManager storage = context.getSystemService(StorageManager.class);
            StorageVolume volume = storage.getStorageVolume(folder);
            boolean removable = volume != null && volume.isRemovable();
            VerifiedCopy.Observer observer = copyObserver(listener, directory);
            if (directory) {
                VerifiedFolderMove.Result copy = VerifiedFolderMove.move(LOCAL, source.getPath(), LOCAL,
                        folder.getPath(), source.getName(), removable, job, () -> false,
                        observer, (opId, fileCount, totalBytes, check) -> {
                            File root = sourceVolume(context, source, sourceStat);
                            FolderTrash.moveToTrash(source, root, fileCount, totalBytes, opId, () -> {
                                if (!parent.equals(parent.getCanonicalFile())) throw new Fs.SourceChanged(source.getPath());
                                check.check();
                            });
                        }, budget);
                bytes = copy.bytes;
                if (copy.reason != null) {
                    outcome = "FAILED/" + copy.reason;
                    throw new CopyFailure(copy.reason);
                }
                for (String relative : copy.children) childPaths.add(new File(source, relative).getPath());
                outcome = "MOVED";
                if (copy.sourceKept) {
                    keepSource(result, source);
                    outcome = "MOVED (source kept)";
                }
                return new File(copy.target);
            }

            VerifiedCopy.Result copy = VerifiedCopy.copy(LOCAL, source.getPath(), LOCAL, folder.getPath(),
                    source.getName(), removable, job, () -> false, observer);
            bytes = copy.bytes;
            if (!copy.succeeded()) {
                outcome = "FAILED/" + copy.reason;
                throw new CopyFailure(copy.reason);
            }
            outcome = "MOVED";
            try {
                if (!copy.source.unchanged(LOCAL.lstat(source.getPath()))) throw new Fs.SourceChanged(source.getPath());
                FolderTrash.moveFileToTrash(source, sourceVolume(context, source, copy.source), copy.opId, copy.source);
            } catch (Throwable t) {
                keepSource(result, source);
                outcome = "MOVED/" + (t instanceof Fs.SourceChanged ? "SOURCE_CHANGED" : "IO") + " (source kept)";
                DiagLog.e(AREA, outcome + ": " + source, t);
            }
            return new File(copy.target);
        } finally {
            long elapsed = System.nanoTime() - started;
            double rate = elapsed > 0 && outcome.startsWith("MOVED") ? bytes * 1000.0 / elapsed : 0;
            lastCrossVolumeCopy = String.format(Locale.ROOT, "%d bytes, %d ms, %.2f MB/s, %s",
                    bytes, elapsed / 1_000_000, rate, outcome);
            DiagLog.i(AREA, "Cross-volume copy: " + lastCrossVolumeCopy);
        }
    }

    private static void keepSource(Result result, File source) {
        result.sourceKept = true;
        result.keptSources.add(source.getPath());
    }

    private static File sourceVolume(Context context, File source, Fs.Stat expected) throws IOException {
        File root = LocalFolders.volumeRoot(context, source);
        Fs.Stat rootStat = root == null ? null : LOCAL.lstat(root.getPath());
        if (rootStat == null || rootStat.device != expected.device) {
            throw new IOException("Cannot resolve source volume for trash");
        }
        return root;
    }

    private static VerifiedCopy.Observer copyObserver(ReservationListener listener, boolean directory) {
        return new VerifiedCopy.Observer() {
            @Override public void partStarted(String path) throws IOException {
                listener.partStarted(new File(path), directory);
            }

            @Override public void partFinished(String path) {
                listener.partFinished(new File(path), directory);
            }

            @Override public void reserved(String path) {
                notifyReserved(listener, new File(path), true);
            }

            @Override public void reservationDone(String path) {
                notifyReserved(listener, new File(path), false);
            }

            @Override public void log(String message, Throwable error) {
                DiagLog.e(AREA, message, error);
            }
        };
    }

    private static final class PasteRecord implements ReservationListener {
        private final Context context;
        private long token;

        PasteRecord(Context context) { this.context = context; }

        @Override public void partStarted(File part, boolean directory) throws IOException {
            if (token == 0) token = Clipboard.beginPaste(context, part.getParent());
            if (!Clipboard.recordPart(context, token, part.getPath(), directory)) {
                throw new IOException("Cannot record temporary path: " + part);
            }
        }

        @Override public void partFinished(File part, boolean directory) {
            Clipboard.forgetPart(context, token, part.getPath(), directory);
        }

        @Override public void reserved(File target) {
            if (token != 0) Clipboard.reserve(context, token, target.getPath());
        }

        @Override public void done(File target) {
            if (token != 0) Clipboard.unreserve(context, token, target.getPath());
        }

        void end() {
            if (token != 0) Clipboard.endPaste(context, token);
        }
    }

    /** What we created at the target name, so that only our own placeholder is ever removed. */
    private static final class Reservation {
        final File file;
        final boolean directory;
        final long inode;
        final long device;

        Reservation(File file, boolean directory, long inode, long device) {
            this.file = file;
            this.directory = directory;
            this.inode = inode;
            this.device = device;
        }
    }

    /**
     * Reserves a free name in {@code destination} and renames the source onto it. {@code rename(2)} replaces
     * silently, so it is only ever called on a name that this call just created itself (zero-byte file or empty
     * folder). Propagates EXDEV with the source untouched so the caller can copy it.
     */
    private static File transfer(File source, File destination, boolean directory, ReservationListener listener)
            throws IOException, ErrnoException {
        String name = source.getName();
        for (int n = 0; n < VerifiedCopy.MAX_NAME_ATTEMPTS; n++) {
            File target = new File(destination, VerifiedCopy.candidate(name, directory, n));
            // Persist intent before creating anything: a death during commit cannot leave an unrecorded name.
            notifyReserved(listener, target, true);
            try {
                Reservation reservation = reserve(target, directory);
                if (reservation == null) continue; // The name is taken: try the next candidate.
                if (!isUntouched(reservation)) {
                    // Somebody else changed our placeholder: do not touch it, use another name.
                    DiagLog.e(AREA, "Reservation changed by someone else, not touching it: " + target, null);
                    continue;
                }
                try {
                    Os.rename(source.getPath(), target.getPath());
                    return target;
                } catch (ErrnoException e) {
                    boolean stillOurs = isUntouched(reservation);
                    release(reservation);
                    if (e.errno == OsConstants.EXDEV) throw e;
                    if (directory && (e.errno == OsConstants.ENOTEMPTY || e.errno == OsConstants.EEXIST)) {
                        if (stillOurs) {
                            // Our placeholder is still empty, so the platform itself refused to replace it:
                            // other names would fail the same way. Stop and fail this item.
                            throw new IOException("The platform refused to rename a folder onto an empty folder ("
                                    + OsConstants.errnoName(e.errno) + "): " + source + " -> " + target, e);
                        }
                        // Someone put something into our placeholder folder; nothing was lost. Next name.
                        DiagLog.e(AREA, "Reserved folder was filled by someone else: " + target, e);
                        continue;
                    }
                    throw new IOException("rename failed (" + OsConstants.errnoName(e.errno) + "): " + source
                            + " -> " + target, e);
                }
            } finally {
                // Covers taken names, changed placeholders and exceptions as well as a completed rename.
                notifyReserved(listener, target, false);
            }
        }
        throw new IOException("No free name found in " + destination + " for " + name);
    }

    private static void notifyReserved(ReservationListener listener, File target, boolean reserved) {
        if (listener == null) return;
        try {
            if (reserved) listener.reserved(target);
            else listener.done(target);
        } catch (Throwable t) {
            DiagLog.e(AREA, "Reservation listener failed for " + target, t);
        }
    }

    /** Creates the placeholder atomically (O_EXCL / mkdir). Returns null when the name already exists. */
    private static Reservation reserve(File target, boolean directory) throws IOException {
        try {
            if (directory) Files.createDirectory(target.toPath());
            else Files.createFile(target.toPath());
        } catch (FileAlreadyExistsException e) {
            return null;
        }
        try {
            StructStat stat = Os.lstat(target.getPath());
            return new Reservation(target, directory, stat.st_ino, stat.st_dev);
        } catch (ErrnoException e) {
            throw new IOException("Cannot inspect the reserved name " + target + ": " + e, e);
        }
    }

    /** True while the placeholder is still exactly what we created: empty, same type, same inode. */
    private static boolean isUntouched(Reservation reservation) {
        try {
            StructStat stat = Os.lstat(reservation.file.getPath());
            if (stat.st_ino != reservation.inode || stat.st_dev != reservation.device) return false;
            if (reservation.directory) {
                if (!OsConstants.S_ISDIR(stat.st_mode)) return false;
                String[] children = reservation.file.list();
                return children != null && children.length == 0;
            }
            return OsConstants.S_ISREG(stat.st_mode) && stat.st_size == 0;
        } catch (ErrnoException e) {
            return false;
        }
    }

    /** Removes our own placeholder (unlink / rmdir, never recursive) if it is still untouched. */
    private static void release(Reservation reservation) {
        try {
            if (isUntouched(reservation) && !reservation.file.delete())
                DiagLog.e(AREA, "Cannot remove the reserved name " + reservation.file, null);
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot remove the reserved name " + reservation.file, t);
        }
    }

    /**
     * Never uses ContentResolver.delete (on Android 11+ that would delete real files): stale rows are
     * dropped by scanning the old paths (a missing path removes its row) and the new paths are registered.
     */
    private static void syncMediaStore(Context context, List<String> oldPaths, List<String> newPaths) {
        if (oldPaths.isEmpty()) return;
        List<String> all = new ArrayList<>(oldPaths);
        all.addAll(newPaths);
        try {
            for (int i = 0; i < all.size(); i += SCAN_BATCH) {
                List<String> part = all.subList(i, Math.min(all.size(), i + SCAN_BATCH));
                MediaScannerConnection.scanFile(context, part.toArray(new String[0]), null, null);
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Failed to scan moved files", t);
        }
    }
}
