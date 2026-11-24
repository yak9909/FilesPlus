package app.filesplus.extension.smb;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure path helpers (no Android or smbj types). Internal paths are share-relative, use '/' and have no
 * leading or trailing separator; the share root is "".
 */
public final class SmbPath {
    private SmbPath() {}

    /**
     * Converts '\' to '/', collapses repeated separators and drops "." segments. Throws
     * IllegalArgumentException for ".." segments and NUL characters (escape attempts).
     */
    public static String normalize(String path) {
        if (path == null) return "";
        if (path.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid character in path");
        List<String> kept = new ArrayList<>();
        for (String segment : path.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) throw new IllegalArgumentException("Path escapes the share root");
            kept.add(segment);
        }
        return String.join("/", kept);
    }

    /** Joins a normalized parent and one child name; the name must be a single plain segment. */
    public static String join(String parent, String name) {
        String child = normalize(name);
        if (child.isEmpty() || child.indexOf('/') >= 0) throw new IllegalArgumentException("Invalid name");
        String base = normalize(parent);
        return base.isEmpty() ? child : base + "/" + child;
    }

    /** Parent of a normalized path ("" for the root and for top-level entries). */
    public static String parent(String path) {
        String p = normalize(path);
        int cut = p.lastIndexOf('/');
        return cut < 0 ? "" : p.substring(0, cut);
    }

    /** Last segment of a normalized path ("" for the root). */
    public static String name(String path) {
        String p = normalize(path);
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** smbj paths are relative to the share root and use backslashes. */
    public static String toSmb(String path) {
        return normalize(path).replace('/', '\\');
    }
}
