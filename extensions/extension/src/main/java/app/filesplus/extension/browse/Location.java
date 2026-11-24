package app.filesplus.extension.browse;

import java.io.File;
import java.util.Objects;

/**
 * A place in the local storage or on an SMB share (DESIGN 5.11.5). Immutable. The string form is stable because it
 * is stored in the move history: local is the absolute path (compatible with the old history), SMB is
 * {@code smb://<connection id>/<share>/<path>}. Connection ids are UUIDs and share names never contain '/'.
 */
public final class Location {
    public enum Kind { LOCAL, SMB }

    private static final String SMB_PREFIX = "smb://";

    public final Kind kind;
    /** SMB only: the saved connection's id, otherwise null. */
    public final String connectionId;
    /** SMB only: the share name, otherwise null. */
    public final String share;
    /** LOCAL: the absolute path. SMB: the path inside the share without leading or trailing '/' ("" = share root). */
    public final String path;

    private Location(Kind kind, String connectionId, String share, String path) {
        this.kind = kind;
        this.connectionId = connectionId;
        this.share = share;
        this.path = path;
    }

    public static Location local(String absolutePath) {
        String p = cleanLocal(absolutePath);
        return p == null ? null : new Location(Kind.LOCAL, null, null, p);
    }

    public static Location local(File file) {
        return file == null ? null : local(file.getPath());
    }

    public static Location smb(String connectionId, String share, String path) {
        // Both are single plain names (no separators, line breaks, "." or "..").
        if (!validName(connectionId) || !validName(share)) return null;
        String p = cleanSmbPath(path);
        return p == null ? null : new Location(Kind.SMB, connectionId, share, p);
    }

    /** Parses the string form; null for anything else (so unreadable history lines are dropped). */
    public static Location parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith(SMB_PREFIX)) {
            String rest = s.substring(SMB_PREFIX.length());
            int first = rest.indexOf('/');
            if (first <= 0) return null;
            String id = rest.substring(0, first);
            String afterId = rest.substring(first + 1);
            int second = afterId.indexOf('/');
            String share = second < 0 ? afterId : afterId.substring(0, second);
            String path = second < 0 ? "" : afterId.substring(second + 1);
            return smb(id, share, path);
        }
        return local(s);
    }

    public boolean isLocal() {
        return kind == Kind.LOCAL;
    }

    public boolean isSmb() {
        return kind == Kind.SMB;
    }

    /** The local path as a File. Only valid for LOCAL. */
    public File file() {
        if (kind != Kind.LOCAL) throw new IllegalStateException("Not a local location: " + this);
        return new File(path);
    }

    /** The last path segment; for an SMB share root the share name; for the local root "/". */
    public String name() {
        if (kind == Kind.LOCAL) {
            if (path.equals("/")) return path;
            return path.substring(path.lastIndexOf('/') + 1);
        }
        if (path.isEmpty()) return share;
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** The containing folder, or null at the local root or an SMB share root. */
    public Location parent() {
        if (kind == Kind.LOCAL) {
            if (path.equals("/")) return null;
            int cut = path.lastIndexOf('/');
            return new Location(Kind.LOCAL, null, null, cut <= 0 ? "/" : path.substring(0, cut));
        }
        if (path.isEmpty()) return null;
        int cut = path.lastIndexOf('/');
        return new Location(Kind.SMB, connectionId, share, cut < 0 ? "" : path.substring(0, cut));
    }

    /** A direct child; null when {@code name} is not a single plain file name. */
    public Location child(String name) {
        if (!validName(name)) return null;
        if (kind == Kind.LOCAL) {
            return new Location(Kind.LOCAL, null, null, path.equals("/") ? "/" + name : path + "/" + name);
        }
        return new Location(Kind.SMB, connectionId, share, path.isEmpty() ? name : path + "/" + name);
    }

    /** A single path segment: not empty, no separators or line breaks, not "." or "..". */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\n' || c == '\r' || c == 0) return false;
        }
        return true;
    }

    /** Whether this equals {@code ancestor} or lies below it (same kind, connection and share). */
    public boolean isWithin(Location ancestor) {
        if (ancestor == null || kind != ancestor.kind) return false;
        if (kind == Kind.SMB && !(connectionId.equals(ancestor.connectionId) && share.equals(ancestor.share))) {
            return false;
        }
        if (path.equals(ancestor.path)) return true;
        if (kind == Kind.LOCAL && ancestor.path.equals("/")) return true;
        if (kind == Kind.SMB && ancestor.path.isEmpty()) return true;
        return path.startsWith(ancestor.path + "/");
    }

    @Override public String toString() {
        if (kind == Kind.LOCAL) return path;
        return SMB_PREFIX + connectionId + "/" + share + (path.isEmpty() ? "" : "/" + path);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Location)) return false;
        Location o = (Location) other;
        return kind == o.kind && Objects.equals(connectionId, o.connectionId) && Objects.equals(share, o.share)
                && path.equals(o.path);
    }

    @Override public int hashCode() {
        return Objects.hash(kind, connectionId, share, path);
    }

    /** Absolute, no trailing separator (except the root), no "." or ".." segments, no line breaks. */
    private static String cleanLocal(String raw) {
        if (raw == null) return null;
        String p = raw.trim();
        if (!p.startsWith("/") || p.indexOf('\n') >= 0 || p.indexOf('\r') >= 0 || p.indexOf(0) >= 0) return null;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.equals("/")) return p;
        for (String segment : p.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return null;
        }
        return p;
    }

    /** Relative to the share root; null for "." or ".." segments, empty segments inside, or line breaks. */
    private static String cleanSmbPath(String raw) {
        if (raw == null) return "";
        String p = raw.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return "";
        for (String segment : p.split("/", -1)) {
            if (!validName(segment)) return null;
        }
        return p;
    }
}
