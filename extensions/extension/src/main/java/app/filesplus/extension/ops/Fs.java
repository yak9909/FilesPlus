package app.filesplus.extension.ops;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.attribute.FileTime;
import java.util.List;

/** Paths are opaque to the copy engine except when appending a child name. */
public interface Fs {
    enum Type { FILE, DIRECTORY, LINK, OTHER }

    final class Stat {
        public final Type type;
        public final long size;
        public final FileTime mtime;
        public final long inode;
        public final long device;

        public Stat(Type type, long size, FileTime mtime, long inode, long device) {
            this.type = type;
            this.size = size;
            this.mtime = mtime;
            this.inode = inode;
            this.device = device;
        }

        public boolean sameFile(Stat other) {
            return other != null && type == other.type && inode == other.inode && device == other.device;
        }

        public boolean sameFileAndSize(Stat other) {
            return sameFile(other) && size == other.size;
        }

        public boolean unchanged(Stat other) {
            return sameFileAndSize(other) && mtime.equals(other.mtime);
        }
    }

    /** The adapter can preserve a stable identity for an open file until the handle is closed. */
    interface Writer extends Closeable {
        Stat stat() throws IOException;
        void write(byte[] buffer, int count) throws IOException;
        void sync() throws IOException;
    }

    /** Holds an open directory. Use its path for child access while the handle is open. */
    interface Directory extends Closeable {
        String path();
    }

    /** Opens the expected directory without following a link at its name. */
    Directory openDirectory(String path, Stat expected) throws IOException;

    /** Creates a directory exclusively and returns its identity. */
    Stat mkdir(String path) throws IOException;
    void rmdir(String path) throws IOException;

    /** No link following. Null only for a missing path. */
    Stat lstat(String path) throws IOException;

    /** Opens a regular file without following links, checking identity, size and optionally mtime. */
    InputStream openRead(String path, Stat expected, boolean checkMtime) throws IOException;

    /** Creates and opens a new regular file atomically. Throws FileAlreadyExistsException on a clash. */
    Writer createExclusive(String path) throws IOException;

    String child(String folder, String name);
    List<String> list(String folder) throws IOException;
    List<String> list(String folder, int limit) throws IOException;
    long freeSpace(String folder) throws IOException;
    void rename(String from, String to) throws IOException;
    void syncDirectory(String folder) throws IOException;
    /** Removes a non-directory entry itself, without following links. */
    void unlink(String path) throws IOException;
    void setMtime(String path, FileTime mtime) throws IOException;

    final class NoSpace extends IOException {
        public NoSpace(Throwable cause) { super("No space left on destination", cause); }
    }

    final class SourceChanged extends IOException {
        public SourceChanged(String path) { super("File changed: " + path); }
    }
}
