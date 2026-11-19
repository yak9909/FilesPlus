package app.filesplus.extension.clip;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Locale;

/**
 * One entry of the clipboard. Immutable. A plain final class, not a Java record: the extension avoids records
 * (desugaring is unverified). {@code path} is a {@code Location} string: an absolute path for local files.
 *
 * <p>Folders count their contents (DESIGN 5.12.2, 10.8): {@link #size()} is the recursive size and
 * {@link #fileCount()} the number of entries (files and subfolders) inside, so the 50 items / 100MB limit of
 * {@link PasteRules} cannot be passed by cutting one big folder. Use {@link #measure(File)} when cutting.
 */
public final class ClipItem {
    /** {@link #fileCount()} of a folder that was never measured (an old clipboard entry): pasting it is refused. */
    public static final int UNKNOWN_COUNT = -1;

    private final String path;
    private final long mediaId;
    private final String name;
    private final long size;
    private final boolean isDir;
    private final int fileCount;
    private final boolean overLimit;

    /**
     * Old form without a measurement: a file counts as 1; a folder is {@link #UNKNOWN_COUNT} (not measured) and
     * is refused as too large until it is measured. {@code mediaId} is the MediaStore id, or 0 when unknown.
     */
    public ClipItem(String path, long mediaId, String name, long size, boolean isDir) {
        this(path, mediaId, name, size, isDir, isDir ? UNKNOWN_COUNT : 1, false);
    }

    public ClipItem(String path, long mediaId, String name, long size, boolean isDir, int fileCount,
                    boolean overLimit) {
        this.path = path;
        this.mediaId = mediaId;
        this.name = name;
        this.size = size;
        this.isDir = isDir;
        this.fileCount = fileCount;
        this.overLimit = overLimit;
    }

    public String path() { return path; }

    public long mediaId() { return mediaId; }

    public String name() { return name; }

    /** Size in bytes at the time of the cut; recursive for folders (a lower bound when {@link #overLimit()}). */
    public long size() { return size; }

    public boolean isDir() { return isDir; }

    /**
     * 1 for a file. For a folder the number of entries (files and subfolders) inside it, recursively (a lower
     * bound when {@link #overLimit()}), or {@link #UNKNOWN_COUNT}.
     */
    public int fileCount() { return fileCount; }

    /** The walk stopped early because the item is above the paste limits (50 entries or 100MB). */
    public boolean overLimit() { return overLimit; }

    /** Case-insensitive key (FAT / FUSE treat names that differ in case as the same file). */
    String key() {
        return path.toLowerCase(Locale.ROOT);
    }

    // ---- measuring ------------------------------------------------------------------------------------------

    /**
     * Measures a file or a folder tree: recursive size and entry count. Links are never followed (a link counts
     * as one entry of its own size). Stops as soon as the tree is above the paste limits and marks the result
     * {@link #overLimit()}. Does file I/O: call it off the UI thread, at cut time.
     */
    public static ClipItem measure(File file) {
        return measure(file, 0);
    }

    public static ClipItem measure(File file, long mediaId) {
        return measure(file, mediaId, PasteRules.MAX_ITEMS, PasteRules.MAX_BYTES);
    }

    /** Same with explicit limits (for tests). */
    static ClipItem measure(File file, long mediaId, int maxEntries, long maxBytes) {
        boolean dir = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS);
        if (!dir) {
            long length = file.length();
            return new ClipItem(file.getPath(), mediaId, file.getName(), length, false, 1, length > maxBytes);
        }
        long[] total = new long[2]; // [0] bytes, [1] entries
        boolean over = walk(file, total, maxEntries, maxBytes);
        int count = (int) Math.min(total[1], Integer.MAX_VALUE);
        return new ClipItem(file.getPath(), mediaId, file.getName(), total[0], true, count, over);
    }

    /** Returns true once a limit is exceeded (the walk then stops). */
    private static boolean walk(File folder, long[] total, int maxEntries, long maxBytes) {
        File[] children = folder.listFiles();
        if (children == null) return false;
        for (File child : children) {
            total[1]++;
            boolean link = Files.isSymbolicLink(child.toPath());
            boolean directory = !link && Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS);
            if (!directory) total[0] += child.length();
            if (total[1] > maxEntries || total[0] > maxBytes) return true;
            if (directory && walk(child, total, maxEntries, maxBytes)) return true;
        }
        return false;
    }

    @Override public boolean equals(Object other) {
        return other instanceof ClipItem && key().equals(((ClipItem) other).key());
    }

    @Override public int hashCode() {
        return key().hashCode();
    }

    @Override public String toString() {
        return path;
    }
}
