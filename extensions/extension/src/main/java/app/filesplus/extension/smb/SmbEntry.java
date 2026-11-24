package app.filesplus.extension.smb;

/** One directory entry as seen through {@link SmbVfs}. Immutable. */
public final class SmbEntry {
    public final String name;
    public final boolean isDir;
    public final long size;
    public final long mtimeMillis;

    public SmbEntry(String name, boolean isDir, long size, long mtimeMillis) {
        this.name = name;
        this.isDir = isDir;
        this.size = size;
        this.mtimeMillis = mtimeMillis;
    }
}
