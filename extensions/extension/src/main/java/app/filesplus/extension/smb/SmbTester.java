package app.filesplus.extension.smb;

import app.filesplus.extension.DiagLog;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.Share;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** One-shot connection check used by the diagnostics screen. Blocking: call off the main thread. */
public final class SmbTester {
    private static final int MAX_NAMES = 10;
    private static final int PROBE_BYTES = 1024;
    private static final int MAX_ERROR_CHARS = 240;

    private SmbTester() {}

    /** Returns a short multi-line report, or a one-line error. Never throws and never includes the password. */
    public static String run(String host, int port, String user, String password, String domain,
            String share, String path) {
        long started = System.currentTimeMillis();
        host = trim(host);
        if (host.isEmpty()) return "Error: host is empty";
        if (trim(share).isEmpty()) return "Error: share name is empty";
        SMBClient client = null;
        Connection connection = null;
        Session session = null;
        Share connected = null;
        try {
            client = SmbClientFactory.create();
            connection = client.connect(host, port > 0 ? port : SmbClientFactory.DEFAULT_PORT);
            session = connection.authenticate(context(user, password, domain));
            String dialect = String.valueOf(connection.getNegotiatedProtocol().getDialect());
            connected = session.connectShare(trim(share));
            if (!(connected instanceof DiskShare)) {
                return "Error: '" + trim(share) + "' is not a disk share (" + connected.getClass().getSimpleName() + ")";
            }
            DiskShare disk = (DiskShare) connected;
            StringBuilder report = new StringBuilder("Dialect: ").append(dialect);
            String dir = normalize(path);
            List<FileIdBothDirectoryInformation> entries = new ArrayList<>();
            for (FileIdBothDirectoryInformation entry : disk.list(dir)) {
                String name = entry.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;
                entries.add(entry);
            }
            report.append("\nListing of \\").append(dir).append(": ").append(entries.size()).append(" entries");
            FileIdBothDirectoryInformation firstFile = null;
            for (int i = 0; i < entries.size(); i++) {
                FileIdBothDirectoryInformation entry = entries.get(i);
                boolean directory = isDirectory(entry);
                if (i < MAX_NAMES) {
                    report.append("\n  ").append(directory ? "[D] " : "    ").append(entry.getFileName());
                }
                if (firstFile == null && !directory) firstFile = entry;
            }
            if (entries.size() > MAX_NAMES) report.append("\n  ...");
            if (firstFile == null) {
                report.append("\nRead: no files");
            } else {
                String file = (dir.isEmpty() ? "" : dir + "\\") + firstFile.getFileName();
                // A failed read must not hide the listing above.
                try {
                    report.append("\nRead: ").append(readProbe(disk, file)).append(" bytes from ")
                            .append(firstFile.getFileName());
                } catch (Throwable t) {
                    report.append("\nRead: failed (").append(describe(t)).append(')');
                }
            }
            long elapsed = System.currentTimeMillis() - started;
            report.append("\nElapsed: ").append(elapsed).append(" ms");
            DiagLog.i("smb", "Test OK, " + dialect + ", " + elapsed + " ms");
            return report.toString();
        } catch (Throwable t) {
            String line = describe(t);
            DiagLog.i("smb", "Test failed: " + line);
            return "Error: " + line;
        } finally {
            // Close failures must not turn a finished report into an error.
            closeQuietly(connected);
            closeQuietly(session);
            closeQuietly(connection);
            closeQuietly(client);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // Nothing useful to report.
        }
    }

    private static AuthenticationContext context(String user, String password, String domain) {
        String name = trim(user);
        if (name.isEmpty()) return AuthenticationContext.guest();
        return new AuthenticationContext(name, password == null ? new char[0] : password.toCharArray(), trim(domain));
    }

    private static int readProbe(DiskShare disk, String file) throws java.io.IOException {
        try (File handle = disk.openFile(file, EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
            int count = handle.read(new byte[PROBE_BYTES], 0);
            return Math.max(count, 0);
        }
    }

    private static boolean isDirectory(FileIdBothDirectoryInformation entry) {
        return (entry.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
    }

    /** smbj paths are relative to the share root with backslash separators. */
    private static String normalize(String path) {
        String result = trim(path).replace('/', '\\');
        while (result.startsWith("\\")) result = result.substring(1);
        while (result.endsWith("\\")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String describe(Throwable t) {
        String line = brief(t);
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        if (root != t) line += " <- " + brief(root);
        return line.length() > MAX_ERROR_CHARS ? line.substring(0, MAX_ERROR_CHARS) + "..." : line;
    }

    private static String brief(Throwable t) {
        String message = t.getMessage();
        String name = t.getClass().getSimpleName();
        return message == null || message.isEmpty() ? name : name + ": " + message.replace('\n', ' ');
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
