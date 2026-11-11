package app.filesplus.extension.foldertabs;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Process;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import app.filesplus.extension.DiagLog;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * File operations for "move selected files to the current folder tab". No app types, no UI.
 *
 * <p>Emergency hardening (DESIGN 10.4): the target name is reserved first (a zero-byte file created
 * with O_EXCL, or an empty folder) and the source is then renamed onto the reservation with
 * {@code Os.rename}. A rename across volumes (EXDEV) fails and leaves the source untouched; there is
 * no copy and no delete of user data anywhere in this class.
 */
public final class TabMove {
    private static final String AREA = "foldertabs";
    private static final Uri FILES = MediaStore.Files.getContentUri("external");
    private static final int CHUNK = 400;
    private static final int SCAN_BATCH = 500;
    private static final int MAX_NAME_ATTEMPTS = 1000;

    private static ExecutorService fileExecutor;

    public static final class Result {
        public int moved;
        public int skipped;
        public int failed;
        /** Subset of {@link #failed}: sources that sit on another volume than the destination. */
        public int crossVolume;
    }

    /** The move needs a copy (different volume). Nothing was changed. */
    private static final class CrossVolumeException extends IOException {
        CrossVolumeException(String message, Throwable cause) {
            super(message, cause);
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

    /** Same language rule as the folder-tab messages (the app's configured locale). */
    public static String crossVolumeNote(Context context) {
        return crossVolumeNote("ja".equals(
                context.getResources().getConfiguration().getLocales().get(0).getLanguage()));
    }

    /** User-visible explanation for {@link Result#crossVolume}. */
    public static String crossVolumeNote(boolean japanese) {
        return japanese ? "SD カードとの間の移動は次の版で対応します"
                : "Moves to/from the SD card are supported in the next version";
    }

    /** Resolves MediaStore ids to file paths. Blocking: call off the UI thread. */
    public static List<String> pathsOf(Context context, long[] ids) {
        List<String> paths = new ArrayList<>();
        for (int from = 0; from < ids.length; from += CHUNK) {
            int to = Math.min(ids.length, from + CHUNK);
            String[] args = new String[to - from];
            for (int i = from; i < to; i++) args[i - from] = Long.toString(ids[i]);
            String selection = "_id IN (" + String.join(",", Collections.nCopies(args.length, "?")) + ")";
            try (Cursor cursor = context.getContentResolver().query(FILES, new String[]{"_data"},
                    selection, args, null)) {
                if (cursor != null) while (cursor.moveToNext()) {
                    String data = cursor.getString(0);
                    if (data != null) paths.add(data);
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
        Result result = new Result();
        List<String> oldPaths = new ArrayList<>();
        List<String> newPaths = new ArrayList<>();
        for (String path : sources) {
            try {
                File source = new File(path);
                Path from = source.toPath();
                if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source is missing");
                File parent = source.getParentFile();
                // Case-insensitive on purpose: FAT / FUSE treat names that differ in case as the same.
                if (parent != null && parent.getCanonicalPath().toLowerCase(Locale.ROOT)
                        .equals(destination.getCanonicalPath().toLowerCase(Locale.ROOT))) {
                    result.skipped++;
                    continue;
                }
                boolean directory = Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS);
                List<String> childPaths = Collections.emptyList();
                if (directory) {
                    File canonical = source.getCanonicalFile();
                    if (FolderTabContext.protectedPath(canonical, FolderTabContext.roots(context), true))
                        throw new IOException("Protected folder cannot be moved: " + canonical);
                    // Case-insensitive on purpose: FAT / FUSE treat names that differ in case as the same.
                    String src = canonical.getPath().toLowerCase(Locale.ROOT) + File.separator;
                    String dst = destination.getCanonicalPath().toLowerCase(Locale.ROOT) + File.separator;
                    if (dst.startsWith(src)) throw new IOException("Cannot move a folder into itself or below itself");
                    // Read before the move: these are the paths whose MediaStore rows become stale.
                    childPaths = FolderTrash.mediaPaths(context, source);
                }
                File target = transfer(source, destination, directory);
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
            } catch (CrossVolumeException e) {
                result.failed++;
                result.crossVolume++;
                DiagLog.e(AREA, "Not moved (other volume; moves to/from the SD card are supported in the next version): "
                        + path, e);
            } catch (Throwable t) {
                result.failed++;
                DiagLog.e(AREA, "Failed to move " + path, t);
            }
        }
        syncMediaStore(context, oldPaths, newPaths);
        DiagLog.i(AREA, "Move to " + destination + ": moved=" + result.moved + ", skipped=" + result.skipped
                + ", failed=" + result.failed + " (other volume=" + result.crossVolume + ")");
        return result;
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
     * folder). Throws {@link CrossVolumeException} on EXDEV with the source untouched.
     */
    private static File transfer(File source, File destination, boolean directory) throws IOException {
        String name = source.getName();
        int dot = directory ? -1 : name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 0; n < MAX_NAME_ATTEMPTS; n++) {
            File target = new File(destination, n == 0 ? name : base + " (" + n + ")" + extension);
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
                if (e.errno == OsConstants.EXDEV) {
                    throw new CrossVolumeException("Source and destination are on different volumes: "
                            + source + " -> " + target, e);
                }
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
        }
        throw new IOException("No free name found in " + destination + " for " + name);
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
