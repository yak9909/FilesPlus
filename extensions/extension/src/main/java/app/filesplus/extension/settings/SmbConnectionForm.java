package app.filesplus.extension.settings;

import app.filesplus.extension.smb.SmbConnection;
import app.filesplus.extension.smb.SmbPath;

/** Draft fields and validation shared by auto-save and the connection test. */
final class SmbConnectionForm {
    enum Error { HOST, PORT, SHARE, PATH, PASSWORD, CHANGED_CREDENTIALS }

    final String id;
    String name = "", host = "", port = "445", share = "", start = "", user = "", password = "", domain = "";
    boolean openStart;

    SmbConnectionForm(SmbConnection existing, String id) {
        this.id = id == null ? SmbConnection.newId() : id;
        if (existing == null) return;
        name = existing.displayName;
        host = existing.host;
        port = String.valueOf(existing.port);
        share = existing.share;
        start = existing.startPath;
        user = existing.user;
        domain = existing.domain;
        openStart = SmbConnection.OPEN_START.equals(existing.openWhere);
    }

    Error error(SmbConnection saved, boolean hasPassword, boolean requirePassword) {
        if (host.trim().isEmpty()) return Error.HOST;
        int number = portNumber();
        if (number < 1 || number > 65535) return Error.PORT;
        if (share.indexOf('/') >= 0 || share.indexOf('\\') >= 0) return Error.SHARE;
        try {
            SmbPath.normalize(start);
        } catch (IllegalArgumentException e) {
            return Error.PATH;
        }
        return requirePassword ? passwordError(connection(), !password.isEmpty(), saved, hasPassword) : null;
    }

    static Error passwordError(SmbConnection next, boolean typed, SmbConnection saved, boolean hasPassword) {
        if (next.user.isEmpty() || typed) return null;
        if (saved != null && !next.credentialKey().equals(saved.credentialKey())) return Error.CHANGED_CREDENTIALS;
        return hasPassword ? null : Error.PASSWORD;
    }

    static boolean needsSave(SmbConnection next, SmbConnection previous, String typedPassword) {
        return typedPassword != null && !typedPassword.isEmpty() || !next.sameSettings(previous);
    }

    SmbConnection connection() {
        return new SmbConnection(id, name, host, portNumber(), share, SmbPath.normalize(start), user, domain,
                openStart ? SmbConnection.OPEN_START : SmbConnection.OPEN_LAST, "");
    }

    private int portNumber() {
        try {
            return Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
