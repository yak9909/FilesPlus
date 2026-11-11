package app.filesplus.extension.smb;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * File-system view of one saved connection. Paths are share-relative with '/' separators, normalized by
 * {@link SmbPath} ("" is the root); a path that escapes the share throws IllegalArgumentException. Every
 * method is blocking (background threads only) and retries once on a fresh connection when the session died.
 * Failures are IOExceptions or smbj runtime exceptions; show them with {@link SmbErrors#describe}.
 */
public final class SmbVfs {
    private static final long CACHE_MS = 30_000L;
    private static final int MAX_DELETE_DEPTH = 64;
    private static final int DIRECTORY = (int) FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue();
    private static final int REPARSE = (int) FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue();
    private static final Map<String, SmbVfs> SHARED = new HashMap<>();

    private final SmbConnection connection;
    private final String passwordOverride;
    private final Map<String, Cached> cache = new HashMap<>();

    private interface Op<T> {
        T run(DiskShare share) throws IOException;
    }

    private static final class Cached {
        final long time;
        final List<SmbEntry> entries;

        Cached(long time, List<SmbEntry> entries) {
            this.time = time;
            this.entries = entries;
        }
    }

    /** Throwaway use with explicit (possibly unsaved) values; the caller closes the pool entry afterwards. */
    public SmbVfs(SmbConnection connection, String passwordOverride) {
        this.connection = connection;
        this.passwordOverride = passwordOverride;
    }

    /** Shared instance per saved connection, so the listing cache is shared between callers. */
    public static SmbVfs of(SmbConnection c) {
        synchronized (SHARED) {
            SmbVfs vfs = SHARED.get(c.id);
            if (vfs == null || !vfs.connection.targetKey().equals(c.targetKey())) {
                vfs = new SmbVfs(c, null);
                SHARED.put(c.id, vfs);
            }
            return vfs;
        }
    }

    /** Forgets everything held for a connection id (cache, session). Safe on any thread. */
    public static void invalidate(String id) {
        synchronized (SHARED) {
            SHARED.remove(id);
        }
        SmbSessionPool.close(id);
    }

    public List<SmbEntry> list(String path) throws IOException {
        String dir = SmbPath.normalize(path);
        synchronized (cache) {
            Cached hit = cache.get(dir);
            if (hit != null && System.currentTimeMillis() - hit.time < CACHE_MS) return hit.entries;
        }
        List<SmbEntry> entries = call(share -> {
            List<SmbEntry> out = new ArrayList<>();
            for (FileIdBothDirectoryInformation info : share.list(SmbPath.toSmb(dir))) {
                String name = info.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;
                out.add(new SmbEntry(name, (info.getFileAttributes() & DIRECTORY) != 0, info.getEndOfFile(),
                        info.getLastWriteTime().toEpochMillis()));
            }
            Collections.sort(out, ORDER);
            return Collections.unmodifiableList(out);
        });
        synchronized (cache) {
            cache.put(dir, new Cached(System.currentTimeMillis(), entries));
        }
        return entries;
    }

    private static final Comparator<SmbEntry> ORDER = (a, b) -> {
        if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
        return a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
    };

    public SmbEntry stat(String path) throws IOException {
        String p = SmbPath.normalize(path);
        if (p.isEmpty()) return new SmbEntry("", true, 0, 0);
        return call(share -> {
            FileAllInformation info = share.getFileInformation(SmbPath.toSmb(p));
            return new SmbEntry(SmbPath.name(p), info.getStandardInformation().isDirectory(),
                    info.getStandardInformation().getEndOfFile(),
                    info.getBasicInformation().getLastWriteTime().toEpochMillis());
        });
    }

    public long size(String path) throws IOException {
        return stat(path).size;
    }

    /** Opens a file for sequential reading starting at the byte offset (an offset read, nothing is skipped over the wire). */
    public InputStream openRead(String path, long offset) throws IOException {
        if (offset < 0) throw new IllegalArgumentException("Negative offset");
        String p = SmbPath.normalize(path);
        if (p.isEmpty()) throw new IllegalArgumentException("Not a file");
        // The share stays acquired until the stream is closed, so the idle sweep cannot close it under a reader.
        for (int attempt = 0; ; attempt++) {
            DiskShare share = SmbSessionPool.acquire(connection, passwordOverride);
            try {
                File file = share.openFile(SmbPath.toSmb(p), EnumSet.of(AccessMask.GENERIC_READ), null,
                        SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
                return new ReadStream(connection.id, share, file, offset);
            } catch (RuntimeException e) {
                SmbSessionPool.release(connection.id, share);
                if (attempt == 0 && SmbErrors.isConnectionBroken(e)) {
                    // Only drop the session this call used; another thread may have reconnected already.
                    SmbSessionPool.invalidate(connection.id, share);
                    continue;
                }
                throw e;
            }
        }
    }

    /** Creates the folder and any missing parents; existing folders are fine. */
    public void mkdirs(String path) throws IOException {
        String p = SmbPath.normalize(path);
        if (p.isEmpty()) return;
        try {
            call(share -> {
                String current = "";
                for (String segment : p.split("/")) {
                    current = current.isEmpty() ? segment : current + "/" + segment;
                    String smb = SmbPath.toSmb(current);
                    if (!share.folderExists(smb)) share.mkdir(smb);
                }
                return null;
            });
        } finally {
            dropCache();
        }
    }

    /** Deletes a file, or a folder (recursively when asked: files first, then folders; links are not followed). */
    public void delete(String path, boolean recursive) throws IOException {
        String p = SmbPath.normalize(path);
        if (p.isEmpty()) throw new IllegalArgumentException("Cannot delete the share root");
        try {
            call(share -> {
                deleteTree(share, p, recursive, 0);
                return null;
            });
        } finally {
            dropCache();
        }
    }

    /** Renames or moves inside the share. Returns false when the destination already exists. */
    public boolean rename(String from, String to) throws IOException {
        String source = SmbPath.normalize(from);
        String target = SmbPath.normalize(to);
        if (source.isEmpty() || target.isEmpty()) throw new IllegalArgumentException("Cannot rename the share root");
        try {
            return call(share -> {
                try (DiskEntry entry = share.open(SmbPath.toSmb(source), EnumSet.of(AccessMask.DELETE), null,
                        SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
                    entry.rename(SmbPath.toSmb(target), false);
                    return true;
                } catch (SMBApiException e) {
                    if (e.getStatus() == NtStatus.STATUS_OBJECT_NAME_COLLISION) return false;
                    throw e;
                }
            });
        } finally {
            dropCache();
        }
    }

    /**
     * Never follows links: every open uses FILE_OPEN_REPARSE_POINT, so a symlink or junction is deleted itself
     * and never recursed into. Links are recognized by the REPARSE attribute of the opened entry (top level) or
     * of the directory listing (children). Samba with "follow symlinks" resolves links on the server and hides
     * them, so a link cannot be detected there (and its target would be treated as a normal entry).
     */
    private static void deleteTree(DiskShare share, String path, boolean recursive, int depth) throws IOException {
        String smb = SmbPath.toSmb(path);
        try (DiskEntry entry = openNoFollow(share, smb)) {
            FileAllInformation info = entry.getFileInformation();
            boolean directory = info.getStandardInformation().isDirectory();
            boolean link = (info.getBasicInformation().getFileAttributes() & REPARSE) != 0;
            if (!directory || link || !recursive) {
                // A non-empty folder fails here (when the handle closes) with STATUS_DIRECTORY_NOT_EMPTY.
                entry.deleteOnClose();
                return;
            }
        }
        if (depth >= MAX_DELETE_DEPTH) throw new IOException("Folder is nested too deeply to delete");
        List<FileIdBothDirectoryInformation> children = new ArrayList<>();
        for (FileIdBothDirectoryInformation child : share.list(smb)) {
            if (!".".equals(child.getFileName()) && !"..".equals(child.getFileName())) children.add(child);
        }
        // Files and links first, then the real sub-folders, then the folder itself.
        for (FileIdBothDirectoryInformation child : children) {
            long attributes = child.getFileAttributes();
            if ((attributes & REPARSE) != 0 || (attributes & DIRECTORY) == 0) removeNoFollow(share, smb + "\\" + child.getFileName());
        }
        for (FileIdBothDirectoryInformation child : children) {
            long attributes = child.getFileAttributes();
            if ((attributes & REPARSE) == 0 && (attributes & DIRECTORY) != 0) {
                deleteTree(share, path + "/" + child.getFileName(), true, depth + 1);
            }
        }
        removeNoFollow(share, smb);
    }

    private static DiskEntry openNoFollow(DiskShare share, String smbPath) {
        return share.open(smbPath, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT));
    }

    private static void removeNoFollow(DiskShare share, String smbPath) throws IOException {
        try (DiskEntry entry = openNoFollow(share, smbPath)) {
            entry.deleteOnClose();
        }
    }

    private void dropCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    /** Runs one operation, retrying once on a new connection when the old session turned out to be dead. */
    private <T> T call(Op<T> op) throws IOException {
        for (int attempt = 0; ; attempt++) {
            DiskShare share = SmbSessionPool.acquire(connection, passwordOverride);
            try {
                return op.run(share);
            } catch (IOException | RuntimeException e) {
                if (attempt == 0 && SmbErrors.isConnectionBroken(e)) {
                    SmbSessionPool.invalidate(connection.id, share);
                    continue;
                }
                throw e;
            } finally {
                SmbSessionPool.release(connection.id, share);
            }
        }
    }

    /** Reads with explicit file offsets and releases the share when closed. */
    private static final class ReadStream extends InputStream {
        private final String id;
        private final DiskShare share;
        private final File file;
        private long position;
        private boolean closed;

        ReadStream(String id, DiskShare share, File file, long position) {
            this.id = id;
            this.share = share;
            this.file = file;
            this.position = position;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (closed) throw new IOException("Stream closed");
            if (length == 0) return 0;
            try {
                int count = file.read(buffer, position, offset, length);
                if (count <= 0) return -1;
                position += count;
                return count;
            } catch (RuntimeException e) {
                if (SmbErrors.isConnectionBroken(e)) SmbSessionPool.invalidate(id, share);
                // Stream callers only expect IOException.
                throw new IOException(SmbErrors.describe(e), e);
            }
        }

        @Override
        public long skip(long count) {
            if (count <= 0) return 0;
            position += count;
            return count;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                file.close();
            } catch (Throwable ignored) {
                // The share may already be gone; nothing to report.
            }
            SmbSessionPool.release(id, share);
        }
    }
}
