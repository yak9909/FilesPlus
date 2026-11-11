package app.filesplus.extension.smb;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.webkit.MimeTypeMap;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * Read-only DocumentsProvider exposing every saved SMB connection as a storage root, so the system picker
 * (and any SAF client) can browse and open files on the share. Document ids are {@link SmbDocId}. Writing,
 * creating, renaming and deleting are not offered (no document flags). Calls arrive on binder threads, which
 * may block; a call on the main thread is rejected. Failures reach the caller as short FileNotFoundExceptions
 * built by {@link SmbErrors#describe}; no other exception type leaves the provider.
 */
public final class SmbDocumentsProvider extends DocumentsProvider {
    private static final String AREA = "smb";
    private static final String AUTHORITY_SUFFIX = ".smb.documents";
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID};
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};

    @Override
    public boolean onCreate() {
        // The provider can start before any activity of the app.
        try {
            ModKit.init(getContext());
        } catch (Throwable t) {
            DiagLog.e(AREA, "Provider init failed", t);
        }
        return true;
    }

    /** Tells the system picker that the list of roots changed (called after a connection is saved or deleted). */
    public static void notifyRootsChanged() {
        try {
            Context context = ModKit.context();
            if (context == null) return;
            context.getContentResolver().notifyChange(
                    android.provider.DocumentsContract.buildRootsUri(context.getPackageName() + AUTHORITY_SUFFIX), null);
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot notify roots change", t);
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(ROOT_COLUMNS);
        try {
            Context context = getContext();
            int icon = context == null ? 0 : context.getApplicationInfo().icon;
            for (SmbConnection c : SmbConnectionStore.all()) {
                cursor.newRow()
                        .add(Root.COLUMN_ROOT_ID, c.id)
                        .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
                        .add(Root.COLUMN_ICON, icon)
                        .add(Root.COLUMN_TITLE, c.label())
                        .add(Root.COLUMN_SUMMARY, c.hostAndShare())
                        .add(Root.COLUMN_DOCUMENT_ID, SmbDocId.root(c.id));
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "queryRoots failed", t);
        }
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        try {
            SmbDocId id = SmbDocId.decode(documentId);
            SmbConnection c = connection(id);
            MatrixCursor cursor = new MatrixCursor(DOCUMENT_COLUMNS);
            if (id.isRoot()) {
                addRow(cursor, SmbDocId.root(c.id), c.label(), true, 0, 0);
            } else {
                requireBackgroundThread();
                SmbEntry entry = SmbVfs.of(c).stat(id.path);
                addRow(cursor, SmbDocId.encode(c.id, id.path), entry.name, entry.isDir, entry.size, entry.mtimeMillis);
            }
            return cursor;
        } catch (Throwable t) {
            throw fail("queryDocument", t);
        }
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        try {
            requireBackgroundThread();
            SmbDocId parent = SmbDocId.decode(parentDocumentId);
            SmbConnection c = connection(parent);
            List<SmbEntry> entries = SmbVfs.of(c).list(parent.path);
            MatrixCursor cursor = new MatrixCursor(DOCUMENT_COLUMNS, entries.size());
            for (SmbEntry e : entries) {
                addRow(cursor, SmbDocId.encode(c.id, SmbPath.join(parent.path, e.name)), e.name,
                        e.isDir, e.size, e.mtimeMillis);
            }
            return cursor;
        } catch (Throwable t) {
            throw fail("queryChildDocuments", t);
        }
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            return SmbDocId.decode(parentDocumentId).isAncestorOf(SmbDocId.decode(documentId));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        HandlerThread thread = null;
        try {
            requireBackgroundThread();
            if (Build.VERSION.SDK_INT < 26) throw new FileNotFoundException("Android 8 or later is required");
            if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
                throw new FileNotFoundException("SMB documents are read-only");
            }
            SmbDocId id = SmbDocId.decode(documentId);
            if (id.isRoot()) throw new FileNotFoundException("Not a file");
            SmbConnection c = connection(id);
            SmbEntry entry = SmbVfs.of(c).stat(id.path);
            if (entry.isDir) throw new FileNotFoundException("Not a file");

            thread = new HandlerThread("filesplus-smb-proxy");
            thread.start();
            StorageManager storage = getContext().getSystemService(StorageManager.class);
            ProxyReader reader = new ProxyReader(SmbVfs.of(c), id.path, entry.size, thread);
            ParcelFileDescriptor fd = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, reader,
                    new Handler(thread.getLooper()));
            thread = null; // Owned by the reader now; it quits the thread in onRelease.
            return fd;
        } catch (Throwable t) {
            if (thread != null) thread.quit();
            throw fail("openDocument", t);
        }
    }

    private static SmbConnection connection(SmbDocId id) throws FileNotFoundException {
        SmbConnection c = SmbConnectionStore.get(id.connectionId);
        if (c == null) throw new FileNotFoundException("Unknown SMB connection");
        return c;
    }

    private static void requireBackgroundThread() throws FileNotFoundException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new FileNotFoundException("SMB documents cannot be read on the main thread");
        }
    }

    /** Logs and converts any failure to the only exception type the provider may throw. */
    private static FileNotFoundException fail(String what, Throwable t) {
        if (t instanceof FileNotFoundException) return (FileNotFoundException) t;
        String message = t instanceof IllegalArgumentException ? "Invalid SMB document" : SmbErrors.describe(t);
        DiagLog.i(AREA, what + " failed: " + message);
        return new FileNotFoundException(message);
    }

    private static void addRow(MatrixCursor cursor, String documentId, String name, boolean dir, long size, long mtime) {
        cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, documentId)
                .add(Document.COLUMN_DISPLAY_NAME, name)
                .add(Document.COLUMN_SIZE, dir ? null : size)
                .add(Document.COLUMN_MIME_TYPE, dir ? Document.MIME_TYPE_DIR : mimeType(name))
                .add(Document.COLUMN_LAST_MODIFIED, mtime > 0 ? mtime : null)
                .add(Document.COLUMN_FLAGS, 0);
    }

    private static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    /** Serves reads of one file to the kernel through SmbVfs; runs on its own handler thread. */
    private static final class ProxyReader extends ProxyFileDescriptorCallback {
        private final SmbVfs vfs;
        private final String path;
        private final long size;
        private final HandlerThread thread;
        private InputStream stream;
        private long position;

        ProxyReader(SmbVfs vfs, String path, long size, HandlerThread thread) {
            this.vfs = vfs;
            this.path = path;
            this.size = size;
            this.thread = thread;
        }

        @Override
        public long onGetSize() {
            return size;
        }

        @Override
        public int onRead(long offset, int length, byte[] data) throws ErrnoException {
            try {
                if (offset >= size) return 0;
                if (stream == null || offset < position) {
                    closeStream();
                    stream = vfs.openRead(path, offset);
                } else if (offset > position) {
                    stream.skip(offset - position); // SmbVfs streams skip by moving the offset only.
                }
                position = offset;
                int total = 0;
                int wanted = (int) Math.min(length, size - offset);
                while (total < wanted) {
                    int count = stream.read(data, total, wanted - total);
                    if (count <= 0) break;
                    total += count;
                }
                position += total;
                return total;
            } catch (Throwable t) {
                DiagLog.i(AREA, "read failed: " + SmbErrors.describe(t));
                closeStream();
                throw new ErrnoException("onRead", OsConstants.EIO);
            }
        }

        @Override
        public void onRelease() {
            closeStream();
            thread.quitSafely();
        }

        private void closeStream() {
            InputStream s = stream;
            stream = null;
            if (s == null) return;
            try {
                s.close();
            } catch (IOException ignored) {
                // Nothing useful to report on close.
            }
        }
    }
}
