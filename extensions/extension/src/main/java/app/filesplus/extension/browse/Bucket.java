package app.filesplus.extension.browse;

/** A folder that holds files of one category: one row of the category screen (DESIGN 5.11.4 3). */
public final class Bucket {
    public final Location folder;
    public final String name;
    /** Files of the category directly inside the folder; 0 for a known folder that is currently empty. */
    public final int count;
    /** The newest file of the category (its thumbnail), or null when {@link #count} is 0. */
    public final Entry newest;

    public Bucket(Location folder, String name, int count, Entry newest) {
        this.folder = folder;
        this.name = name;
        this.count = count;
        this.newest = newest;
    }
}
