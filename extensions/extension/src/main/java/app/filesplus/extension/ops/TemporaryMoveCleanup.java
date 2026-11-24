package app.filesplus.extension.ops;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/** Recovers only entries named in the persisted temporary-path records. */
public final class TemporaryMoveCleanup {
    private TemporaryMoveCleanup() {}

    /** The caller checks that destination is canonical and on a mounted volume. */
    public static void clean(Fs fs, String destination, Fs.Stat parent, String recorded, boolean directory,
                             Consumer<String> log) throws IOException {
        String name = new File(recorded).getName();
        if (!VerifiedCopy.isPartName(name)) {
            log.accept("Dropped temporary move record with invalid name: " + recorded);
            return;
        }
        if (!recorded.equals(fs.child(destination, name))) {
            log.accept("Dropped temporary move record outside its destination " + destination + ": " + recorded);
            return;
        }
        try (Fs.Directory folder = fs.openDirectory(destination, parent)) {
            String part = fs.child(folder.path(), name);
            Fs.Stat stat = fs.lstat(part);
            if (stat == null) return;
            Fs.Type expected = directory ? Fs.Type.DIRECTORY : Fs.Type.FILE;
            if (stat.type != expected || stat.device != parent.device) {
                throw new IOException("Recorded temporary entry has the wrong type or device: " + recorded);
            }
            remove(fs, part, stat, 0);
        }
    }

    private static void remove(Fs fs, String path, Fs.Stat stat, int depth) throws IOException {
        if (stat.type != Fs.Type.DIRECTORY) {
            if (!stat.sameFile(fs.lstat(path))) throw new IOException("Temporary entry changed: " + path);
            fs.unlink(path);
            return;
        }
        // Created trees are limited to depth 64. Keep the record if anything deeper blocks cleanup.
        if (depth > 64) throw new IOException("Unexpected depth in temporary directory: " + path);
        try (Fs.Directory folder = fs.openDirectory(path, stat)) {
            for (String name : fs.list(folder.path())) {
                String child = fs.child(folder.path(), name);
                Fs.Stat entry = fs.lstat(child);
                if (entry == null) continue;
                if (entry.device != stat.device) throw new IOException("Other device inside temporary directory: " + child);
                remove(fs, child, entry, depth + 1);
            }
        }
        if (!stat.sameFile(fs.lstat(path))) throw new IOException("Temporary directory changed: " + path);
        fs.rmdir(path);
    }
}
