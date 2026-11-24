package app.filesplus.extension.smb;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.settings.SettingsHooks;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only DocumentsProvider exposing every saved SMB connection as a storage root, so the system picker
 * (and any SAF client) can browse and open files on the share. Document ids are {@link SmbDocId}. Writing,
 * creating, renaming and deleting are not offered (no document flags). Calls arrive on binder threads, which
 * may block. Root metadata is local and safe on the main thread. Child listing reports failures in cursor extras.
 */
public final class SmbDocumentsProvider extends DocumentsProvider {
    private static final String AREA = "smb";
    private static final AtomicLong LAST_LIST_ERROR = new AtomicLong(-5_000);
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
                    DocumentsContract.buildRootsUri(SmbRoots.authority(context.getPackageName())), null);
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
        SmbDiagnostics.Call timing = SmbDiagnostics.begin("queryDocument");
        Throwable error = null;
        int rows = 0;
        try {
            SmbDocId id = SmbDocId.decode(documentId);
            timing.target(SmbDiagnostics.pathTag(id));
            SmbConnection c = connection(id);
            MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
            if (id.isRoot()) {
                addRow(cursor, SmbDocId.root(c.id), c.label(), true, 0, 0);
            } else {
                requireBackgroundThread();
                SmbEntry entry = SmbVfs.of(c).stat(id.path);
                addRow(cursor, SmbDocId.encode(c.id, id.path), entry.name, entry.isDir, entry.size, entry.mtimeMillis);
            }
            rows = cursor.getCount();
            return cursor;
        } catch (Throwable t) {
            error = t;
            throw fail(t);
        } finally {
            timing.finish("rows=" + rows, error);
        }
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        SmbDiagnostics.Call timing = SmbDiagnostics.begin("queryChildDocuments");
        Throwable error = null;
        int rows = 0;
        try {
            requireBackgroundThread();
            SmbDocId parent = SmbDocId.decode(parentDocumentId);
            timing.target(SmbDiagnostics.pathTag(parent));
            SmbConnection c = connection(parent);
            List<SmbEntry> entries = SmbVfs.of(c).list(parent.path);
            MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection, entries.size());
            for (SmbEntry e : entries) {
                addRow(cursor, SmbDocId.encode(c.id, SmbPath.join(parent.path, e.name)), e.name,
                        e.isDir, e.size, e.mtimeMillis);
            }
            rows = cursor.getCount();
            return cursor;
        } catch (Throwable t) {
            error = t;
            String message = describe(t);
            MatrixCursor empty = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection, 0);
            Bundle extras = new Bundle();
            extras.putString(DocumentsContract.EXTRA_ERROR, message);
            empty.setExtras(extras);
            if (Binder.getCallingUid() == Process.myUid()) showListError(message);
            return empty;
        } finally {
            timing.finish("rows=" + rows, error);
        }
    }

    private void showListError(String message) {
        try {
            Context context = getContext();
            if (context == null) return;
            long now = SystemClock.elapsedRealtime();
            long previous = LAST_LIST_ERROR.get();
            if (now - previous < 5_000 || !LAST_LIST_ERROR.compareAndSet(previous, now)) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(context, (SettingsHooks.isJapanese(context)
                            ? "SMB フォルダを読み込めません: " : "Could not load SMB folder: ") + message,
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable error) {
                    DiagLog.e(AREA, "Cannot show SMB listing error", error);
                }
            });
        } catch (Throwable error) {
            DiagLog.e(AREA, "Cannot post SMB listing error", error);
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
        SmbDiagnostics.Call timing = SmbDiagnostics.begin("openDocument");
        Throwable error = null;
        long size = 0;
        HandlerThread thread = null;
        try {
            if (!"r".equals(mode)) throw readOnly();
            requireBackgroundThread();
            if (Build.VERSION.SDK_INT < 26) throw new FileNotFoundException("Android 8 or later is required");
            SmbDocId id = SmbDocId.decode(documentId);
            String pathTag = SmbDiagnostics.pathTag(id);
            timing.target(pathTag);
            if (id.isRoot()) throw new FileNotFoundException("Not a file");
            SmbConnection c = connection(id);
            SmbEntry entry = SmbVfs.of(c).stat(id.path);
            if (entry.isDir) throw new FileNotFoundException("Not a file");

            thread = new ProxyThread();
            thread.start();
            StorageManager storage = getContext().getSystemService(StorageManager.class);
            ProxyReader reader = new ProxyReader(SmbVfs.of(c), id.path, pathTag, entry.size, thread);
            ParcelFileDescriptor fd = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, reader,
                    new Handler(thread.getLooper()));
            reader.fdOpened();
            size = entry.size;
            thread = null; // Owned by the reader now; it quits the thread in onRelease.
            return fd;
        } catch (Throwable t) {
            error = t;
            if (thread != null) thread.quit();
            throw fail(t);
        } finally {
            timing.finish("bytes=" + size, error);
        }
    }

    @Override
    public String createDocument(String parentId, String mimeType, String name) throws FileNotFoundException {
        throw readOnly();
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        throw readOnly();
    }

    @Override
    public String renameDocument(String documentId, String name) throws FileNotFoundException {
        throw readOnly();
    }

    @Override
    public String moveDocument(String documentId, String sourceParentId, String targetParentId)
            throws FileNotFoundException {
        throw readOnly();
    }

    @Override
    public String copyDocument(String documentId, String targetParentId) throws FileNotFoundException {
        throw readOnly();
    }

    @Override
    public void removeDocument(String documentId, String parentId) throws FileNotFoundException {
        throw readOnly();
    }

    private static FileNotFoundException readOnly() {
        return new FileNotFoundException("SMB documents are read-only");
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

    /** Converts any failure to the only exception type the provider may throw. */
    private static FileNotFoundException fail(Throwable t) {
        if (t instanceof FileNotFoundException) return (FileNotFoundException) t;
        return new FileNotFoundException(describe(t));
    }

    private static String describe(Throwable t) {
        if (t instanceof FileNotFoundException && t.getMessage() != null) return t.getMessage();
        return t instanceof IllegalArgumentException ? "Invalid SMB document" : SmbErrors.describe(t);
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

    private static final class ProxyThread extends HandlerThread {
        ProxyThread() {
            super("filesplus-smb-proxy");
        }

        @Override
        public void run() {
            SmbDiagnostics.PROXY_THREADS.incrementAndGet();
            try {
                super.run();
            } finally {
                SmbDiagnostics.PROXY_THREADS.decrementAndGet();
            }
        }
    }

    /** Serves reads of one file to the kernel through SmbVfs; runs on its own handler thread. */
    private static final class ProxyReader extends ProxyFileDescriptorCallback {
        private final SmbVfs vfs;
        private final String path;
        private final String pathTag;
        private final long size;
        private final HandlerThread thread;
        private InputStream stream;
        private long position;
        private boolean opened;
        private boolean released;

        ProxyReader(SmbVfs vfs, String path, String pathTag, long size, HandlerThread thread) {
            this.vfs = vfs;
            this.path = path;
            this.pathTag = pathTag;
            this.size = size;
            this.thread = thread;
        }

        synchronized void fdOpened() {
            if (released) return;
            opened = true;
            SmbDiagnostics.OPEN_PROXIES.incrementAndGet();
        }

        private synchronized void fdReleased() {
            if (released) return;
            released = true;
            if (opened) SmbDiagnostics.OPEN_PROXIES.decrementAndGet();
        }

        @Override
        public long onGetSize() {
            return size;
        }

        @Override
        public int onRead(long offset, int length, byte[] data) throws ErrnoException {
            SmbDiagnostics.Call timing = SmbDiagnostics.begin("proxy onRead");
            timing.target(pathTag);
            Throwable error = null;
            int total = 0;
            try {
                if (offset >= size) return 0;
                if (stream == null || offset < position) {
                    closeStream();
                    stream = vfs.openRead(path, offset);
                } else if (offset > position) {
                    stream.skip(offset - position); // SmbVfs streams skip by moving the offset only.
                }
                position = offset;
                int wanted = (int) Math.min(length, size - offset);
                while (total < wanted) {
                    int count = stream.read(data, total, wanted - total);
                    if (count <= 0) break;
                    total += count;
                }
                position += total;
                return total;
            } catch (Throwable t) {
                error = t;
                closeStream();
                throw new ErrnoException("onRead", OsConstants.EIO);
            } finally {
                timing.finishRead(offset, total, error);
            }
        }

        @Override
        public void onRelease() {
            SmbDiagnostics.Call timing = SmbDiagnostics.begin("proxy onRelease");
            timing.target(pathTag);
            Throwable error = null;
            try {
                fdReleased();
                closeStream();
            } catch (Throwable t) {
                error = t;
            } finally {
                try {
                    thread.quitSafely();
                } catch (Throwable t) {
                    if (error == null) error = t;
                }
                timing.finish("fd released", error);
            }
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
