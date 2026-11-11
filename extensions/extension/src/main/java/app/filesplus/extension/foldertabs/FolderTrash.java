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
 * FilesPlus trash (DESIGN section 10.6): a folder is moved with ONE same-volume rename to
 * {@code <volume root>/.FilesPlus/trash/<opId>/<folder name>}. Nothing is ever deleted here and
 * there is no copy fallback: if the rename fails the folder stays exactly where it was.
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
        for (int attempt = 0; attempt < 20 && opDir == null; attempt++) {
            String candidate = timestamp("yyyyMMdd-HHmmss") + "-" + String.format(Locale.ROOT, "%06x", RANDOM.nextInt(1 << 24));
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

        File trashed = new File(opDir, folder.getName());
        File manifest = new File(opDir, "manifest.json");
        File manifestTmp = new File(opDir, "manifest.json.tmp");
        boolean moved = false;
        try {
            // Written before the rename so a trashed folder is never left without its origin.
            writeManifest(manifestTmp, manifest, opId, folder, trashed, fileCount, totalBytes);
            if (trashed.exists()) throw new Failure("Trash target already exists: " + trashed, 0, null);
            try {
                // opDir is new and empty, so rename(2) cannot replace anything.
                Os.rename(folder.getPath(), trashed.getPath());
                moved = true;
            } catch (ErrnoException e) {
                throw new Failure("rename failed (" + OsConstants.errnoName(e.errno) + "): " + folder
                        + " -> " + trashed, e.errno, e);
            }
        } finally {
            if (!moved) {
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

    private static void writeManifest(File tmp, File target, String opId, File original, File trashed,
                                      long fileCount, long totalBytes) throws IOException {
        long now = System.currentTimeMillis();
        try {
            JSONObject json = new JSONObject();
            json.put("version", 1);
            json.put("opId", opId);
            json.put("kind", "folder");
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
