package app.filesplus.extension.browse;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;

import app.filesplus.extension.ModSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared local-storage folder resolution: volume roots, canonical folder paths and MediaStore bucket to folder.
 * Extracted unchanged from the folder tabs (FolderTabContext) so the destination browser resolves folders the
 * same way. Pure static helpers; nothing here may run on the UI thread when it touches MediaStore.
 */
public final class LocalFolders {
    /** SharedPreferences key prefix of the known-folders sets (DESIGN 5.9), one string set per app category. */
    public static final String KNOWN_PREFIX = "tabs.knownFolders.";

    private LocalFolders() {}

    /** Canonical roots of all storage volumes, the primary external storage first. */
    public static List<File> roots(Context context) throws IOException {
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory().getCanonicalFile());
        StorageManager manager = context.getSystemService(StorageManager.class);
        for (StorageVolume volume : manager.getStorageVolumes()) {
            File directory = volume.getDirectory();
            if (directory != null && !roots.contains(directory.getCanonicalFile())) roots.add(directory.getCanonicalFile());
        }
        return roots;
    }

    /** The storage volume root that contains {@code folder} (same canonical roots as the folder tabs). */
    public static File volumeRoot(Context context, File folder) throws IOException {
        File canonical = folder.getCanonicalFile();
        File best = null;
        for (File root : roots(context)) {
            if (canonical.getPath().startsWith(root.getPath() + File.separator)
                    && (best == null || root.getPath().length() > best.getPath().length())) best = root;
        }
        return best;
    }

    /** The canonical path of an existing, non-linked folder inside a mounted volume, otherwise null. */
    public static String folderPath(Context context, File folder) throws IOException {
        return folderPath(folder, roots(context));
    }

    public static String folderPath(File folder, List<File> volumes) throws IOException {
        if (Files.isSymbolicLink(folder.toPath()) || !folder.isDirectory()) return null;
        File canonical = folder.getCanonicalFile();
        for (File volume : volumes) {
            if (canonical.equals(volume) || canonical.getPath().startsWith(volume.getPath() + File.separator))
                return canonical.getPath();
        }
        return null;
    }

    /** The folder for a MediaStore (RELATIVE_PATH, volume name) pair, or null when the volume is not mounted. */
    public static File relativeFolder(Context context, String relative, String volumeName) {
        if (relative == null || volumeName == null) return null;
        StorageManager manager = context.getSystemService(StorageManager.class);
        for (StorageVolume volume : manager.getStorageVolumes()) {
            if (volumeName.equals(volume.getMediaStoreVolumeName()) && volume.getDirectory() != null)
                return new File(volume.getDirectory(), relative);
        }
        return null;
    }

    /**
     * Resolves a bucket display name (case-insensitive) to its single folder. This version of the app identifies
     * folder tabs by parent name rather than BUCKET_ID; same-name buckets that differ are never guessed between,
     * and null is returned for no match, an ambiguous match or an inaccessible folder.
     */
    public static String folderOfBucketName(Context context, String name) throws IOException {
        String match = null;
        try (Cursor cursor = context.getContentResolver().query(MediaStore.Files.getContentUri("external"),
                new String[]{"_data", "relative_path", "volume_name"},
                "bucket_display_name = ? COLLATE NOCASE", new String[]{name}, null)) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                String data = cursor.getString(0);
                File parent = data == null ? relativeFolder(context, cursor.getString(1), cursor.getString(2))
                        : new File(data).getParentFile();
                String found = parent == null ? null : folderPath(context, parent);
                if (found == null) return null;
                if (match != null && !match.equals(found)) return null;
                match = found;
            }
        }
        return match;
    }

    /**
     * Folders that once held files of the given app category tab (CATEGORY_IMAGE, CATEGORY_VIDEO, ...), recorded
     * by the folder-tab hook. Returns a copy; entries may no longer exist.
     */
    public static Set<String> knownFolders(String appCategory) {
        SharedPreferences prefs = ModSettings.prefs();
        if (prefs == null) return Collections.emptySet();
        return new HashSet<>(prefs.getStringSet(KNOWN_PREFIX + appCategory, Collections.<String>emptySet()));
    }
}
