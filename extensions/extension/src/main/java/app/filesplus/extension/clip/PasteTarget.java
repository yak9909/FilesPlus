package app.filesplus.extension.clip;

import android.content.Context;
import android.os.Environment;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.browse.Category;
import app.filesplus.extension.browse.LocalFolders;
import app.filesplus.extension.browse.Location;
import app.filesplus.extension.move.DestinationHistory;
import app.filesplus.extension.ui.ProtoNode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a paste would go (DESIGN 5.12.2). Immutable; {@link PasteRules#check} decides whether the clipboard may
 * be pasted there. Build one with {@link #ofFolder}, {@link #ofAllTab} or {@link #ofLocation}.
 */
public final class PasteTarget {
    /** Values of the setting {@code paste.allTab}. */
    public static final String ALL_TAB_OFF = "off";
    public static final String ALL_TAB_DEFAULT = "default";
    public static final String ALL_TAB_LAST = "last";
    public static final String ALL_TAB_ASK = "ask";

    private final File folder;
    private final boolean smb;
    private final boolean allTab;
    private final boolean needsPicker;
    private final boolean createIfMissing;
    private final List<File> roots;

    private PasteTarget(File folder, boolean smb, boolean allTab, boolean needsPicker, boolean createIfMissing,
                        List<File> roots) {
        this.folder = folder;
        this.smb = smb;
        this.allTab = allTab;
        this.needsPicker = needsPicker;
        this.createIfMissing = createIfMissing;
        this.roots = roots;
    }

    /** A local folder: the storage browser's current folder or a folder tab's folder. */
    public static PasteTarget ofFolder(File folder) {
        return new PasteTarget(folder, false, false, false, false, null);
    }

    /**
     * Same, with explicit volume roots (the canonical root folders of all storage volumes) instead of the
     * device's. For tests that run without Android.
     */
    public static PasteTarget ofFolder(File folder, List<File> volumeRoots) {
        return new PasteTarget(folder, false, false, false, false, new ArrayList<>(volumeRoots));
    }

    /** A folder that is created (one level, below an existing folder) when it is missing at paste time. */
    public static PasteTarget ofFolder(File folder, boolean createIfMissing) {
        return new PasteTarget(folder, false, false, false, createIfMissing, null);
    }

    /** A place on an SMB share or any other non-local place: never pastable until S9. */
    public static PasteTarget ofLocation(Location location) {
        if (location == null) return new PasteTarget(null, false, false, false, false, null);
        if (location.isSmb()) return nonLocal();
        return ofFolder(location.file());
    }

    static PasteTarget nonLocal() {
        return new PasteTarget(null, true, false, false, false, null);
    }

    static PasteTarget ofStorageFolder(byte[] folder) {
        if (folder == null) return ofFolder(null);
        ClipSources.Source source = ClipSources.folder(ProtoNode.decode(folder));
        return source.local() ? ofFolder(new File(source.path)) : nonLocal();
    }

    /**
     * The media browser's "All" tab. {@code setting} is the value of {@code paste.allTab}:
     * {@code off} (default; also any unknown value) cannot be pasted, {@code default} pastes into the category's
     * own folder (created when missing), {@code last} into the last pasted folder (cannot be pasted when there is
     * none or it is gone), {@code ask} needs the user to pick a folder first ({@link #needsPicker}).
     */
    public static PasteTarget ofAllTab(Category category, String setting) {
        File defaultFolder = null;
        File last = null;
        try {
            if (ALL_TAB_DEFAULT.equals(setting)) defaultFolder = defaultFolder(category);
            else if (ALL_TAB_LAST.equals(setting)) last = DestinationHistory.last();
        } catch (Throwable t) {
            DiagLog.e("clip", "Cannot resolve the All tab target", t);
        }
        return ofAllTab(category, setting, defaultFolder, last);
    }

    /** Same with the folders already resolved (no Android calls): for tests. */
    public static PasteTarget ofAllTab(Category category, String setting, File defaultFolder, File lastFolder) {
        return ofAllTab(category, setting, defaultFolder, lastFolder, null);
    }

    /** Same with explicit volume roots too ({@code null} = the device's). */
    public static PasteTarget ofAllTab(Category category, String setting, File defaultFolder, File lastFolder,
                                       List<File> volumeRoots) {
        List<File> roots = volumeRoots == null ? null : new ArrayList<>(volumeRoots);
        if (ALL_TAB_ASK.equals(setting)) return new PasteTarget(null, false, true, true, false, roots);
        if (ALL_TAB_DEFAULT.equals(setting) && defaultFolder != null && category != null) {
            return new PasteTarget(defaultFolder, false, true, false, true, roots);
        }
        if (ALL_TAB_LAST.equals(setting) && lastFolder != null && lastFolder.isDirectory()) {
            return new PasteTarget(lastFolder, false, true, false, false, roots);
        }
        return new PasteTarget(null, false, true, false, false, roots);
    }

    /** The category's standard folder: Pictures, Movies, Music, Documents or Download. */
    static File defaultFolder(Category category) {
        if (category == null) return null;
        String directory;
        switch (category) {
            case IMAGES: directory = Environment.DIRECTORY_PICTURES; break;
            case VIDEOS: directory = Environment.DIRECTORY_MOVIES; break;
            case AUDIO: directory = Environment.DIRECTORY_MUSIC; break;
            case DOCUMENTS: directory = Environment.DIRECTORY_DOCUMENTS; break;
            case DOWNLOADS: directory = Environment.DIRECTORY_DOWNLOADS; break;
            default: return null;
        }
        return Environment.getExternalStoragePublicDirectory(directory);
    }

    /** The destination folder; null for SMB, for an "All" tab that cannot be pasted into and for "ask". */
    public File folder() { return folder; }

    public boolean isSmb() { return smb; }

    public boolean isAllTab() { return allTab; }

    /** "All" tab set to {@code ask}: the caller must let the user pick a folder and then check {@link #ofFolder}. */
    public boolean needsPicker() { return needsPicker; }

    /** The folder may be missing; paste creates it (one level) when its parent exists. */
    public boolean createIfMissing() { return createIfMissing; }

    /** Canonical roots of all storage volumes. Empty when the device's volumes cannot be read. */
    List<File> roots() {
        if (roots != null) return roots;
        try {
            Context context = ModKit.context();
            if (context != null) return LocalFolders.roots(context);
        } catch (Throwable t) {
            DiagLog.e("clip", "Cannot read the storage volumes", t);
        }
        return new ArrayList<>();
    }
}
