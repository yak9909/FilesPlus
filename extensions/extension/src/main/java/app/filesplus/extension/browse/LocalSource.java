package app.filesplus.extension.browse;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.OperationCanceledException;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Size;

import app.filesplus.extension.DiagLog;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local storage: the file tree through java.io and the category aggregation through the MediaStore index
 * (DESIGN 5.11.4/5.11.5). Cheap to construct; all the I/O methods belong on a background thread.
 */
public final class LocalSource implements BrowseSource {
    private static final String AREA = "browse";
    private static final int MKDIR_MODE = 0775;

    /** One mounted storage volume. */
    public static final class Volume {
        public final Location root;
        /** MediaStore volume name ("external_primary", "1234-ABCD"), or null when the volume is not indexed. */
        public final String mediaVolume;
        public final boolean primary;
        public final boolean removable;
        public final String description;

        Volume(Location root, String mediaVolume, boolean primary, boolean removable, String description) {
            this.root = root;
            this.mediaVolume = mediaVolume;
            this.primary = primary;
            this.removable = removable;
            this.description = description;
        }
    }

    /** The folders of one (volume, category) with the MediaStore generation and version they were read at. */
    private static final class Cached {
        final long generation;
        final String version;
        final String rootPath;
        final List<Bucket> buckets;

        Cached(long generation, String version, String rootPath, List<Bucket> buckets) {
            this.generation = generation;
            this.version = version;
            this.rootPath = rootPath;
            this.buckets = buckets;
        }
    }

    /** Process-wide: LocalSource instances are cheap and short lived, the aggregation is not. */
    private static final ConcurrentHashMap<String, Cached> CACHE = new ConcurrentHashMap<>();

    private final Context context;

    public LocalSource(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    @Override public boolean hasMediaIndex() {
        return true;
    }

    @Override public boolean canPickDestination() {
        return true;
    }

    /** Mounted volumes with a directory, primary first. */
    public List<Volume> volumes() {
        List<Volume> out = new ArrayList<>();
        try {
            StorageManager manager = context.getSystemService(StorageManager.class);
            for (StorageVolume volume : manager.getStorageVolumes()) {
                File directory = volume.getDirectory();
                if (directory == null || !Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
                File canonical;
                try {
                    canonical = directory.getCanonicalFile();
                } catch (IOException e) {
                    canonical = directory;
                }
                Location root = Location.local(canonical);
                if (root == null) continue;
                Volume item = new Volume(root, volume.getMediaStoreVolumeName(), volume.isPrimary(),
                        volume.isRemovable(), volume.getDescription(context));
                if (item.primary) out.add(0, item); else out.add(item);
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot list storage volumes", t);
        }
        return out;
    }

    @Override public List<Location> roots() {
        List<Location> out = new ArrayList<>();
        for (Volume volume : volumes()) out.add(volume.root);
        return out;
    }

    // ---- categories (MediaStore) ------------------------------------------------------------------------------

    @Override public List<Bucket> buckets(Category category) throws IOException {
        if (!category.indexed()) return new ArrayList<>();
        long started = System.nanoTime();
        List<Volume> volumes = volumes();
        Set<String> indexed = externalVolumeNames();
        List<Bucket> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        StringBuilder note = new StringBuilder();
        for (Volume volume : volumes) {
            if (volume.mediaVolume == null || !indexed.contains(volume.mediaVolume)) continue;
            long volumeStarted = System.nanoTime();
            boolean hit = false;
            List<Bucket> found;
            long generation = generation(volume.mediaVolume);
            // A new MediaStore version (database rebuilt) restarts the generation numbers: it is part of the key.
            String version = version(volume.mediaVolume);
            String key = volume.mediaVolume + '|' + category.name();
            Cached cached = CACHE.get(key);
            if (generation >= 0 && cached != null && cached.generation == generation
                    && cached.version.equals(version) && cached.rootPath.equals(volume.root.path)) {
                found = cached.buckets;
                hit = true;
            } else {
                found = aggregate(volume, category);
                if (generation >= 0) CACHE.put(key, new Cached(generation, version, volume.root.path, found));
            }
            for (Bucket bucket : found) {
                if (seen.add(bucket.folder.path)) all.add(bucket);
            }
            note.append(' ').append(volume.mediaVolume).append(hit ? " cached " : " read ")
                    .append((System.nanoTime() - volumeStarted) / 1_000_000).append(" ms");
        }
        mergeKnownFolders(category, volumes, all, seen);
        Collections.sort(all, (left, right) -> {
            if ((left.count == 0) != (right.count == 0)) return left.count == 0 ? 1 : -1;
            if (left.count > 0 && left.newest != null && right.newest != null
                    && left.newest.modified != right.newest.modified) {
                return left.newest.modified > right.newest.modified ? -1 : 1;
            }
            int order = left.name.compareToIgnoreCase(right.name);
            return order != 0 ? order : left.folder.path.compareTo(right.folder.path);
        });
        DiagLog.i(AREA, "buckets " + category + ": " + all.size() + " folders, "
                + (System.nanoTime() - started) / 1_000_000 + " ms (" + note.toString().trim() + ")");
        return all;
    }

    /** One MediaStore pass over a volume, grouped in Java by RELATIVE_PATH. Minimal projection, no sorting. */
    private List<Bucket> aggregate(Volume volume, Category category) throws IOException {
        Uri uri = MediaStore.Files.getContentUri(volume.mediaVolume);
        Map<String, long[]> counts = new HashMap<>(); // relative path -> {count, newest date, newest id}
        Map<String, String> newestNames = new HashMap<>();
        int rows = 0;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{"_id", "relative_path", "_display_name", "date_modified"}, category.selection, null, null)) {
            if (cursor == null) throw new IOException("MediaStore query failed for " + volume.mediaVolume);
            while (cursor.moveToNext()) {
                rows++;
                String relative = cursor.getString(1);
                // Files directly in the volume root have RELATIVE_PATH "/": no folder row for them.
                if (relative == null || relative.isEmpty() || relative.equals("/") || hiddenRelative(relative)) continue;
                long date = cursor.getLong(3);
                long[] agg = counts.get(relative);
                if (agg == null) {
                    agg = new long[]{0, -1, 0};
                    counts.put(relative, agg);
                }
                agg[0]++;
                if (date > agg[1]) {
                    agg[1] = date;
                    agg[2] = cursor.getLong(0);
                    newestNames.put(relative, cursor.getString(2));
                }
            }
        }
        List<Bucket> out = new ArrayList<>(counts.size());
        for (Map.Entry<String, long[]> item : counts.entrySet()) {
            Location folder = Location.local(new File(volume.root.path, item.getKey()));
            if (folder == null) continue;
            long[] agg = item.getValue();
            String newestName = newestNames.get(item.getKey());
            Location file = newestName == null ? null : folder.child(newestName);
            Entry newest = file == null ? null : new Entry(file, newestName, false, 0, agg[1] * 1000,
                    category, agg[2], volume.mediaVolume);
            out.add(new Bucket(folder, folder.name(), (int) agg[0], newest));
        }
        DiagLog.i(AREA, "aggregated " + category + " on " + volume.mediaVolume + ": " + rows + " rows -> "
                + out.size() + " folders");
        return out;
    }

    /** Adds the section 5.9 known folders of the category (count 0) that the index does not list. */
    private void mergeKnownFolders(Category category, List<Volume> volumes, List<Bucket> into, Set<String> seen) {
        try {
            for (String appCategory : category.appCategories()) {
                for (String path : LocalFolders.knownFolders(appCategory)) {
                    Location folder = Location.local(path);
                    if (folder == null || seen.contains(folder.path)) continue;
                    Volume volume = volumeOf(volumes, folder);
                    if (volume == null || folder.path.equals(volume.root.path)) continue;
                    String relative = folder.path.substring(volume.root.path.length() + 1);
                    File file = folder.file();
                    // Links are never followed (DESIGN 10): a linked known folder is not offered.
                    if (hiddenRelative(relative + "/") || Files.isSymbolicLink(file.toPath()) || !file.isDirectory()) {
                        continue;
                    }
                    seen.add(folder.path);
                    into.add(new Bucket(folder, folder.name(), 0, null));
                }
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot merge known folders for " + category, t);
        }
    }

    @Override public List<Entry> all(Category category, int offset, int limit) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (!category.indexed()) return out;
        List<Volume> volumes = volumes();
        Map<String, Volume> byName = new HashMap<>();
        for (Volume volume : volumes) if (volume.mediaVolume != null) byName.put(volume.mediaVolume, volume);
        Bundle args = new Bundle();
        // Hidden folders are excluded in SQL so a page is never short because of rows dropped after LIMIT/OFFSET.
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "(" + category.selection + ")"
                + " AND relative_path NOT LIKE 'Android/%' AND relative_path NOT LIKE '.%'"
                + " AND relative_path NOT LIKE '%/.%'");
        args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_modified DESC, _id DESC");
        args.putInt(ContentResolver.QUERY_ARG_OFFSET, Math.max(0, offset));
        args.putInt(ContentResolver.QUERY_ARG_LIMIT, Math.max(1, limit));
        try (Cursor cursor = context.getContentResolver().query(MediaStore.Files.getContentUri("external"),
                new String[]{"_id", "relative_path", "volume_name", "_display_name", "size", "date_modified"},
                args, null)) {
            if (cursor == null) throw new IOException("MediaStore query failed");
            while (cursor.moveToNext()) {
                String relative = cursor.getString(1);
                Volume volume = byName.get(cursor.getString(2));
                String name = cursor.getString(3);
                if (volume == null || relative == null || name == null || hiddenRelative(relative)) continue;
                Location folder = Location.local(new File(volume.root.path, relative));
                Location file = folder == null ? null : folder.child(name);
                if (file == null) continue;
                out.add(new Entry(file, name, false, cursor.getLong(4), cursor.getLong(5) * 1000, category,
                        cursor.getLong(0), volume.mediaVolume));
            }
        }
        return out;
    }

    // ---- folders (java.io) ------------------------------------------------------------------------------------

    @Override public List<Entry> list(Location dir, Category filterOrNull) throws IOException {
        if (dir == null || !dir.isLocal()) throw new IOException("Not a local folder: " + dir);
        File directory = dir.file();
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Cannot read " + dir);
        List<Volume> volumes = volumes();
        Volume volume = volumeOf(volumes, dir);
        boolean atRoot = volume != null && volume.root.equals(dir);
        List<Entry> out = new ArrayList<>(children.length);
        boolean hasFiles = false;
        for (File child : children) {
            String name = child.getName();
            // Dot entries (.nomedia, .FilesPlus, ...) and links (never followed, DESIGN 10) are not shown.
            if (name.startsWith(".") || Files.isSymbolicLink(child.toPath())) continue;
            boolean folder = child.isDirectory();
            if (folder && atRoot && name.equalsIgnoreCase("Android")) continue;
            // DOWNLOADS has no extension table: it filters nothing.
            if (!folder && filterOrNull != null && filterOrNull.indexed() && !filterOrNull.matchesName(name)) continue;
            Location location = dir.child(name);
            if (location == null) continue;
            hasFiles |= !folder;
            out.add(new Entry(location, name, folder, folder ? 0 : child.length(), child.lastModified(),
                    folder ? null : Category.ofName(name), 0, null));
        }
        if (hasFiles && volume != null && volume.mediaVolume != null) {
            out = withMediaIds(out, volume, dir);
        }
        Collections.sort(out, (left, right) -> {
            if (left.directory != right.directory) return left.directory ? -1 : 1;
            int order = left.name.compareToIgnoreCase(right.name);
            return order != 0 ? order : left.name.compareTo(right.name);
        });
        return out;
    }

    /** One query for the folder's direct children gives every file its MediaStore id (for thumbnails and preview). */
    private List<Entry> withMediaIds(List<Entry> entries, Volume volume, Location dir) {
        try {
            // Files directly in the volume root have RELATIVE_PATH "/".
            boolean root = dir.equals(volume.root);
            String relative = root ? "/" : dir.path.substring(volume.root.path.length() + 1) + "/";
            Map<String, Long> ids = new HashMap<>();
            try (Cursor cursor = context.getContentResolver().query(MediaStore.Files.getContentUri(volume.mediaVolume),
                    new String[]{"_id", "_display_name"}, "relative_path = ?", new String[]{relative}, null)) {
                if (cursor != null) while (cursor.moveToNext()) ids.put(cursor.getString(1), cursor.getLong(0));
            }
            if (ids.isEmpty()) return entries;
            List<Entry> out = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                Long id = entry.directory ? null : ids.get(entry.name);
                out.add(id == null ? entry : new Entry(entry.location, entry.name, false, entry.size, entry.modified,
                        entry.category, id, volume.mediaVolume));
            }
            return out;
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot look up media ids in " + dir, t);
            return entries;
        }
    }

    @Override public Location mkdir(Location parent, String name) throws IOException {
        if (parent == null || !parent.isLocal()) throw new IOException("Not a local folder: " + parent);
        // A plain visible name: no dot start (it would hide the folder), no trailing dot or space (lost on FAT/exFAT).
        if (!Location.validName(name) || name.startsWith(".") || name.endsWith(".") || !name.equals(name.trim())) {
            throw new IOException("Invalid folder name: " + name);
        }
        // The parent must be a real, unlinked folder inside a mounted volume (and is used in its canonical form).
        String base = LocalFolders.folderPath(context, parent.file());
        if (base == null) throw new IOException("Not a folder on a mounted volume: " + parent);
        // Nothing is created in Android/ or the FilesPlus trash folder (DESIGN 10.6), at any depth below them.
        File volumeRoot = LocalFolders.volumeRoot(context, new File(base));
        if (volumeRoot != null && !base.equals(volumeRoot.getPath())) {
            String top = base.substring(volumeRoot.getPath().length() + 1).split("/", 2)[0];
            if (top.equalsIgnoreCase("Android") || top.equalsIgnoreCase(".FilesPlus")) {
                throw new IOException("Cannot create folders in " + top + ": " + parent);
            }
        }
        File target = new File(base, name);
        try {
            Os.mkdir(target.getPath(), MKDIR_MODE);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EEXIST) throw new FileAlreadyExistsException(target.getPath());
            throw new IOException("mkdir failed (" + e.getMessage() + "): " + target, e);
        }
        MediaScannerConnection.scanFile(context, new String[]{target.getPath()}, null, null);
        Location created = Location.local(target);
        if (created == null) throw new IOException("Invalid path: " + target);
        return created;
    }

    // ---- thumbnails and viewing -------------------------------------------------------------------------------

    @Override public Bitmap thumbnail(Entry entry, int px, CancellationSignal signal) {
        try {
            if (Build.VERSION.SDK_INT < 29) return null;
            Uri uri = mediaUri(entry, true);
            if (uri == null) return null;
            return context.getContentResolver().loadThumbnail(uri, new Size(px, px), signal);
        } catch (OperationCanceledException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            return null; // No thumbnail for this file (or it vanished): the caller keeps its icon.
        }
    }

    @Override public Uri viewUri(Entry entry) {
        return mediaUri(entry, false);
    }

    /** The MediaStore URI of an indexed file; thumbnails need the media-type specific collection. */
    private static Uri mediaUri(Entry entry, boolean typedOnly) {
        if (entry == null || entry.directory || entry.mediaId == 0 || entry.mediaVolume == null) return null;
        Uri base = null;
        if (entry.category == Category.IMAGES) base = MediaStore.Images.Media.getContentUri(entry.mediaVolume);
        else if (entry.category == Category.VIDEOS) base = MediaStore.Video.Media.getContentUri(entry.mediaVolume);
        else if (entry.category == Category.AUDIO) base = MediaStore.Audio.Media.getContentUri(entry.mediaVolume);
        else if (typedOnly) return null; // Documents and other files have no thumbnail.
        else base = MediaStore.Files.getContentUri(entry.mediaVolume);
        return ContentUris.withAppendedId(base, entry.mediaId);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private Set<String> externalVolumeNames() {
        try {
            if (Build.VERSION.SDK_INT >= 29) return MediaStore.getExternalVolumeNames(context);
        } catch (Throwable t) {
            DiagLog.e(AREA, "Cannot list MediaStore volumes", t);
        }
        return Collections.singleton(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    /** The MediaStore generation of a volume, or -1 when unavailable (the cache is then not used). */
    private long generation(String mediaVolume) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return MediaStore.getGeneration(context, mediaVolume);
        } catch (Throwable t) {
            // Fall through: no caching.
        }
        return -1;
    }

    /** The MediaStore version of a volume (changes when its database is rebuilt), or "" when unavailable. */
    private String version(String mediaVolume) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return MediaStore.getVersion(context, mediaVolume);
        } catch (Throwable t) {
            // Fall through.
        }
        return "";
    }

    private static Volume volumeOf(List<Volume> volumes, Location location) {
        Volume best = null;
        for (Volume volume : volumes) {
            if (location.isWithin(volume.root)
                    && (best == null || volume.root.path.length() > best.root.path.length())) best = volume;
        }
        return best;
    }

    /** A MediaStore RELATIVE_PATH ("Pictures/Twitter/") that the browser hides: dot folders and Android/. */
    static boolean hiddenRelative(String relative) {
        String[] segments = relative.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].startsWith(".")) return true;
        }
        return segments.length > 0 && segments[0].equalsIgnoreCase("Android");
    }
}
