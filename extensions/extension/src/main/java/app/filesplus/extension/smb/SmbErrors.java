package app.filesplus.extension.smb;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Classifies SMB failures and turns them into short one-line messages (never a stack trace). */
public final class SmbErrors {
    private static final int MAX_CHARS = 240;
    private static final int MAX_DEPTH = 8;
    // Wrong credentials or an unusable account: the only cases where asking for new credentials makes sense.
    private static final Set<String> AUTH = new HashSet<>(Arrays.asList("STATUS_LOGON_FAILURE",
            "STATUS_WRONG_PASSWORD", "STATUS_ACCOUNT_DISABLED", "STATUS_ACCOUNT_LOCKED_OUT",
            "STATUS_ACCOUNT_EXPIRED", "STATUS_ACCOUNT_RESTRICTION", "STATUS_PASSWORD_EXPIRED",
            "STATUS_PASSWORD_MUST_CHANGE", "STATUS_LOGON_TYPE_NOT_GRANTED", "STATUS_NO_LOGON_SERVERS"));
    // The connection or session is gone; a fresh connection may work.
    private static final Set<String> BROKEN = new HashSet<>(Arrays.asList("STATUS_NETWORK_NAME_DELETED",
            "STATUS_USER_SESSION_DELETED", "STATUS_NETWORK_SESSION_EXPIRED", "STATUS_CONNECTION_DISCONNECTED",
            "STATUS_CONNECTION_RESET"));

    private SmbErrors() {}

    /** Raised when a user name is saved but its password is missing or cannot be decrypted. */
    public static final class PasswordMissingException extends IOException {
        public PasswordMissingException() {
            super("Saved password is missing; enter it again");
        }
    }

    /**
     * Raised when the server's list of shares cannot be read (access denied, no srvsvc service). The user can
     * still connect by typing the share name.
     */
    public static final class ShareListException extends IOException {
        public ShareListException(String message) {
            super(message);
        }

        public ShareListException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** True when the share list is unavailable but the server itself answered. */
    public static boolean isShareListUnavailable(Throwable t) {
        return find(t, ShareListException.class) != null;
    }

    /**
     * Wraps the failures that mean "this server does not give out its share list" (refused IPC$, missing srvsvc
     * pipe) so callers can tell the user to type the share name. Returns null for any other failure.
     */
    public static ShareListException asShareListFailure(Throwable t) {
        ShareListException existing = find(t, ShareListException.class);
        if (existing != null) return existing;
        if (hasStatus(t, "STATUS_ACCESS_DENIED") || hasStatus(t, "STATUS_OBJECT_NAME_NOT_FOUND")
                || hasStatus(t, "STATUS_BAD_NETWORK_NAME") || hasStatus(t, "STATUS_NOT_SUPPORTED")
                || hasStatus(t, "STATUS_PIPE_NOT_AVAILABLE") || hasStatus(t, "STATUS_OBJECT_PATH_NOT_FOUND")) {
            return new ShareListException("The server does not list its shares (" + describe(t) + ")", t);
        }
        return null;
    }

    /** True when the saved password is missing or unreadable (not a server answer). */
    public static boolean isPasswordMissing(Throwable t) {
        return find(t, PasswordMissingException.class) != null;
    }

    /** True for wrong password, disabled account and a missing saved password. */
    public static boolean isAuthFailure(Throwable t) {
        if (isPasswordMissing(t)) return true;
        SMBApiException api = find(t, SMBApiException.class);
        return api != null && AUTH.contains(statusName(api));
    }

    /** True when the failure carries the given NTSTATUS name, e.g. "STATUS_BAD_NETWORK_NAME". */
    public static boolean hasStatus(Throwable t, String name) {
        SMBApiException api = find(t, SMBApiException.class);
        return api != null && name.equals(statusName(api));
    }

    /** True when the failure is a missing share or a missing path. */
    public static boolean isNotFound(Throwable t) {
        return hasStatus(t, "STATUS_BAD_NETWORK_NAME") || hasStatus(t, "STATUS_OBJECT_NAME_NOT_FOUND")
                || hasStatus(t, "STATUS_OBJECT_PATH_NOT_FOUND");
    }

    /** True when the server cannot be reached at all (not an SMB status from a reachable server). */
    public static boolean isUnreachable(Throwable t) {
        return find(t, ConnectException.class) != null || find(t, UnknownHostException.class) != null
                || find(t, NoRouteToHostException.class) != null || find(t, SocketTimeoutException.class) != null
                || (find(t, SMBApiException.class) == null && find(t, TransportException.class) != null);
    }

    /** True when the session or connection died, so one retry on a new connection is worth it. */
    public static boolean isConnectionBroken(Throwable t) {
        // A refused or unroutable connect is a fresh failure, not a dead session.
        if (find(t, ConnectException.class) != null || find(t, NoRouteToHostException.class) != null) return false;
        if (find(t, TransportException.class) != null || find(t, SocketException.class) != null
                || find(t, EOFException.class) != null || find(t, ClosedChannelException.class) != null) {
            return true;
        }
        SMBApiException api = find(t, SMBApiException.class);
        return api != null && BROKEN.contains(statusName(api));
    }

    /** One line such as "SMBApiException: STATUS_LOGON_FAILURE" or "ConnectException: ... <- root cause". */
    public static String describe(Throwable t) {
        SMBApiException api = find(t, SMBApiException.class);
        String line;
        if (api != null) {
            line = api.getClass().getSimpleName() + ": " + statusName(api);
        } else {
            line = brief(t);
            Throwable root = t;
            for (int i = 0; i < MAX_DEPTH && root.getCause() != null && root.getCause() != root; i++) root = root.getCause();
            if (root != t) line += " <- " + brief(root);
        }
        return line.length() > MAX_CHARS ? line.substring(0, MAX_CHARS) + "..." : line;
    }

    private static String statusName(SMBApiException api) {
        // smbj maps codes it does not know to STATUS_OTHER; show the raw code instead.
        NtStatus status = api.getStatus();
        if (status != null && status != NtStatus.STATUS_OTHER) return status.name();
        return String.format("STATUS_0x%08X", api.getStatusCode());
    }

    private static String brief(Throwable t) {
        String message = t.getMessage();
        String name = t.getClass().getSimpleName();
        return message == null || message.isEmpty() ? name : name + ": " + message.replace('\n', ' ');
    }

    private static <T extends Throwable> T find(Throwable t, Class<T> type) {
        for (int i = 0; i < MAX_DEPTH && t != null; i++) {
            if (type.isInstance(t)) return type.cast(t);
            t = t.getCause() == t ? null : t.getCause();
        }
        return null;
    }
}
