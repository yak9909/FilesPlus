package app.filesplus.extension.ops;

import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Android local storage operations. An exclusive create keeps the original descriptor open for writing. */
public final class LocalFs implements Fs {
    private static final int OPEN_FLAGS = OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW;

    @Override public Stat lstat(String path) throws IOException {
        try {
            return stat(Os.lstat(path));
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return null;
            throw io(e, path);
        }
    }

    private static Stat stat(StructStat value) {
        Type type = OsConstants.S_ISREG(value.st_mode) ? Type.FILE
                : OsConstants.S_ISDIR(value.st_mode) ? Type.DIRECTORY
                : OsConstants.S_ISLNK(value.st_mode) ? Type.LINK : Type.OTHER;
        return new Stat(type, value.st_size,
                FileTime.from(Instant.ofEpochSecond(value.st_mtim.tv_sec, value.st_mtim.tv_nsec)),
                value.st_ino, value.st_dev);
    }

    @Override public Directory openDirectory(String path, Stat expected) throws IOException {
        FileDescriptor fd;
        try {
            fd = Os.open(path, OPEN_FLAGS | OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
        } catch (ErrnoException e) {
            throw io(e, path);
        }
        try {
            Stat opened = stat(Os.fstat(fd));
            if (opened.type != Type.DIRECTORY || !expected.sameFile(opened)) {
                throw new SourceChanged(path);
            }
            // The public SDK has no openat. A held descriptor anchors child paths to this directory.
            ParcelFileDescriptor handle = ParcelFileDescriptor.dup(fd);
            return new Directory() {
                @Override public String path() { return "/proc/self/fd/" + handle.getFd() + "/."; }
                @Override public void close() throws IOException { handle.close(); }
            };
        } catch (ErrnoException e) {
            throw io(e, path);
        } finally {
            close(fd, path);
        }
    }

    @Override public Stat mkdir(String path) throws IOException {
        try {
            Os.mkdir(path, 0777);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EEXIST) throw new FileAlreadyExistsException(path);
            throw io(e, path);
        }
        Stat created = lstat(path);
        if (created == null || created.type != Type.DIRECTORY) throw new SourceChanged(path);
        return created;
    }

    @Override public void rmdir(String path) throws IOException {
        try {
            // The trailing slash makes remove act only on a directory entry.
            Os.remove(path + "/");
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return;
            throw io(e, path);
        }
    }

    @Override public InputStream openRead(String path, Stat expected, boolean checkMtime) throws IOException {
        FileDescriptor fd;
        try {
            fd = Os.open(path, OPEN_FLAGS | OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ELOOP) throw new SourceChanged(path);
            throw io(e, path);
        }
        boolean ready = false;
        try {
            Stat opened = stat(Os.fstat(fd));
            if (!(checkMtime ? expected.unchanged(opened) : expected.sameFileAndSize(opened))) {
                throw new SourceChanged(path);
            }
            ready = true;
        } catch (ErrnoException e) {
            throw io(e, path);
        } finally {
            if (!ready) close(fd, path);
        }
        return new InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override public int read(byte[] buffer, int offset, int count) throws IOException {
                if (count == 0) return 0;
                try {
                    int read = Os.read(fd, buffer, offset, count);
                    return read == 0 ? -1 : read;
                } catch (ErrnoException e) {
                    throw io(e, path);
                }
            }

            @Override public void close() throws IOException { LocalFs.close(fd, path); }
        };
    }

    @Override public Writer createExclusive(String path) throws IOException {
        FileDescriptor fd;
        try {
            fd = Os.open(path, OPEN_FLAGS | OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL, 0666);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EEXIST) throw new FileAlreadyExistsException(path);
            throw io(e, path);
        }
        return new Writer() {
            @Override public Stat stat() throws IOException {
                try {
                    return LocalFs.stat(Os.fstat(fd));
                } catch (ErrnoException e) {
                    throw io(e, path);
                }
            }

            @Override public void write(byte[] buffer, int count) throws IOException {
                try {
                    int offset = 0;
                    while (offset < count) {
                        int written = Os.write(fd, buffer, offset, count - offset);
                        if (written <= 0) throw new IOException("No write progress: " + path);
                        offset += written;
                    }
                } catch (ErrnoException e) {
                    throw io(e, path);
                }
            }

            @Override public void sync() throws IOException {
                try {
                    Os.fsync(fd);
                } catch (ErrnoException e) {
                    throw io(e, path);
                }
            }

            @Override public void close() throws IOException { LocalFs.close(fd, path); }
        };
    }

    private static void close(FileDescriptor fd, String path) throws IOException {
        try {
            Os.close(fd);
        } catch (ErrnoException e) {
            throw io(e, path);
        }
    }

    @Override public String child(String folder, String name) { return new File(folder, name).getPath(); }

    @Override public List<String> list(String folder) throws IOException {
        return list(folder, Integer.MAX_VALUE);
    }

    @Override public List<String> list(String folder, int limit) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(new File(folder).toPath())) {
            for (Path entry : entries) {
                names.add(entry.getFileName().toString());
                if (names.size() == limit) break;
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        return names;
    }

    @Override public long freeSpace(String folder) throws IOException {
        try {
            return new StatFs(folder).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot check free space: " + folder, e);
        }
    }

    @Override public void rename(String from, String to) throws IOException {
        try {
            Os.rename(from, to);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOTEMPTY) throw new DirectoryNotEmptyException(to);
            if (e.errno == OsConstants.EEXIST) throw new FileAlreadyExistsException(to);
            throw io(e, from + " -> " + to);
        }
    }

    @Override public void syncDirectory(String folder) throws IOException {
        FileDescriptor fd;
        try {
            // Android's public OsConstants omits O_DIRECTORY. Check the open descriptor before syncing.
            fd = Os.open(folder, OPEN_FLAGS | OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
        } catch (ErrnoException e) {
            throw io(e, folder);
        }
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw new IOException("Expected a directory: " + folder);
            Os.fsync(fd);
        } catch (ErrnoException e) {
            throw io(e, folder);
        } finally {
            close(fd, folder);
        }
    }

    @Override public void unlink(String path) throws IOException {
        Path file = new File(path).toPath().toAbsolutePath();
        try (DirectoryStream<Path> parent = Files.newDirectoryStream(file.getParent())) {
            Stat current = lstat(path);
            if (current == null) return;
            if (current.type == Type.DIRECTORY) throw new IOException("Expected a non-directory entry: " + path);
            if (parent instanceof SecureDirectoryStream) {
                try {
                    ((SecureDirectoryStream<Path>) parent).deleteFile(file.getFileName());
                } catch (NoSuchFileException ignored) {
                    // An already absent file needs no cleanup.
                }
            } else {
                Files.deleteIfExists(file);
            }
        }
    }

    @Override public void setMtime(String path, FileTime mtime) throws IOException {
        Files.getFileAttributeView(new File(path).toPath(), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .setTimes(mtime, null, null);
    }

    private static IOException io(ErrnoException error, String path) {
        if (error.errno == OsConstants.ENOSPC || error.errno == OsConstants.EDQUOT) return new NoSpace(error);
        return new IOException(path + ": " + error, error);
    }
}
