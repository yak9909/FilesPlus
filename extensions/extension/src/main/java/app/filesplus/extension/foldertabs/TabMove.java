package app.filesplus.extension.foldertabs;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;

import app.filesplus.extension.DiagLog;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** File operations for "move selected files to the current folder tab". No app types, no UI. */
final class TabMove {
    private static final String AREA = "foldertabs";
    private static final Uri FILES = MediaStore.Files.getContentUri("external");
    private static final int CHUNK = 400;

    static final class Result {
        int moved;
        int skipped;
        int failed;
    }

    private TabMove() {}

    /** Resolves MediaStore ids to file paths. Blocking: call off the UI thread. */
    static List<String> pathsOf(Context context, long[] ids) {
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

    /** Moves every source into the destination folder, keeping both on a name clash. Blocking. */
    static Result move(Context context, List<String> sources, File destination) {
        Result result = new Result();
        List<String> oldPaths = new ArrayList<>();
        List<String> newPaths = new ArrayList<>();
        for (String path : sources) {
            try {
                File source = new File(path);
                Path from = source.toPath();
                if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source is missing");
                File parent = source.getParentFile();
                if (parent != null && parent.getCanonicalFile().equals(destination.getCanonicalFile())) {
                    result.skipped++;
                    continue;
                }
                boolean directory = Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS);
                if (directory && (destination.getCanonicalPath() + File.separator)
                        .startsWith(source.getCanonicalPath() + File.separator)) {
                    throw new IOException("Cannot move a folder into itself");
                }
                File target = uniqueTarget(destination, source.getName(), directory);
                transfer(from, target.toPath(), directory);
                result.moved++;
                oldPaths.add(source.getPath());
                newPaths.add(target.getPath());
                if (!target.getName().equals(source.getName()))
                    DiagLog.i(AREA, "Renamed on conflict: " + source.getName() + " -> " + target.getName());
            } catch (Throwable t) {
                result.failed++;
                DiagLog.e(AREA, "Failed to move " + path, t);
            }
        }
        syncMediaStore(context, oldPaths, newPaths);
        DiagLog.i(AREA, "Move to " + destination + ": moved=" + result.moved + ", skipped=" + result.skipped
                + ", failed=" + result.failed);
        return result;
    }

    private static File uniqueTarget(File folder, String name, boolean directory) {
        File target = new File(folder, name);
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return target;
        int dot = directory ? -1 : name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; ; n++) {
            target = new File(folder, base + " (" + n + ")" + extension);
            if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return target;
        }
    }

    /** Never replaces an existing target: Files.move without options fails if it appeared meanwhile. */
    private static void transfer(Path from, Path to, boolean directory) throws IOException {
        try {
            Files.move(from, to);
            return;
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (IOException e) {
            if (!directory) throw e;
            DiagLog.i(AREA, "Direct folder move failed (" + e.getClass().getSimpleName() + "), copying instead");
        }
        try {
            copyTree(from, to);
        } catch (IOException | RuntimeException e) {
            try { deleteTree(to); } catch (IOException cleanup) { DiagLog.e(AREA, "Cannot clean partial copy " + to, cleanup); }
            throw e;
        }
        deleteTree(from);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Drops the old rows (including rows below moved folders) and scans the new paths. */
    private static void syncMediaStore(Context context, List<String> oldPaths, List<String> newPaths) {
        if (oldPaths.isEmpty()) return;
        for (String path : oldPaths) {
            try {
                context.getContentResolver().delete(FILES,
                        "_data = ? OR substr(_data, 1, ?) = ?",
                        new String[]{path, Integer.toString(path.length() + 1), path + File.separator});
            } catch (Throwable t) {
                DiagLog.e(AREA, "Failed to remove old MediaStore rows for " + path, t);
            }
        }
        try {
            MediaScannerConnection.scanFile(context, newPaths.toArray(new String[0]), null, null);
        } catch (Throwable t) {
            DiagLog.e(AREA, "Failed to scan moved files", t);
        }
    }
}
