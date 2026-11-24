package app.filesplus.extension.foldertabs;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ops.Fs;
import app.filesplus.extension.ops.LocalFs;
import app.filesplus.extension.ops.VerifiedCopy;
import app.filesplus.extension.ops.VerifiedFolderMove;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * FilesPlus trash (DESIGN section 10.6): a file or folder moves with one same-volume rename to
 * {@code <volume root>/.FilesPlus/trash/<opId>/}. Its manifest records the original and trash paths.
 */
final class FolderTrash {
    private static final String AREA = "foldertabs";
    private static final Uri FILES = MediaStore.Files.getContentUri("external");
    static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX_MEDIA_PATHS = 20000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private FolderTrash() {}

    /** A failed move. Nothing was changed apart from empty bookkeeping folders. */
    static final class Failure extends IOException {
        /** errno of the failed rename, or 0 when the failure was not the rename itself. */
        final int errno;

        Failure(String message, int errno, Throwable cause) {
            super(message, cause);
            this.errno = errno;
        }

        boolean crossVolume() { return errno == OsConstants.EXDEV; }
    }

    static final class Result {
        File opDir;
        File trashed;
    }

    /** {@code <volume root>/.FilesPlus/trash}, shown in the confirmation dialog. */
    static File trashRoot(File volumeRoot) {
        return new File(new File(volumeRoot, ".FilesPlus"), "trash");
    }

    static Result moveToTrash(File folder, File volumeRoot, long fileCount, long totalBytes) throws IOException {
        return moveToTrash(folder, volumeRoot, fileCount, totalBytes, null, null, null);
    }

    static Result moveFileToTrash(File file, File volumeRoot, String opId, Fs.Stat expected) throws IOException {
        return moveToTrash(file, volumeRoot, 1, expected.size, opId, expected, null);
    }

    static Result moveToTrash(File folder, File volumeRoot, long fileCount, long totalBytes, String opId,
                              VerifiedFolderMove.SourceCheck check) throws IOException {
        return moveToTrash(folder, volumeRoot, fileCount, totalBytes, opId, null, check);
    }

    private static Result moveToTrash(File source, File volumeRoot, long fileCount, long totalBytes,
                                      String copyOpId, Fs.Stat expected, VerifiedFolderMove.SourceCheck folderCheck)
            throws IOException {
        File base = new File(volumeRoot, ".FilesPlus");
        File trash = trashRoot(volumeRoot);
        try {
            if (Files.isSymbolicLink(base.toPath())) throw new Failure("Trash folder is a link: " + base, 0, null);
            Files.createDirectories(base.toPath());
            if (!Files.isDirectory(base.toPath(), LinkOption.NOFOLLOW_LINKS))
                throw new Failure("Trash folder is not a folder: " + base, 0, null);
            // Keep the media scanner out of the trash before anything is moved in.
            try { Files.createFile(new File(base, ".nomedia").toPath()); }
            catch (FileAlreadyExistsException ignored) { }
            if (Files.isSymbolicLink(trash.toPath())) throw new Failure("Trash folder is a link: " + trash, 0, null);
            Files.createDirectories(trash.toPath());
        } catch (Failure f) {
            throw f;
        } catch (IOException e) {
            throw new Failure("Cannot prepare the trash folder " + base + ": " + e, 0, e);
        }

        // Reserve a unique operation folder atomically: createDirectory fails if it already exists.
        File opDir = null;
        String opId = null;
        int attempts = copyOpId == null ? 20 : 1;
        for (int attempt = 0; attempt < attempts && opDir == null; attempt++) {
            String candidate = copyOpId != null ? copyOpId
                    : timestamp("yyyyMMdd-HHmmss") + "-" + String.format(Locale.ROOT, "%06x", RANDOM.nextInt(1 << 24));
            File dir = new File(trash, candidate);
            try {
                Files.createDirectory(dir.toPath());
                opDir = dir;
                opId = candidate;
            } catch (FileAlreadyExistsException ignored) {
                // Try another id; never reuse an existing operation folder.
            } catch (IOException e) {
                throw new Failure("Cannot create " + dir + ": " + e, 0, e);
            }
        }
        if (opDir == null) throw new Failure("Cannot reserve a unique trash folder in " + trash, 0, null);

        String name = source.getName();
        if (copyOpId != null && ("manifest.json".equalsIgnoreCase(name) || "manifest.json.tmp".equalsIgnoreCase(name))) {
            name = VerifiedCopy.candidate(name, folderCheck != null, 1);
        }
        File trashed = new File(opDir, name);
        File manifest = new File(opDir, "manifest.json");
        File manifestTmp = new File(opDir, "manifest.json.tmp");
        boolean moved = false;
        LocalFs fs = new LocalFs();
        Fs.Stat reservation = null;
        try {
            // Record the original path before renaming the source.
            writeManifest(manifestTmp, manifest, opId, source, trashed, fileCount, totalBytes, expected == null);
            if (folderCheck != null) {
                reservation = fs.mkdir(trashed.getPath());
                folderCheck.check();
                if (!emptyReservation(fs, trashed.getPath(), reservation)) {
                    throw new IOException("Trash folder reservation changed: " + trashed);
                }
            } else if (expected == null) {
                if (trashed.exists()) throw new Failure("Trash target already exists: " + trashed, 0, null);
            } else {
                try (Fs.Writer placeholder = fs.createExclusive(trashed.getPath())) {
                    reservation = placeholder.stat();
                }
                if (!expected.unchanged(fs.lstat(source.getPath()))) throw new Fs.SourceChanged(source.getPath());
                Fs.Stat current = fs.lstat(trashed.getPath());
                if (!reservation.sameFile(current) || current.size != 0) {
                    throw new IOException("Trash reservation changed: " + trashed);
                }
            }
            try {
                Os.rename(source.getPath(), trashed.getPath());
                moved = true;
            } catch (ErrnoException e) {
                throw new Failure("rename failed (" + OsConstants.errnoName(e.errno) + "): " + source
                        + " -> " + trashed, e.errno, e);
            }
        } finally {
            if (!moved) {
                if (reservation != null) {
                    try {
                        Fs.Stat current = fs.lstat(trashed.getPath());
                        if (reservation.type == Fs.Type.DIRECTORY) {
                            if (emptyReservation(fs, trashed.getPath(), reservation)) fs.rmdir(trashed.getPath());
                        } else if (reservation.sameFile(current) && current.size == 0) {
                            fs.unlink(trashed.getPath());
                        }
                    } catch (IOException e) {
                        DiagLog.e(AREA, "Cannot release trash reservation " + trashed, e);
                    }
                }
                // Remove only the empty bookkeeping we created ourselves (no recursion).
                if (manifestTmp.exists() && !manifestTmp.delete()) DiagLog.e(AREA, "Cannot remove " + manifestTmp, null);
                if (manifest.exists() && !manifest.delete()) DiagLog.e(AREA, "Cannot remove " + manifest, null);
                if (!opDir.delete()) DiagLog.e(AREA, "Cannot remove unused trash folder " + opDir, null);
            }
        }
        Result result = new Result();
        result.opDir = opDir;
        result.trashed = trashed;
        return result;
    }

    private static boolean emptyReservation(Fs fs, String path, Fs.Stat reservation) throws IOException {
        if (!reservation.sameFile(fs.lstat(path))) return false;
        try (Fs.Directory directory = fs.openDirectory(path, reservation)) {
            return fs.list(directory.path(), 1).isEmpty() && reservation.sameFile(fs.lstat(path));
        }
    }

    private static void writeManifest(File tmp, File target, String opId, File original, File trashed,
                                      long fileCount, long totalBytes, boolean directory) throws IOException {
        long now = System.currentTimeMillis();
        try {
            JSONObject json = new JSONObject();
            json.put("version", 1);
            json.put("opId", opId);
            json.put("kind", directory ? "folder" : "file");
            json.put("originalPath", original.getPath());
            json.put("name", original.getName());
            json.put("trashedPath", trashed.getPath());
            json.put("fileCount", fileCount);
            json.put("totalBytes", totalBytes);
            json.put("trashedAt", now);
            json.put("trashedAtIso", timestamp(now));
            json.put("expiresAt", now + RETENTION_MS);
            json.put("expiresAtIso", timestamp(now + RETENTION_MS));
            byte[] bytes = json.toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            // The target does not exist (new operation folder), so this replaces nothing.
            Os.rename(tmp.getPath(), target.getPath());
        } catch (ErrnoException e) {
            throw new Failure("Cannot write the trash manifest: " + e, 0, e);
        } catch (IOException e) {
            throw new Failure("Cannot write the trash manifest: " + e, 0, e);
        } catch (org.json.JSONException e) {
            throw new Failure("Cannot build the trash manifest: " + e, 0, e);
        }
    }

    private static String timestamp(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    private static String timestamp(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT);
        return format.format(new Date(millis));
    }

    /**
     * Exact MediaStore paths below {@code folder}, read-only. Call BEFORE the rename: they are the
     * paths whose rows can become stale. They are only ever passed to the media scanner.
     */
    static List<String> mediaPaths(Context context, File folder) {
        List<String> paths = new ArrayList<>();
        String prefix = folder.getPath() + File.separator;
        String escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        try (Cursor cursor = context.getContentResolver().query(FILES, new String[]{"_data"},
                "_data LIKE ? ESCAPE '\\'", new String[]{escaped + "%"}, null)) {
            if (cursor == null) return paths;
            while (cursor.moveToNext() && paths.size() < MAX_MEDIA_PATHS) {
                String data = cursor.getString(0);
                if (data != null && data.startsWith(prefix)) paths.add(data);
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot list MediaStore paths below " + folder, t);
        }
        return paths;
    }

    /**
     * Never uses ContentResolver.delete (on Android 11+ that would delete real files): stale rows
     * are dropped by scanning the old paths (a missing path removes its row), and the trashed folder
     * is scanned so the scanner applies the .nomedia rule to whatever MediaProvider kept.
     * TODO (DESIGN 9 #14): verify on the device that photos disappear from the Files categories.
     */
    static void refreshMediaStore(Context context, File oldFolder, List<String> oldPaths, File trashed) {
        List<String> all = new ArrayList<>(oldPaths);
        all.add(oldFolder.getPath());
        all.add(trashed.getPath());
        try {
            final int batch = 500;
            for (int i = 0; i < all.size(); i += batch) {
                List<String> part = all.subList(i, Math.min(all.size(), i + batch));
                MediaScannerConnection.scanFile(context, part.toArray(new String[0]), null, null);
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Media scan after trashing failed: " + oldFolder, t);
        }
    }
}
