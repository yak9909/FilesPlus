package app.filesplus.extension.smb;

import android.os.Looper;

import app.filesplus.extension.DiagLog;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.PipeShare;
import com.hierynomus.smbj.share.Share;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One authenticated share handle per (saved connection, share name), reused between callers and closed after
 * 60 s without users. A connection without a fixed share uses one entry per browsed share plus one for the
 * IPC$ share list. acquire/release are reference counted so a long read keeps its share open. All blocking
 * calls must run off the main thread; close/closeAll only schedule the real close and are safe anywhere.
 */
public final class SmbSessionPool {
    private static final String AREA = "smb";
    /** The share that serves the server's share list (srvsvc pipe). */
    public static final String IPC_SHARE = "IPC$";
    private static final long IDLE_MS = 60_000L;
    private static final long SWEEP_SEC = 15L;

    private static final Object LOCK = new Object();
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static final Map<Share, Entry> LEASES = new IdentityHashMap<>();
    private static final Set<Entry> LIVE = new LinkedHashSet<>();
    private static ScheduledExecutorService executor;

    private SmbSessionPool() {}

    /**
     * LOCK guards registration, lease counts and retirement. The entry monitor serializes connection setup.
     * Once retired, a fully initialized entry is closed by the pool thread after its final lease is released.
     */
    private static final class Entry {
        final String id;
        final String shareName;
        final String key;
        final String tag;
        final long created = SmbDiagnostics.now();
        SMBClient client;
        Connection connection;
        Session session;
        Share share;
        int users;
        long lastUse = System.currentTimeMillis();
        boolean connecting;
        volatile boolean dead;
        String reason;

        Entry(String id, String shareName, String key) {
            this.id = id;
            this.shareName = shareName;
            this.key = key;
            tag = SmbSessionPool.tag(id, shareName) + " entry=" + Integer.toHexString(System.identityHashCode(this));
        }

        boolean alive() {
            return share != null && share.isConnected() && connection != null && connection.isConnected();
        }
    }

    static String tag(String id, String shareName) {
        String share = IPC_SHARE.equalsIgnoreCase(shareName) ? IPC_SHARE : "share#" + Integer.toHexString(shareName.hashCode());
        return SmbDiagnostics.connectionTag(id) + " " + share;
    }

    /** Returns a leased share. A non-null passwordOverride replaces the saved password. */
    public static Share acquire(SmbConnection c, String shareName, String passwordOverride) throws IOException {
        return acquire(c, shareName, passwordOverride, null);
    }

    static Share acquire(SmbConnection c, String shareName, String passwordOverride,
                         SmbDiagnostics.Call operation) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("SMB calls must not run on the main thread");
        }
        SmbDiagnostics.Call timing = SmbDiagnostics.begin("pool acquire");
        Throwable error = null;
        String result = "";
        try {
            while (true) {
                timing.phase("entry lookup");
                Entry entry = entryFor(c, shareName);
                timing.target(entry.tag);
                if (entry.dead) continue;
                timing.phase("entry monitor wait");
                synchronized (entry) {
                    timing.phase("entry check");
                    synchronized (LOCK) {
                        if (entry.dead) continue;
                        if (entry.alive()) {
                            result = "reused";
                            return lease(entry, operation, result);
                        }
                        if (entry.share != null) {
                            retire(entry, "disconnected");
                            continue;
                        }
                        entry.connecting = true;
                    }
                    Share fresh;
                    boolean connected = false;
                    try {
                        timing.phase("connection setup");
                        connect(entry, c, passwordOverride);
                        connected = true;
                    } finally {
                        fresh = finishConnect(entry, operation, connected);
                    }
                    if (fresh != null) {
                        result = "fresh";
                        return fresh;
                    }
                }
            }
        } catch (IOException | RuntimeException | Error e) {
            error = e;
            throw e;
        } finally {
            timing.finish(result, error);
        }
    }

    private static Share finishConnect(Entry entry, SmbDiagnostics.Call operation, boolean connected) {
        synchronized (LOCK) {
            if (!connected) retire(entry, "connect failure");
            entry.connecting = false;
            if (entry.dead) {
                scheduleCloseIfUnused(entry);
                return null;
            }
            // Publish the first lease and refresh its idle timestamp before the sweeper can see it.
            return lease(entry, operation, "fresh");
        }
    }

    private static Share lease(Entry entry, SmbDiagnostics.Call operation, String result) {
        // Keep provenance on a slow operation even when its acquisition was too fast to log separately.
        if (operation != null) operation.target(entry.tag + " " + result);
        LEASES.put(entry.share, entry);
        entry.users++;
        entry.lastUse = System.currentTimeMillis();
        return entry.share;
    }

    private static String mapKey(String id, String shareName) {
        return id + '\n' + shareName;
    }

    /** Called with LOCK held. Active leases survive removal from the reusable entries. */
    private static void retire(Entry entry, String reason) {
        if (entry.dead) return;
        entry.reason = reason;
        entry.dead = true;
        ENTRIES.remove(mapKey(entry.id, entry.shareName), entry);
        scheduleCloseIfUnused(entry);
    }

    /** Pairs with acquire, even after retirement. A release cannot affect a replacement share. */
    public static void release(String id, String shareName, Share share) {
        synchronized (LOCK) {
            Entry entry = LEASES.get(share);
            if (entry == null || !entry.id.equals(id) || !entry.shareName.equals(shareName) || entry.users == 0) return;
            entry.users--;
            entry.lastUse = System.currentTimeMillis();
            if (entry.dead) scheduleCloseIfUnused(entry);
        }
    }

    /** Drops the session only if it is still the one the caller used and found broken. Safe on any thread. */
    public static void invalidate(String id, String shareName, Share share) {
        synchronized (LOCK) {
            Entry entry = LEASES.get(share);
            if (entry != null && entry.id.equals(id) && entry.shareName.equals(shareName)) {
                retire(entry, "invalidated");
            }
        }
    }

    /** Drops every session of one connection (settings changed, deleted). Safe on any thread. */
    public static void close(String id) {
        synchronized (LOCK) {
            for (Entry entry : new ArrayList<>(ENTRIES.values())) {
                if (entry.id.equals(id)) retire(entry, "settings");
            }
        }
    }

    public static void closeAll() {
        synchronized (LOCK) {
            for (Entry entry : new ArrayList<>(ENTRIES.values())) retire(entry, "closeAll");
        }
    }

    private static Entry entryFor(SmbConnection c, String shareName) {
        String mapKey = mapKey(c.id, shareName);
        String target = c.targetKey();
        synchronized (LOCK) {
            ensureSweeper();
            Entry entry = ENTRIES.get(mapKey);
            if (entry != null && !entry.key.equals(target)) {
                retire(entry, "settings replaced");
                entry = null;
            }
            if (entry == null) {
                entry = new Entry(c.id, shareName, target);
                ENTRIES.put(mapKey, entry);
                LIVE.add(entry);
            }
            return entry;
        }
    }

    static List<String> diagnosticEntries() {
        synchronized (LOCK) {
            long now = SmbDiagnostics.now();
            long wallNow = System.currentTimeMillis();
            List<String> entries = new ArrayList<>(LIVE.size());
            for (Entry entry : LIVE) {
                entries.add(entry.tag + " users=" + entry.users + " dead=" + entry.dead
                        + " age=" + (now - entry.created) + "ms idle=" + (wallNow - entry.lastUse) + "ms"
                        + (entry.connecting ? " connecting" : "") + (entry.dead ? " " + entry.reason : ""));
            }
            return entries;
        }
    }

    private static void connect(Entry entry, SmbConnection c, String passwordOverride) throws IOException {
        SmbDiagnostics.Call timing = SmbDiagnostics.begin("pool connect");
        timing.target(entry.tag);
        Throwable error = null;
        String dialect = "";
        try {
            timing.phase("credentials and setup");
            AuthenticationContext auth;
            if (c.user.isEmpty()) {
                auth = AuthenticationContext.guest();
            } else {
                String password = passwordOverride != null ? passwordOverride : SmbConnectionStore.password(c.id);
                // Never try an empty password for a lost one: repeated failed logons can lock the account.
                if (password == null) throw new SmbErrors.PasswordMissingException();
                auth = new AuthenticationContext(c.user, password.toCharArray(), c.domain);
            }
            entry.client = SmbClientFactory.create();
            entry.connection = SmbClientFactory.connect(entry.client, c.host, c.port, timing);
            timing.phase("authenticate");
            entry.session = entry.connection.authenticate(auth);
            dialect = String.valueOf(entry.connection.getNegotiatedProtocol().getDialect());
            timing.phase("tree connect");
            try {
                entry.share = entry.session.connectShare(entry.shareName);
            } catch (RuntimeException e) {
                SmbErrors.ShareListException refused =
                        IPC_SHARE.equalsIgnoreCase(entry.shareName) ? SmbErrors.asShareListFailure(e) : null;
                if (refused != null) throw refused;
                throw e;
            }
            boolean pipe = entry.share instanceof PipeShare && IPC_SHARE.equalsIgnoreCase(entry.shareName);
            if (!(entry.share instanceof DiskShare) && !pipe) {
                throw new IOException("'" + entry.shareName + "' is not a disk share");
            }
        } catch (IOException | RuntimeException | Error e) {
            error = e;
            throw e;
        } finally {
            timing.finish(dialect, error);
        }
    }

    /** Called only on retirement, connection completion, or a lease release, with LOCK held. */
    private static void scheduleCloseIfUnused(Entry entry) {
        if (entry.connecting || entry.users != 0) return;
        LEASES.remove(entry.share);
        executor.execute(() -> closeEntry(entry));
    }

    private static void closeEntry(Entry entry) {
        SmbDiagnostics.Call timing = SmbDiagnostics.begin("pool close");
        timing.target(entry.tag + " " + entry.reason);
        Throwable error = null;
        try {
            error = closeResource(entry.share, "tree disconnect", timing, error);
            error = closeResource(entry.session, "logoff", timing, error);
            error = closeResource(entry.connection, "connection close", timing, error);
            error = closeResource(entry.client, "client close", timing, error);
        } finally {
            synchronized (LOCK) {
                LIVE.remove(entry);
            }
            timing.finish("released", error);
        }
    }

    private static Throwable closeResource(AutoCloseable resource, String phase, SmbDiagnostics.Call timing,
                                           Throwable firstError) {
        if (resource == null) return firstError;
        timing.phase(phase);
        try {
            resource.close();
        } catch (Throwable t) {
            if (firstError == null) return t;
        }
        return firstError;
    }

    private static void ensureSweeper() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "filesplus-smb-pool");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(SmbSessionPool::sweep, SWEEP_SEC, SWEEP_SEC, TimeUnit.SECONDS);
    }

    /** Retires unused sessions after IDLE_MS and lets the pool executor close them. */
    private static void sweep() {
        try {
            synchronized (LOCK) {
                long now = System.currentTimeMillis();
                for (Entry entry : new ArrayList<>(ENTRIES.values())) {
                    if (!entry.connecting && entry.users == 0 && entry.share != null && now - entry.lastUse >= IDLE_MS) {
                        retire(entry, "idle");
                    }
                }
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Idle sweep failed", t);
        }
    }
}
