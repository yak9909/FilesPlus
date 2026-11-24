package app.filesplus.extension.thumbs;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.ModSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class VideoThumbHooks {
    private static final String AREA = "video-thumbs";
    private static final int MAX_PENDING = 24;
    private static final long VERIFY_INTERVAL_MS = 15_000;
    private static final int MEMORY_LIMIT = 16_384;
    private static final long MB = 1024L * 1024L;
    private static final int EDGE = 512;
    private static final int[] FRAME_POSITIONS = {10, 30, 50};
    private static final String[] SOURCE_COLUMNS = {"_data", "_size", "date_modified", "mime_type"};
    private static final Pattern SIGNATURE = Pattern.compile("ObjectKey\\{object=-?[0-9]+[/:]-?[0-9]+\\}");
    private static final Object QUEUE = new Object();
    private static final ScheduledThreadPoolExecutor WORKER = new ScheduledThreadPoolExecutor(1, runnable ->
            new Thread(() -> {
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
                catch (Throwable error) { hookError(error); }
                runnable.run();
            }, "FilesPlus-video-thumbs"));
    private static final LinkedHashMap<String, Uri> pending = new LinkedHashMap<>();
    private enum Rejection { UNUSABLE, FAILED }

    private static final VideoThumbMemory<Uri, Boolean> mimeHints = new VideoThumbMemory<>(MEMORY_LIMIT);
    private static final VideoThumbMemory<String, String> aliases = new VideoThumbMemory<>(MEMORY_LIMIT);
    private static final VideoThumbMemory<String, Rejection> rejected = new VideoThumbMemory<>(MEMORY_LIMIT);
    private static final VideoThumbMemory<String, Boolean> failedSources = new VideoThumbMemory<>(MEMORY_LIMIT);
    private static final AtomicLong hits = new AtomicLong();
    private static final AtomicLong misses = new AtomicLong();
    private static final AtomicLong generated = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();
    private static final Set<VideoThumbStore.Entry> touches = new HashSet<>();
    private static volatile VideoThumbStore store;
    private static volatile int clears;
    private static volatile Boolean mainProcess;
    private static boolean draining;
    private static String runningAlias;

    private VideoThumbHooks() {}

    // Replaced by the bytecode patch.
    public static boolean installed() { return false; }

    public static void hookError(Throwable error) {
        try { DiagLog.e(AREA, "Video thumbnail cache", error); }
        catch (Throwable ignored) { /* Keep the original request usable even if logging fails. */ }
    }

    public static void warmUp() {
        try {
            if (isMainProcess() && enabled()) work(VideoThumbHooks::getStore);
        } catch (Throwable error) { hookError(error); }
    }

    /** The native thumbnail resolver already knows the file's MIME, even for Files-table URIs. */
    public static void rememberType(Uri uri, String mime) {
        try {
            if (isMainProcess() && enabled() && uri != null && mime != null && !mime.isEmpty()
                    && mediaItem(uri, "file")) mimeHints.put(uri, isVideoMime(mime));
        } catch (Throwable error) { hookError(error); }
    }

    public static Uri replacement(Object model, Object signature) {
        try {
            if (!isMainProcess() || !enabled() || clears != 0 || !(model instanceof Uri)) return null;
            Uri uri = (Uri) model;
            if (!isCandidate(uri) || signature == null) return null;
            String nativeKey = signature.toString();
            String request = uri.toString() + '\0' + nativeKey;
            String alias = aliases.get(request);
            if (alias == null) {
                if (!SIGNATURE.matcher(nativeKey).matches()) return null;
                alias = VideoThumbStore.hash(uri.toString() + '\0' + nativeKey.replace(':', '/'));
                aliases.put(request, alias);
            }
            if (rejected.get(alias) == Rejection.UNUSABLE) return null;
            VideoThumbStore current = store;
            VideoThumbStore.Entry entry = current == null ? null : current.lookup(alias);
            if (entry != null) {
                if (SystemClock.elapsedRealtime() >= entry.verifyAfter) enqueue(alias, uri);
                markUsed(current, entry);
                hits.incrementAndGet();
                return Uri.fromFile(entry.file);
            }
            misses.incrementAndGet();
            enqueue(alias, uri);
        } catch (Throwable error) { hookError(error); }
        return null;
    }

    /** Invoked before Glide's error request starts. Accept only our own cached file models. */
    public static void loadFailed(Object model) {
        try {
            VideoThumbStore current = store;
            if (!isMainProcess() || current == null || !(model instanceof Uri)) return;
            Uri uri = (Uri) model;
            if (!"file".equals(uri.getScheme()) || uri.getPath() == null) return;
            VideoThumbStore.Entry entry = current.byFile(new File(uri.getPath()));
            if (entry != null && markInvalid(entry)) {
                work(() -> current.remove(entry));
                DiagLog.e(AREA, "Cached WebP failed to load; using the original video", null);
            }
        } catch (Throwable error) { hookError(error); }
    }

    private static boolean isMainProcess() {
        Boolean cached = mainProcess;
        if (cached != null) return cached;
        Context context = ModKit.context();
        if (context == null) return false;
        boolean main = context.getApplicationInfo().processName.equals(Application.getProcessName());
        mainProcess = main;
        return main;
    }

    private static boolean enabled() {
        return ModSettings.getBool(ModSettings.THUMBS_VIDEO_CACHE, true);
    }

    private static boolean isCandidate(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            return mediaItem(uri, "video") || (mediaItem(uri, "file") && !Boolean.FALSE.equals(mimeHints.get(uri)));
        }
        if (!"file".equals(uri.getScheme()) || !uri.isHierarchical()
                || (uri.getAuthority() != null && !uri.getAuthority().isEmpty())) return false;
        String path = uri.getPath();
        return path != null && path.startsWith("/") && isVideoMime(fileMime(path));
    }

    private static boolean mediaItem(Uri uri, String collection) {
        if (!"content".equals(uri.getScheme()) || !MediaStore.AUTHORITY.equals(uri.getAuthority())) return false;
        var segments = uri.getPathSegments();
        return "file".equals(collection)
                ? segments.size() == 3 && collection.equals(segments.get(1))
                : segments.size() == 4 && collection.equals(segments.get(1)) && "media".equals(segments.get(2));
    }

    private static boolean isVideoMime(String mime) {
        return mime != null && mime.startsWith("video/");
    }

    private static String fileMime(String path) {
        int dot = path.lastIndexOf('.');
        if (dot <= path.lastIndexOf('/')) return null;
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static VideoThumbStore getStore() throws IOException {
        if (store == null) {
            Context context = ModKit.context();
            if (context == null) throw new IOException("Application context unavailable");
            VideoThumbStore ready = new VideoThumbStore(new File(context.getFilesDir(), "modkit/video_thumbs"));
            ready.trim(limit());
            store = ready;
        }
        return store;
    }

    private static long limit() {
        return Math.max(1, ModSettings.getInt(ModSettings.THUMBS_VIDEO_LIMIT_MB, 500)) * MB;
    }

    private static boolean validFile(VideoThumbStore.Entry entry) {
        return entry.file.length() == entry.bytes && entry.bytes > 0;
    }

    private static boolean markInvalid(VideoThumbStore.Entry entry) {
        synchronized (QUEUE) {
            if (entry.invalid) return false;
            entry.invalid = true;
            return true;
        }
    }

    private static void persistTouch(VideoThumbStore current, VideoThumbStore.Entry entry) {
        try { current.touch(entry); }
        catch (Throwable error) { hookError(error); }
    }

    private static void markUsed(VideoThumbStore current, VideoThumbStore.Entry entry) {
        entry.lastUsed = System.currentTimeMillis();
        synchronized (QUEUE) {
            if (touches.isEmpty()) {
                WORKER.schedule(() -> {
                    Set<VideoThumbStore.Entry> batch;
                    synchronized (QUEUE) {
                        batch = new HashSet<>(touches);
                        touches.clear();
                    }
                    for (VideoThumbStore.Entry touched : batch) persistTouch(current, touched);
                }, 3, TimeUnit.SECONDS);
            }
            touches.add(entry);
        }
    }

    private static void enqueue(String alias, Uri uri) {
        if (!isMainProcess()) return;
        synchronized (QUEUE) {
            if (clears != 0 || alias.equals(runningAlias) || rejected.get(alias) != null) return;
            pending.remove(alias);
            if (pending.size() == MAX_PENDING) pending.remove(pending.keySet().iterator().next());
            pending.put(alias, uri);
            if (!draining) {
                draining = true;
                WORKER.execute(VideoThumbHooks::generateNext);
            }
        }
    }

    private static void generateNext() {
        String alias;
        Uri uri;
        synchronized (QUEUE) {
            // Latest binds go first. Old binds fall out of the bounded queue during fast scrolling.
            alias = null;
            for (String key : pending.keySet()) alias = key;
            if (alias == null) {
                draining = false;
                return;
            }
            uri = pending.remove(alias);
            runningAlias = alias;
        }
        try {
            if (enabled() && clears == 0) {
                VideoThumbStore current = getStore();
                VideoThumbStore.Entry entry = current.lookup(alias);
                if (entry == null || !verify(current, entry)) generate(current, alias, uri);
            }
        } catch (Throwable error) {
            rememberFailure(alias, error);
        } finally {
            synchronized (QUEUE) {
                runningAlias = null;
                if (pending.isEmpty()) draining = false;
                else WORKER.execute(VideoThumbHooks::generateNext);
            }
        }
    }

    private static boolean verify(VideoThumbStore current, VideoThumbStore.Entry entry) throws IOException {
        if (SystemClock.elapsedRealtime() < entry.verifyAfter) return true;
        if (!validFile(entry)) {
            discard(current, entry, "Cached WebP is missing or has an unexpected size");
            return false;
        }
        if (!entry.sourceMatches()) {
            discard(current, entry, null);
            return false;
        }
        entry.verifyAfter = SystemClock.elapsedRealtime() + VERIFY_INTERVAL_MS;
        return !entry.invalid;
    }

    private static void discard(VideoThumbStore current, VideoThumbStore.Entry entry, String reason) throws IOException {
        if (markInvalid(entry) && reason != null) DiagLog.e(AREA, reason, null);
        current.remove(entry);
    }

    private static void rememberFailure(String alias, Throwable error) {
        if (rejected.put(alias, Rejection.FAILED) == null) {
            failed.incrementAndGet();
            hookError(error);
        }
    }

    private static void generate(VideoThumbStore current, String alias, Uri uri) throws IOException {
        Context context = ModKit.context();
        VideoThumbStore.Source source = sourceIdentity(context, uri);
        if (source == null) {
            rejected.put(alias, Rejection.UNUSABLE);
            return;
        }
        String key = source.key();
        if (failedSources.get(key) != null) {
            rejected.put(alias, Rejection.FAILED);
            return;
        }
        VideoThumbStore.Entry entry = current.existing(key);
        if (entry != null && !validFile(entry)) {
            discard(current, entry, "Cached WebP is missing or has an unexpected size");
            entry = null;
        }
        if (entry == null) {
            File temporary = current.temp(key);
            try {
                try {
                    encode(context, uri, temporary);
                } catch (Throwable error) {
                    failedSources.put(key, true);
                    rememberFailure(alias, error);
                    return;
                }
                if (!enabled() || clears != 0) return;
                VideoThumbStore.Source latest = sourceIdentity(context, uri);
                if (latest == null || !key.equals(latest.key())) return;
                source = latest;
                entry = current.publish(key, temporary);
                generated.incrementAndGet();
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
        }
        if (!enabled() || clears != 0) return;
        entry.lastUsed = System.currentTimeMillis();
        persistTouch(current, entry);
        current.trim(limit());
        if (!entry.invalid) {
            entry.verifyAfter = SystemClock.elapsedRealtime() + VERIFY_INTERVAL_MS;
            current.link(alias, entry, source);
        }
    }

    private static VideoThumbStore.Source sourceIdentity(Context context, Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) return fileSource(new File(uri.getPath()));
        try (Cursor row = context.getContentResolver().query(uri, SOURCE_COLUMNS, null, null, null)) {
            if (row == null || !row.moveToFirst() || !isVideoMime(row.getString(3))) return null;
            String path = row.getString(0);
            if (path == null || !path.startsWith("/")) return null;
            File file = new File(path);
            VideoThumbStore.Source source = fileSource(file);
            if (source != null) return source;
            if (row.isNull(1) || row.isNull(2)) return null;
            return new VideoThumbStore.Source(file.getCanonicalFile(), row.getLong(1), row.getLong(2) * 1000L, false);
        }
    }

    private static VideoThumbStore.Source fileSource(File file) throws IOException {
        if (!file.isFile()) return null;
        return new VideoThumbStore.Source(file.getCanonicalFile(), file.length(), file.lastModified(), true);
    }

    private static void encode(Context context, Uri uri, File target) throws IOException {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            if ("file".equals(uri.getScheme())) retriever.setDataSource(uri.getPath());
            else retriever.setDataSource(context, uri);
            long duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int width = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            if (duration <= 0 || width <= 0 || height <= 0) throw new IOException("Invalid video dimensions or duration");
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if ("90".equals(rotation) || "270".equals(rotation)) {
                int previousWidth = width;
                width = height;
                height = previousWidth;
            }
            double scale = Math.min(1.0, (double) EDGE / Math.max(width, height));
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            Bitmap chosen = null;
            try {
                for (int percent : FRAME_POSITIONS) {
                    long timeUs = duration * percent * 10L;
                    Bitmap frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            targetWidth, targetHeight);
                    if (frame == null) continue;
                    if (chosen != null) chosen.recycle();
                    chosen = frame;
                    if (!almostBlack(frame)) break;
                }
                if (chosen == null) throw new IOException("No video frame decoded");
                try (FileOutputStream out = new FileOutputStream(target)) {
                    if (!chosen.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)) {
                        throw new IOException("WebP encoding failed");
                    }
                    out.getFD().sync();
                }
            } finally {
                if (chosen != null) chosen.recycle();
            }
        }
    }

    private static boolean almostBlack(Bitmap frame) {
        int width = Math.min(frame.getWidth(), 32);
        int height = Math.min(frame.getHeight(), 32);
        Bitmap sample = Bitmap.createScaledBitmap(frame, width, height, false);
        try {
            int[] pixels = new int[width * height];
            sample.getPixels(pixels, 0, width, 0, 0, width, height);
            return VideoThumbStore.almostBlack(pixels);
        } finally {
            if (sample != frame) sample.recycle();
        }
    }

    public static void usage(boolean japanese, Consumer<String> callback) {
        runReport(() -> usageText(japanese), japanese, callback);
    }

    public static void applyLimit(boolean japanese, Consumer<String> callback) {
        runReport(() -> {
            getStore().trim(limit());
            return usageText(japanese);
        }, japanese, callback);
    }

    public static void clear(boolean japanese, Consumer<String> callback) {
        synchronized (QUEUE) {
            clears++;
            pending.clear();
            runReport(() -> {
                try {
                    getStore().clear();
                    return usageText(japanese);
                } finally {
                    synchronized (QUEUE) { clears--; }
                }
            }, japanese, callback);
        }
    }

    private static String usageText(boolean japanese) throws IOException {
        VideoThumbStore.Usage usage = getStore().usage();
        return String.format(Locale.getDefault(), japanese
                        ? "%,d 件、%,d バイト\n今回の起動: ヒット %,d / ミス %,d / 生成 %,d / 失敗 %,d"
                        : "%,d thumbnails, %,d bytes\nThis process: %,d hits served / %,d misses / %,d generated / %,d failed",
                usage.count, usage.bytes, hits.get(), misses.get(), generated.get(), failed.get());
    }

    public static void selfTest(boolean japanese, Consumer<String> callback) {
        runReport(() -> {
            Context context = ModKit.context();
            Uri videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            Bundle query = new Bundle();
            query.putInt(ContentResolver.QUERY_ARG_LIMIT, 1);
            Uri source;
            try (Cursor row = context.getContentResolver().query(videos, new String[]{"_id"}, query, null)) {
                if (row == null) throw new IOException("MediaStore unavailable");
                if (!row.moveToFirst()) return japanese ? "ローカル動画がありません。" : "No local video in MediaStore.";
                source = ContentUris.withAppendedId(videos, row.getLong(0));
            }
            File temp = new File(getStore().directory, "self-test.webp.tmp");
            try {
                long start = SystemClock.elapsedRealtime();
                encode(context, source, temp);
                long elapsed = SystemClock.elapsedRealtime() - start;
                return String.format(Locale.getDefault(), japanese ? "成功: 動画サムネイルを %d ms で生成（%,d バイト）"
                        : "PASS: generated a video thumbnail in %d ms (%,d bytes)", elapsed, temp.length());
            } finally {
                Files.deleteIfExists(temp.toPath());
            }
        }, japanese, callback);
    }

    private static void runReport(Report report, boolean japanese, Consumer<String> callback) {
        work(() -> {
            String text;
            try {
                text = report.run();
            } catch (Throwable error) {
                hookError(error);
                text = japanese ? "失敗。診断ログを確認してください。" : "Failed. See the diagnostic log.";
            }
            DiagLog.i(AREA, text);
            callback.accept(text);
        });
    }

    private static void work(Work work) {
        WORKER.execute(() -> {
            try { work.run(); }
            catch (Throwable error) { hookError(error); }
        });
    }

    private interface Work { void run() throws IOException; }
    private interface Report { String run() throws IOException; }
}
