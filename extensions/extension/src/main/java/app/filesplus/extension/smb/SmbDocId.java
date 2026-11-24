package app.filesplus.extension.smb;

/**
 * Document id of the SMB provider: "&lt;connectionId&gt;:&lt;path&gt;" with a normalized share-relative path.
 * The root of a connection is "&lt;connectionId&gt;:". Connection ids are UUIDs, so the first ':' separates them.
 * Pure Java (no Android types).
 */
public final class SmbDocId {
    public final String connectionId;
    /** Normalized share-relative path; "" is the share root. */
    public final String path;

    private SmbDocId(String connectionId, String path) {
        this.connectionId = connectionId;
        this.path = path;
    }

    public static String encode(String connectionId, String path) {
        return connectionId + ":" + SmbPath.normalize(path);
    }

    public static String root(String connectionId) {
        return connectionId + ":";
    }

    /** Throws IllegalArgumentException for malformed ids and for paths that escape the share. */
    public static SmbDocId decode(String documentId) {
        if (documentId == null) throw new IllegalArgumentException("Missing document id");
        int cut = documentId.indexOf(':');
        if (cut <= 0) throw new IllegalArgumentException("Malformed document id");
        return new SmbDocId(documentId.substring(0, cut), SmbPath.normalize(documentId.substring(cut + 1)));
    }

    public boolean isRoot() {
        return path.isEmpty();
    }

    /** True when other is strictly inside this document (same connection, deeper path). */
    public boolean isAncestorOf(SmbDocId other) {
        if (!connectionId.equals(other.connectionId) || other.path.length() <= path.length()) return false;
        return path.isEmpty() || (other.path.startsWith(path) && other.path.charAt(path.length()) == '/');
    }
}
