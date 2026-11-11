package app.filesplus.extension.smb;

import android.os.Looper;

import app.filesplus.extension.DiagLog;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One authenticated {@link DiskShare} per saved connection, reused between callers and closed after 60 s
 * without users. acquire/release are reference counted so a long read keeps its share open. All blocking
 * calls must run off the main thread; close/closeAll only schedule the real close and are safe anywhere.
 */
public final class SmbSessionPool {
    private static final String AREA = "smb";
    private static final long IDLE_MS = 60_000L;
    private static final long SWEEP_SEC = 15L;

    private static final Object LOCK = new Object();
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static ScheduledExecutorService executor;

    private SmbSessionPool() {}

    /** Server resources of one connection id. Fields are guarded by the entry's own monitor. */
    private static final class Entry {
        final String id;
        final String key;
        SMBClient client;
        Connection connection;
        Session session;
        DiskShare share;
        int users;
        long lastUse;
        boolean dead;

        Entry(String id, String key) {
            this.id = id;
            this.key = key;
        }

        boolean alive() {
            return share != null && share.isConnected() && connection != null && connection.isConnected();
        }

        void closeResources() {
            closeQuietly(share);
            closeQuietly(session);
            closeQuietly(connection);
            closeQuietly(client);
            share = null;
            session = null;
            connection = null;
            client = null;
        }
    }

    /** Uses the saved password of the connection. */
    public static DiskShare acquire(SmbConnection c) throws IOException {
        return acquire(c, null);
    }

    /**
     * Returns a connected, authenticated share, reusing the pooled one when it is alive. A non-null
     * passwordOverride (used by the unsaved-values test) replaces the saved password.
     */
    public static DiskShare acquire(SmbConnection c, String passwordOverride) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("SMB calls must not run on the main thread");
        }
        while (true) {
            Entry entry = entryFor(c);
            boolean dead;
            synchronized (entry) {
                dead = entry.dead;
                if (!dead) {
                    if (!entry.alive()) {
                        entry.closeResources();
                        try {
                            connect(entry, c, passwordOverride);
                        } catch (IOException | RuntimeException e) {
                            // Do not keep an unconnected entry around; the next call starts clean.
                            entry.dead = true;
                            forget(entry);
                            throw e;
                        }
                        // Holders of the dead share release with that old share and are ignored (see release),
                        // so their count must not carry over or this session would never be idle-closed.
                        entry.users = 0;
                    }
                    entry.users++;
                    entry.lastUse = System.currentTimeMillis();
                    return entry.share;
                }
            }
            // Closed by the idle sweep or close(); make sure it is gone from the map and take a fresh entry.
            forget(entry);
        }
    }

    private static void forget(Entry entry) {
        synchronized (LOCK) {
            if (ENTRIES.get(entry.id) == entry) ENTRIES.remove(entry.id);
        }
    }

    /** Pairs with acquire; the share identity makes a late release of a replaced session harmless. */
    public static void release(String id, DiskShare share) {
        Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(id);
        }
        if (entry == null) return;
        synchronized (entry) {
            if (entry.share != share) return;
            if (entry.users > 0) entry.users--;
            entry.lastUse = System.currentTimeMillis();
        }
    }

    /**
     * Drops the session only if it is still the one the caller used and found broken; a session another thread
     * has reconnected since is left alone. Safe on any thread.
     */
    public static void invalidate(String id, DiskShare share) {
        Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(id);
        }
        if (entry == null) return;
        synchronized (entry) {
            if (entry.share != share) return;
            entry.dead = true; // New callers take a fresh entry; the real close happens off this thread.
        }
        forget(entry);
        closeLater(entry);
    }

    /** Drops the session of one connection (settings changed, deleted, or found broken). Safe on any thread. */
    public static void close(String id) {
        Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.remove(id);
        }
        if (entry != null) closeLater(entry);
    }

    public static void closeAll() {
        List<Entry> all;
        synchronized (LOCK) {
            all = new ArrayList<>(ENTRIES.values());
            ENTRIES.clear();
        }
        for (Entry entry : all) closeLater(entry);
    }

    /** Entry for the connection; an entry for different server settings is replaced. */
    private static Entry entryFor(SmbConnection c) {
        Entry old = null;
        Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(c.id);
            if (entry != null && !entry.key.equals(c.targetKey())) {
                old = entry;
                entry = null;
            }
            if (entry == null) {
                entry = new Entry(c.id, c.targetKey());
                ENTRIES.put(c.id, entry);
            }
            ensureSweeper();
        }
        if (old != null) closeLater(old);
        return entry;
    }

    private static void connect(Entry entry, SmbConnection c, String passwordOverride) throws IOException {
        AuthenticationContext auth;
        if (c.user.isEmpty()) {
            auth = AuthenticationContext.guest();
        } else {
            String password = passwordOverride != null ? passwordOverride : SmbConnectionStore.password(c.id);
            // Never try an empty password for a lost one: repeated failed logons can lock the account.
            if (password == null) throw new SmbErrors.PasswordMissingException();
            auth = new AuthenticationContext(c.user, password.toCharArray(), c.domain);
        }
        try {
            entry.client = SmbClientFactory.create();
            entry.connection = entry.client.connect(c.host, c.port);
            entry.session = entry.connection.authenticate(auth);
            String dialect = String.valueOf(entry.connection.getNegotiatedProtocol().getDialect());
            Share share = entry.session.connectShare(c.share);
            if (!(share instanceof DiskShare)) {
                closeQuietly(share);
                throw new IOException("'" + c.share + "' is not a disk share");
            }
            entry.share = (DiskShare) share;
            DiagLog.i(AREA, "Connected (" + dialect + ")");
        } catch (IOException | RuntimeException e) {
            entry.closeResources();
            throw e;
        }
    }

    private static void closeLater(Entry entry) {
        synchronized (LOCK) {
            ensureSweeper();
            executor.execute(() -> closeEntry(entry));
        }
    }

    private static void closeEntry(Entry entry) {
        synchronized (entry) {
            entry.dead = true;
            entry.closeResources();
        }
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

    /** Closes sessions that nobody uses and that have been idle for IDLE_MS. */
    private static void sweep() {
        try {
            List<Entry> snapshot;
            synchronized (LOCK) {
                snapshot = new ArrayList<>(ENTRIES.values());
            }
            long now = System.currentTimeMillis();
            for (Entry entry : snapshot) {
                boolean idle;
                synchronized (entry) {
                    idle = !entry.dead && entry.users == 0 && entry.share != null && now - entry.lastUse >= IDLE_MS;
                    if (idle) {
                        entry.dead = true;
                        entry.closeResources();
                    }
                }
                if (idle) forget(entry);
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Idle sweep failed", t);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // The connection is being dropped anyway.
        }
    }
}
