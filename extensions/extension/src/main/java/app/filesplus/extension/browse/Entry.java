package app.filesplus.extension.browse;

/** One row of a folder listing or category listing. Immutable value; produced off the UI thread. */
public final class Entry {
    public final Location location;
    public final String name;
    public final boolean directory;
    /** Bytes; 0 for folders and for entries built from a bucket aggregate (size is not queried there). */
    public final long size;
    /** Last modified, milliseconds since the epoch; 0 when unknown. */
    public final long modified;
    /** By extension (or MediaStore type); null for folders and unknown types. */
    public final Category category;
    /** MediaStore _id on the local index, 0 when the entry has none (always 0 for SMB). */
    public final long mediaId;
    /** MediaStore volume name of {@link #mediaId} ("external_primary", ...); null when mediaId is 0. */
    public final String mediaVolume;

    public Entry(Location location, String name, boolean directory, long size, long modified, Category category,
            long mediaId, String mediaVolume) {
        this.location = location;
        this.name = name;
        this.directory = directory;
        this.size = size;
        this.modified = modified;
        this.category = category;
        this.mediaId = mediaId;
        this.mediaVolume = mediaVolume;
    }
}
