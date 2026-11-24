package app.filesplus.extension.clip;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether the clipboard may be pasted into a target (DESIGN 5.12.2, 10). The decision uses only
 * {@code java.io} (no Android types), so it also runs on a plain JVM. It reads a few folder names and writability
 * but never changes anything.
 *
 * <p>Reasons are checked in this order: EMPTY, SMB, ALL_TAB_DISABLED, PROTECTED, INTO_SELF, SAME_FOLDER,
 * NOT_WRITABLE, TOO_LARGE. Items that no longer exist are not a reason: a paste skips and counts
 * them. A paste where only some items are already in the folder is allowed (those are skipped).
 */
public final class PasteRules {
    /** More than this many items need the OpEngine (S6). */
    public static final int MAX_ITEMS = 50;
    /** More than this many bytes in total need the OpEngine (S6). */
    public static final long MAX_BYTES = 100L * 1024 * 1024;

    private PasteRules() {}

    public enum Reason {
        /** Every item is already in the target folder. */
        SAME_FOLDER,
        /** A cut folder would be pasted into itself or below itself. */
        INTO_SELF,
        /** The target is missing, not a folder or not writable. */
        NOT_WRITABLE,
        /** Android/ or .FilesPlus (including everything below them), or a place outside the storage volumes. */
        PROTECTED,
        /**
         * More than {@link #MAX_ITEMS} entries or more than {@link #MAX_BYTES} in total, folders counted with their
         * contents, or a folder that was not measured (waits for S6).
         */
        TOO_LARGE,
        /** SMB is never a source or a target until S9. */
        SMB,
        /** The "All" tab setting does not give a folder to paste into. */
        ALL_TAB_DISABLED,
        /** The clipboard has nothing in it. */
        EMPTY
    }

    public static final class Result {
        public final boolean ok;
        /** Null when {@link #ok}. */
        public final Reason reason;
        /** Only with {@link #ok}: the target is an "All" tab set to "ask"; the user must pick a folder first. */
        public final boolean needsPicker;

        private Result(boolean ok, Reason reason, boolean needsPicker) {
            this.ok = ok;
            this.reason = reason;
            this.needsPicker = needsPicker;
        }

        static Result ok() { return new Result(true, null, false); }

        static Result pick() { return new Result(true, null, true); }

        static Result refused(Reason reason) { return new Result(false, reason, false); }

        @Override public String toString() { return ok ? (needsPicker ? "ok(pick)" : "ok") : reason.name(); }
    }

    public static Result check(Clipboard.State state, PasteTarget target) {
        try {
            return decide(state, target);
        } catch (IOException | RuntimeException e) {
            // A folder that cannot even be resolved is not a place to paste into.
            return Result.refused(Reason.NOT_WRITABLE);
        }
    }

    private static Result decide(Clipboard.State state, PasteTarget target) throws IOException {
        if (state == null || state.items.isEmpty()) return Result.refused(Reason.EMPTY);
        if (target == null) return Result.refused(Reason.NOT_WRITABLE);
        if (target.isSmb()) return Result.refused(Reason.SMB);
        for (ClipItem item : state.items) {
            if (item.path() == null || item.path().startsWith("smb://")) return Result.refused(Reason.SMB);
        }
        if (target.isAllTab()) {
            if (target.needsPicker()) return tooLarge(state) ? Result.refused(Reason.TOO_LARGE) : Result.pick();
            if (target.folder() == null) return Result.refused(Reason.ALL_TAB_DISABLED);
        }
        File dest = target.folder();
        if (dest == null) return Result.refused(Reason.NOT_WRITABLE);

        List<File> roots = canonicalRoots(target.roots());
        File destCanonical = dest.getCanonicalFile();
        File destVolume = volumeOf(destCanonical, roots);
        // Fail closed: a target outside the known volumes, or inside Android/ or .FilesPlus, is protected.
        if (destVolume == null || isSystemFolder(relative(destVolume, destCanonical))) {
            return Result.refused(Reason.PROTECTED);
        }

        List<File> sources = new ArrayList<>();
        int inTarget = 0;
        for (ClipItem item : state.items) {
            File raw = new File(item.path());
            File source = canonicalSource(raw);
            File volume = volumeOf(source, roots);
            if (protectedSource(source, volume)) return Result.refused(Reason.PROTECTED);
            if (volume == null) continue; // A missing source on an unmounted volume is skipped later.
            sources.add(source);
            if (item.isDir() || Files.isDirectory(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (within(destCanonical, source)) return Result.refused(Reason.INTO_SELF);
            }
            if (same(source.getParentFile(), destCanonical)) inTarget++;
        }
        if (!sources.isEmpty() && inTarget == sources.size()) return Result.refused(Reason.SAME_FOLDER);

        if (!writable(dest, target.createIfMissing())) return Result.refused(Reason.NOT_WRITABLE);
        if (tooLarge(state)) return Result.refused(Reason.TOO_LARGE);
        return Result.ok();
    }

    /**
     * Folders count their contents. Above the limits, a walk that stopped early ({@code overLimit}) and a folder
     * that was never measured (an old clipboard entry; {@link ClipItem#UNKNOWN_COUNT}) are all too large: the
     * safer choice, since an unmeasured folder could hold any number of files. Cut again to measure it.
     */
    private static boolean tooLarge(Clipboard.State state) {
        long entries = 0;
        long bytes = 0;
        for (ClipItem item : state.items) {
            if (item.overLimit()) return true;
            if (item.isDir() && item.fileCount() == ClipItem.UNKNOWN_COUNT) return true;
            entries += Math.max(1, item.fileCount());
            bytes += Math.max(0, item.size());
        }
        return entries > MAX_ITEMS || bytes > MAX_BYTES;
    }

    private static boolean writable(File dest, boolean createIfMissing) {
        if (dest.isDirectory()) return dest.canWrite();
        if (!createIfMissing || dest.exists()) return false;
        File parent = dest.getAbsoluteFile().getParentFile();
        return parent != null && parent.isDirectory() && parent.canWrite();
    }

    /** Shared cut/paste protection: resolve the parent, preserving the source itself when it is a link. */
    static boolean protectedSource(File raw, List<File> roots) throws IOException {
        File source = canonicalSource(raw);
        return protectedSource(source, volumeOf(source, canonicalRoots(roots)));
    }

    private static File canonicalSource(File raw) throws IOException {
        File parent = raw.getAbsoluteFile().getParentFile();
        return parent == null ? raw : new File(parent.getCanonicalFile(), raw.getName());
    }

    private static boolean protectedSource(File source, File volume) {
        // A missing source outside the mounted volumes is skipped later; an existing one is protected.
        return volume == null ? Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)
                : isSystemFolder(relative(volume, source));
    }

    private static List<File> canonicalRoots(List<File> roots) throws IOException {
        List<File> out = new ArrayList<>();
        for (File root : roots) out.add(root.getCanonicalFile());
        return out;
    }

    /** The volume root that contains {@code path} (the longest match), or null. Compared case-insensitively. */
    private static File volumeOf(File path, List<File> roots) {
        String p = lower(path);
        File best = null;
        for (File root : roots) {
            String r = lower(root);
            if ((p.equals(r) || p.startsWith(r + File.separator))
                    && (best == null || r.length() > lower(best).length())) {
                best = root;
            }
        }
        return best;
    }

    /** The part of {@code path} below its volume root, lower case with '/' separators ("" for the root). */
    private static String relative(File volume, File path) {
        String p = lower(path);
        String r = lower(volume);
        return p.length() <= r.length() ? "" : p.substring(r.length() + 1).replace(File.separatorChar, '/');
    }

    private static boolean isSystemFolder(String relative) {
        return relative.equals("android") || relative.startsWith("android/")
                || relative.equals(".filesplus") || relative.startsWith(".filesplus/");
    }

    /** True when {@code path} is {@code ancestor} or lies below it. */
    private static boolean within(File path, File ancestor) {
        String p = lower(path);
        String a = lower(ancestor);
        return p.equals(a) || p.startsWith(a + File.separator);
    }

    private static boolean same(File a, File b) {
        return a != null && b != null && lower(a).equals(lower(b));
    }

    private static String lower(File file) {
        return file.getPath().toLowerCase(Locale.ROOT);
    }

    // ---- user-visible text ------------------------------------------------------------------------------------

    /**
     * A short sentence for a refused paste (Japanese or English by the app's language). SAME_FOLDER uses the app's
     * own plurals {@code error_move_to_same_directory} when they can be found.
     */
    public static String message(Context context, Reason reason, int itemCount, String folderName) {
        boolean ja = "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        switch (reason) {
            case SAME_FOLDER: {
                int id = context.getResources().getIdentifier("error_move_to_same_directory", "plurals",
                        context.getPackageName());
                if (id != 0) {
                    try {
                        return context.getResources().getQuantityString(id, Math.max(1, itemCount), folderName);
                    } catch (RuntimeException ignored) {
                        // Fall through to our own text.
                    }
                }
                return ja ? "すでに " + folderName + " にあります" : "Already in " + folderName;
            }
            case INTO_SELF:
                return ja ? "移動するフォルダの中には貼り付けられません" : "Cannot paste into the folder being moved";
            case NOT_WRITABLE:
                return ja ? "このフォルダには書き込めません" : "This folder is not writable";
            case PROTECTED:
                return ja ? "この場所（Android・.FilesPlus など）には貼り付けられません"
                        : "Cannot paste here (Android, .FilesPlus and similar)";
            case TOO_LARGE:
                return ja ? "一度に貼り付けられるのは、フォルダの中身も含めて 50 件・100MB までです。選ぶ数を減らしてください"
                        : "At most 50 items / 100MB (folder contents included) can be pasted at once. Please select fewer";
            case SMB:
                return ja ? "SMB への貼り付け・SMB からの切り取りは次の版で対応します"
                        : "SMB is supported in a later version";
            case ALL_TAB_DISABLED:
                return ja ? "フォルダのタブを開いてください" : "Open a folder tab";
            case EMPTY:
            default:
                return ja ? "クリップボードは空です" : "The clipboard is empty";
        }
    }
}
