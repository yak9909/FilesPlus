package app.filesplus.extension.ui;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.browse.BrowseSource;
import app.filesplus.extension.browse.Entry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Loads thumbnails for list and grid rows: a small memory LRU, two background threads and one request per view.
 * A row that is recycled or rebound calls {@link #load} again (or {@link #cancel}); the earlier request is then
 * cancelled and its result is never applied to the view. All methods are called on the UI thread.
 */
public final class ThumbLoader {
    /** An id of the application range, as ImageView.setTag(int, Object) requires. */
    private static final int TAG = 0x7e544c44;
    private static final int THREADS = 2;
    private static final int CACHE_BYTES = 16 * 1024 * 1024;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, new ThreadFactory() {
        private int count;

        @Override public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "FilesPlus-thumb-" + (++count));
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    });
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** The request currently bound to a view (kept in the view's tag). */
    private static final class Request {
        final String key;
        final CancellationSignal signal = new CancellationSignal();
        Future<?> future;
        volatile boolean cancelled;

        Request(String key) {
            this.key = key;
        }

        void cancel() {
            cancelled = true;
            signal.cancel();
            if (future != null) future.cancel(true);
        }
    }

    private ThumbLoader() {}

    /**
     * Shows {@code placeholder} now and the thumbnail of {@code entry} when it is ready. A null entry or a source
     * without thumbnails only shows the placeholder. {@code px} is both the width and the height to request.
     */
    public static void load(ImageView view, BrowseSource source, Entry entry, int px, Drawable placeholder) {
        try {
            cancel(view);
            if (entry == null || source == null || entry.directory) {
                view.setImageDrawable(placeholder);
                return;
            }
            String key = entry.location + "|" + entry.modified + "|" + px;
            Bitmap hit = MEMORY.get(key);
            if (hit != null) {
                view.setImageBitmap(hit);
                return;
            }
            view.setImageDrawable(placeholder);
            Request request = new Request(key);
            view.setTag(TAG, request);
            request.future = POOL.submit(() -> {
                Bitmap bitmap = null;
                try {
                    if (!request.cancelled) bitmap = source.thumbnail(entry, px, request.signal);
                } catch (Throwable t) {
                    DiagLog.e("ui", "Thumbnail failed for " + entry.location, t);
                }
                if (bitmap == null || request.cancelled) return;
                MEMORY.put(key, bitmap);
                final Bitmap result = bitmap;
                MAIN.post(() -> {
                    // Still the view's current request? A recycled row has a new request or none.
                    if (!request.cancelled && view.getTag(TAG) == request) {
                        view.setTag(TAG, null);
                        view.setImageBitmap(result);
                    }
                });
            });
        } catch (Throwable t) {
            DiagLog.e("ui", "Cannot start thumbnail load", t);
        }
    }

    /** Cancels the view's pending request (a recycled or detached row). The image itself is left as it is. */
    public static void cancel(ImageView view) {
        try {
            Object tag = view.getTag(TAG);
            if (tag instanceof Request) ((Request) tag).cancel();
            view.setTag(TAG, null);
        } catch (Throwable t) {
            DiagLog.e("ui", "Cannot cancel thumbnail load", t);
        }
    }

    /** Drops the cached bitmaps (low memory, or after files changed). */
    public static void clear() {
        MEMORY.evictAll();
    }
}
