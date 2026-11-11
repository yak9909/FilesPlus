package app.filesplus.extension.smb;

import java.util.UUID;

/** Immutable saved connection. The password is deliberately not a field (see {@link SmbConnectionStore}). */
public final class SmbConnection {
    public static final String OPEN_START = "START";
    public static final String OPEN_LAST = "LAST";

    public final String id;
    public final String displayName;
    public final String host;
    public final int port;
    public final String share;
    public final String startPath;
    public final String user;
    public final String domain;
    public final String openWhere;
    public final String lastPath;

    public SmbConnection(String id, String displayName, String host, int port, String share, String startPath,
            String user, String domain, String openWhere, String lastPath) {
        this.id = id == null || id.isEmpty() ? newId() : id;
        this.displayName = clean(displayName);
        this.host = clean(host);
        this.port = port > 0 && port <= 65535 ? port : SmbClientFactory.DEFAULT_PORT;
        this.share = clean(share);
        this.startPath = clean(startPath);
        this.user = clean(user);
        this.domain = clean(domain);
        this.openWhere = OPEN_START.equals(openWhere) ? OPEN_START : OPEN_LAST;
        this.lastPath = clean(lastPath);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public SmbConnection withDisplayName(String name) {
        return new SmbConnection(id, name, host, port, share, startPath, user, domain, openWhere, lastPath);
    }

    public SmbConnection withLastPath(String path) {
        return new SmbConnection(id, displayName, host, port, share, startPath, user, domain, openWhere, path);
    }

    /** Display name, or "host/share" when none was given. */
    public String label() {
        return displayName.isEmpty() ? host + "/" + share : displayName;
    }

    /** Folder to open first: the last folder when requested and known, else the start folder. */
    public String initialPath() {
        return OPEN_LAST.equals(openWhere) && !lastPath.isEmpty() ? lastPath : startPath;
    }

    /** Everything that decides which server session is needed (not the display name or folders). */
    public String targetKey() {
        return host + '\n' + port + '\n' + share + '\n' + user + '\n' + domain;
    }

    /** Who receives the saved password: server, port, user and domain (not the share or folders). */
    public String credentialKey() {
        return host.toLowerCase(java.util.Locale.ROOT) + '\n' + port + '\n' + user + '\n' + domain;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
