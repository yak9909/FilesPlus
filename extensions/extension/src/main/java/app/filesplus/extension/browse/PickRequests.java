package app.filesplus.extension.browse;

import android.app.Activity;
import android.content.Intent;

import app.filesplus.extension.DiagLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Starts the destination browser and carries its result back inside this process (DESIGN 5.11.4). Only the request
 * id travels in the Intent: the files to move and the callback never leave the process, so a request that outlives
 * the process is simply gone ("unknown request id" finishes the browser) and nothing moves.
 */
public final class PickRequests {
    static final String EXTRA_REQUEST = "app.filesplus.extension.browse.REQUEST";
    static final String EXTRA_NONCE = "app.filesplus.extension.browse.NONCE";
    /**
     * Different in every process: an activity restored after the process died carries the old nonce, so it can
     * never pick up a new request that happens to reuse its id.
     */
    private static final long NONCE = new java.security.SecureRandom().nextLong();
    private static final String AREA = "browse";

    /** What the browser needs to know about the move it picks a destination for. */
    public static final class PickSpec {
        /** Title of the top screen (usually the app's "Move to"); null uses the app's own string. */
        public final String title;
        /** How many items are moved (for messages). */
        public final int count;
        /** The folder each moved file is in now. "Move here" is refused when all of them are in the target. */
        public final List<Location> sourceFolders;
        /** Folders that are moved themselves: nothing may be moved into them or below them. */
        public final List<Location> movingFolders;

        private List<Location> canonicalSources;
        private List<Location> canonicalMoving;

        public PickSpec(String title, int count, List<Location> sourceFolders, List<Location> movingFolders) {
            this.title = title;
            this.count = count;
            this.sourceFolders = withoutNulls(sourceFolders);
            this.movingFolders = withoutNulls(movingFolders);
        }

        /** {@link #sourceFolders} with symlinks and aliases ("/sdcard") resolved. Does I/O: background thread only. */
        synchronized List<Location> canonicalSources() {
            if (canonicalSources == null) canonicalSources = canonical(sourceFolders);
            return canonicalSources;
        }

        /** {@link #movingFolders} resolved like {@link #canonicalSources()}. */
        synchronized List<Location> canonicalMoving() {
            if (canonicalMoving == null) canonicalMoving = canonical(movingFolders);
            return canonicalMoving;
        }
    }

    private static List<Location> withoutNulls(List<Location> locations) {
        List<Location> out = new ArrayList<>();
        if (locations != null) for (Location location : locations) if (location != null) out.add(location);
        return Collections.unmodifiableList(out);
    }

    /** The canonical form of a local location (the browser's own locations are canonical); others unchanged. */
    static Location canonical(Location location) {
        if (location == null || !location.isLocal()) return location;
        try {
            Location resolved = Location.local(location.file().getCanonicalFile());
            return resolved != null ? resolved : location;
        } catch (java.io.IOException e) {
            return location;
        }
    }

    private static List<Location> canonical(List<Location> locations) {
        List<Location> out = new ArrayList<>();
        for (Location location : locations) out.add(canonical(location));
        return Collections.unmodifiableList(out);
    }

    static final class Pending {
        final PickSpec spec;
        final Consumer<Location> onPicked;

        Pending(PickSpec spec, Consumer<Location> onPicked) {
            this.spec = spec;
            this.onPicked = onPicked;
        }
    }

    private static final Map<Integer, Pending> PENDING = new HashMap<>();
    private static int nextId = 1;

    private PickRequests() {}

    /**
     * Opens the browser over {@code from}. {@code onPicked} runs once, on the UI thread, when the user confirms a
     * destination; it never runs when the user cancels, the browser is closed otherwise, or the process dies.
     */
    public static void start(Activity from, PickSpec spec, Consumer<Location> onPicked) {
        int id;
        synchronized (PENDING) {
            id = nextId++;
            PENDING.put(id, new Pending(spec, onPicked));
        }
        try {
            Intent intent = new Intent(from, BrowseActivity.class);
            intent.putExtra(EXTRA_REQUEST, id);
            intent.putExtra(EXTRA_NONCE, NONCE);
            from.startActivity(intent);
        } catch (Throwable t) {
            cancel(id);
            DiagLog.e(AREA, "Cannot start the destination browser", t);
            throw t;
        }
    }

    /** The request, or null when it is unknown (already answered, cancelled, or lost with the process). */
    static Pending peek(int id, long nonce) {
        if (nonce != NONCE) return null;
        synchronized (PENDING) {
            return PENDING.get(id);
        }
    }

    /**
     * Answers the request once. The entry is removed first so that a double tap cannot deliver twice. Returns
     * whether this call delivered (false when the request was gone).
     */
    static boolean deliver(int id, Location destination) {
        Pending pending;
        synchronized (PENDING) {
            pending = PENDING.remove(id);
        }
        if (pending == null) return false;
        try {
            pending.onPicked.accept(destination);
        } catch (Throwable t) {
            DiagLog.e(AREA, "The pick callback failed", t);
        }
        return true;
    }

    /** Forgets the request without answering it. */
    static void cancel(int id) {
        synchronized (PENDING) {
            PENDING.remove(id);
        }
    }
}
