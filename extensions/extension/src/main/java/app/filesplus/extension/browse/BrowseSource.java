package app.filesplus.extension.browse;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;

import java.io.IOException;
import java.util.List;

/**
 * The reading side of a place the destination browser can show (DESIGN 5.11.5): local storage with the
 * MediaStore index, or an SMB share. The writing side is a separate interface (the file-operation engine).
 * Every method except the two flags does I/O and must be called from a background thread.
 */
public interface BrowseSource {
    /** Whether {@link #buckets} and {@link #all} work (local true, SMB false). */
    boolean hasMediaIndex();

    /** Whether a destination can be picked here (SMB: false until the verified copy exists). */
    boolean canPickDestination();

    /** Volumes (local) or shares (SMB), in display order. */
    List<Location> roots() throws IOException;

    /** Direct children of a folder, folders first; a non-null filter keeps folders and files of that category. */
    List<Entry> list(Location dir, Category filterOrNull) throws IOException;

    /** Folders holding files of the category, newest first. Only when {@link #hasMediaIndex()}. */
    List<Bucket> buckets(Category category) throws IOException;

    /** The category's files, newest first. Only when {@link #hasMediaIndex()}. */
    List<Entry> all(Category category, int offset, int limit) throws IOException;

    /** A thumbnail of about {@code px} pixels, or null when there is none or the signal was cancelled. */
    Bitmap thumbnail(Entry entry, int px, CancellationSignal signal);

    /** A content URI the app's preview can open, or null. */
    Uri viewUri(Entry entry);

    /** Creates one folder level. Fails (IOException) when it already exists: nothing is ever overwritten. */
    Location mkdir(Location parent, String name) throws IOException;
}
