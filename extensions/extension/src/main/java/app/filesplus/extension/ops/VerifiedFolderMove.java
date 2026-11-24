package app.filesplus.extension.ops;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import app.filesplus.extension.ops.VerifiedCopy.Cancellation;
import app.filesplus.extension.ops.VerifiedCopy.Failed;
import app.filesplus.extension.ops.VerifiedCopy.Job;
import app.filesplus.extension.ops.VerifiedCopy.Observer;
import app.filesplus.extension.ops.VerifiedCopy.Reason;

/** Verifies a complete directory in a private staging tree before publishing it. */
public final class VerifiedFolderMove {
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_DEPTH = 64;

    public interface SourceCheck {
        void check() throws IOException;
    }

    public interface Trash {
        /** Calls check immediately before the single rename of the original folder. */
        void move(String opId, long fileCount, long bytes, SourceCheck check) throws IOException;
    }

    public static final class Result {
        public final String target;
        public final long bytes;
        public final List<String> children;
        public final Reason reason;
        public final boolean sourceKept;

        private Result(String target, long bytes, List<String> children, Reason reason, boolean sourceKept) {
            this.target = target;
            this.bytes = bytes;
            this.children = children;
            this.reason = reason;
            this.sourceKept = sourceKept;
        }
    }

    private static final class Entry {
        final String name;
        final String relative;
        final Fs.Stat stat;
        final List<Entry> children;
        Fs.Stat created;
        Fs.Stat verified;
        VerifiedCopy.Part file;

        Entry(String name, String relative, Fs.Stat stat) {
            this.name = name;
            this.relative = relative;
            this.stat = stat;
            this.children = stat.type == Fs.Type.DIRECTORY ? new ArrayList<>() : Collections.emptyList();
        }
    }

    private enum Walk { PLAN, COMPARE, MEASURE }

    private static final class Manifest {
        final Map<String, Entry> entries = new LinkedHashMap<>();
        final int maxEntries;
        final long maxBytes;
        int fileCount;
        long bytes;

        Manifest(int maxEntries, long maxBytes) {
            this.maxEntries = maxEntries;
            this.maxBytes = maxBytes;
        }

        int pasteEntries() { return Math.max(1, entries.size() - 1); }
    }

    /** A paste shares one budget across immediate and deferred items on the file executor. */
    public static final class PasteBudget {
        private final int maxEntries;
        private final long maxBytes;
        private final Map<String, Usage> measured = new LinkedHashMap<>();
        private int entries;
        private long bytes;

        private PasteBudget(int maxEntries, long maxBytes) {
            this.maxEntries = maxEntries;
            this.maxBytes = maxBytes;
        }

        private boolean record(String source, Manifest manifest) {
            return record(source, manifest.pasteEntries(), manifest.bytes);
        }

        public long availableFileBytes(String source) {
            Usage previous = measured.get(source);
            if (previous == null && entries >= maxEntries) return -1;
            return availableBytes(source);
        }

        private long availableBytes(String source) {
            Usage previous = measured.get(source);
            return maxBytes - bytes + (previous == null ? 0 : previous.bytes);
        }

        public void recordCopiedFile(String source, long size) {
            if (!record(source, 1, size)) throw new IllegalStateException("Copied file exceeds paste budget");
        }

        private boolean record(String source, int count, long size) {
            Usage previous = measured.get(source);
            int otherEntries = entries - (previous == null ? 0 : previous.entries);
            long available = availableBytes(source);
            if (count > maxEntries - otherEntries || size > available) return false;
            bytes = maxBytes - available + size;
            entries = otherEntries + count;
            measured.put(source, new Usage(count, size));
            return true;
        }
    }

    private static final class Usage {
        final int entries;
        final long bytes;

        Usage(int entries, long bytes) {
            this.entries = entries;
            this.bytes = bytes;
        }
    }

    /** Recounts the whole paste before any move or destination creation. Null means it exceeds the limits. */
    public static PasteBudget measurePaste(Fs fs, List<String> sources, int maxEntries, long maxBytes)
            throws IOException {
        PasteBudget budget = new PasteBudget(maxEntries, maxBytes);
        for (String source : sources) {
            Fs.Stat stat = fs.lstat(source);
            if (stat == null) continue;
            Manifest manifest = new Manifest(maxEntries + 1, maxBytes);
            try {
                walk(fs, source, "", "", stat, stat.device, 0, manifest, Walk.MEASURE, false, () -> false);
            } catch (Failed e) {
                if (e.reason == Reason.TOO_LARGE) return null;
                throw e;
            }
            if (!budget.record(source, manifest)) return null;
        }
        return budget;
    }

    private VerifiedFolderMove() {}

    public static Result move(Fs srcFs, String source, Fs dstFs, String destination, String name, boolean removable,
                              Job job, Cancellation cancellation, Observer observer, Trash trash) {
        return move(srcFs, source, dstFs, destination, name, removable, job, cancellation, observer, trash, null);
    }

    public static Result move(Fs srcFs, String source, Fs dstFs, String destination, String name, boolean removable,
                              Job job, Cancellation cancellation, Observer observer, Trash trash, PasteBudget budget) {
        return transfer(srcFs, source, dstFs, destination, name, removable, job, cancellation, observer,
                Objects.requireNonNull(trash), budget);
    }

    public static Result copy(Fs srcFs, String source, Fs dstFs, String destination, String name, boolean removable,
                              Job job, Cancellation cancellation, Observer observer, PasteBudget budget) {
        return transfer(srcFs, source, dstFs, destination, name, removable, job, cancellation, observer, null, budget);
    }

    private static Result transfer(Fs srcFs, String source, Fs dstFs, String destination, String name,
                                   boolean removable, Job job, Cancellation cancellation, Observer observer,
                                   Trash trash, PasteBudget budget) {
        if (job.stopped()) return new Result(null, 0, Collections.emptyList(), Reason.HASH_MISMATCH, false);
        String opId = VerifiedCopy.newOpId();
        Manifest manifest = budget == null ? new Manifest(MAX_ENTRIES, Long.MAX_VALUE)
                : new Manifest(budget.maxEntries + 1, budget.maxBytes);
        try {
            Path src = new File(source.toLowerCase(Locale.ROOT)).toPath().normalize();
            Path dst = new File(destination.toLowerCase(Locale.ROOT)).toPath().normalize();
            if (dst.startsWith(src)) throw new Failed(Reason.IO, "Cannot transfer a folder into itself: " + source);
            Fs.Stat root = srcFs.lstat(source);
            requireDirectory(source, root);
            Fs.Stat parent = dstFs.lstat(destination);
            requireDirectory(destination, parent);
            Entry tree = walk(srcFs, source, name, "", root, root.device, 0, manifest, Walk.PLAN, removable,
                    cancellation);
            if (budget != null && !budget.record(source, manifest)) {
                throw new Failed(Reason.TOO_LARGE, "Paste limit exceeded at " + source);
            }
            List<String> children = new ArrayList<>(manifest.entries.keySet());
            children.remove(0); // TabMove scans the root separately.
            VerifiedCopy.checkSpace(dstFs, destination, manifest.bytes);
            SourceCheck sourceCheck = () -> checkSource(srcFs, source, name, root.device, manifest, observer);
            String target = stageAndPublish(srcFs, source, dstFs, destination, name, removable, parent, tree,
                    opId, cancellation, observer, trash == null ? sourceCheck : null);
            job.finish(null, observer);
            if (trash == null) return new Result(target, manifest.bytes, children, null, false);
            boolean kept = false;
            try {
                trash.move(opId, manifest.fileCount, manifest.bytes, () -> {
                    sourceCheck.check();
                    if (!parent.sameFile(dstFs.lstat(destination)) || !tree.created.sameFile(dstFs.lstat(target))) {
                        throw new IOException("Committed folder or its parent changed: " + target);
                    }
                });
            } catch (Throwable t) {
                kept = true;
                observer.log("MOVED (source kept): " + source, t);
            }
            return new Result(target, manifest.bytes, children, null, kept);
        } catch (IOException e) {
            Reason reason = VerifiedCopy.reason(e);
            job.finish(reason, observer);
            observer.log(reason + (trash == null ? " copying folder " : " moving folder ") + source, e);
            return new Result(null, manifest.bytes, Collections.emptyList(), reason, false);
        }
    }

    private static Entry walk(Fs fs, String path, String name, String relative, Fs.Stat stat, long device, int depth,
                              Manifest manifest, Walk mode, boolean removable, Cancellation cancellation)
            throws IOException {
        VerifiedCopy.checkCancelled(cancellation);
        if (depth > MAX_DEPTH || manifest.entries.size() == manifest.maxEntries) {
            throw new Failed(Reason.TOO_LARGE, "Folder entry or depth limit at " + relative);
        }
        if (stat == null) {
            if (mode == Walk.MEASURE) return null;
            throw new Failed(Reason.SOURCE_CHANGED, "Removed source entry: " + (relative.isEmpty() ? path : relative));
        }
        boolean unsupported = stat.device != device || (stat.type != Fs.Type.FILE && stat.type != Fs.Type.DIRECTORY);
        if (mode == Walk.PLAN) {
            if (unsupported) throw new Failed(Reason.LINK, "Link, special file or other device at " + relative);
            try {
                VerifiedCopy.checkName(name, removable);
            } catch (Failed e) {
                throw new Failed(e.reason, "Invalid destination name: " + relative);
            }
        }
        Entry entry = new Entry(name, relative, stat);
        manifest.entries.put(relative, entry);
        if (stat.type == Fs.Type.FILE || (mode == Walk.MEASURE && stat.type != Fs.Type.DIRECTORY)) {
            if (stat.size < 0 || manifest.maxBytes - manifest.bytes < stat.size) {
                Reason reason = manifest.maxBytes == Long.MAX_VALUE ? Reason.NO_SPACE : Reason.TOO_LARGE;
                throw new Failed(reason, "Byte limit at " + relative);
            }
            manifest.bytes += stat.size;
            if (stat.type == Fs.Type.FILE) manifest.fileCount++;
        } else if (!unsupported && stat.type == Fs.Type.DIRECTORY) {
            try (Fs.Directory directory = fs.openDirectory(path, stat)) {
                String folder = directory.path();
                Set<String> names = mode == Walk.PLAN ? new TreeSet<>(String.CASE_INSENSITIVE_ORDER) : null;
                for (String child : fs.list(folder, manifest.maxEntries - manifest.entries.size() + 1)) {
                    String childPath = fs.child(folder, child);
                    String childRelative = relative.isEmpty() ? child : relative + "/" + child;
                    if (names != null && !names.add(child)) {
                        throw new Failed(Reason.NAME_INVALID, "Case-only duplicate source name: " + childRelative);
                    }
                    Entry nested = walk(fs, childPath, child, childRelative, fs.lstat(childPath), device,
                            depth + 1, manifest, mode, removable, cancellation);
                    if (mode == Walk.PLAN) entry.children.add(nested);
                }
            }
        }
        return entry;
    }

    private static String stageAndPublish(Fs srcFs, String source, Fs dstFs, String destination, String name,
                                          boolean removable, Fs.Stat parent, Entry tree, String opId,
                                          Cancellation cancellation, Observer observer, SourceCheck beforePublish)
            throws IOException {
        Fs.Directory directory = dstFs.openDirectory(destination, parent);
        boolean committed = false;
        try {
            String folder = directory.path();
            VerifiedCopy.checkAvailableName(dstFs, folder, name, true, removable);
            String tempName = VerifiedCopy.partName(opId, 0);
            String path = dstFs.child(destination, tempName);
            String part = dstFs.child(folder, tempName);
            if (dstFs.lstat(part) != null) throw new FileAlreadyExistsException(path);
            VerifiedCopy.checkCancelled(cancellation);
            observer.partStarted(path);
            boolean exists = true;
            try {
                try {
                    tree.created = dstFs.mkdir(part);
                } catch (FileAlreadyExistsException e) {
                    exists = false;
                    throw e;
                }
                copyDirectory(srcFs, source, dstFs, part, tree, parent.device, new byte[VerifiedCopy.CHUNK],
                        cancellation, observer);
                if (beforePublish != null) beforePublish.check();
                verifyStaging(dstFs, part, tree, cancellation);
                String targetName = publish(dstFs, folder, destination, parent, name, removable, part, tree.created,
                        cancellation, observer);
                committed = true;
                VerifiedCopy.syncDirectory(dstFs, folder, observer);
                return dstFs.child(destination, targetName);
            } finally {
                if (committed || !exists || ((tree.created == null || tree.created.device == parent.device)
                        && removeCreated(dstFs, part, tree, observer))) observer.partFinished(path);
            }
        } finally {
            try {
                directory.close();
            } catch (IOException e) {
                observer.log("Cannot close destination directory: " + destination, e);
            }
        }
    }

    private static void copyDirectory(Fs srcFs, String source, Fs dstFs, String part, Entry entry, long device,
                                      byte[] buffer, Cancellation cancellation, Observer observer) throws IOException {
        if (entry.created.device != device) throw new Failed(Reason.IO, "Temporary directory is on another device");
        try (Fs.Directory input = srcFs.openDirectory(source, entry.stat);
             Fs.Directory output = dstFs.openDirectory(part, entry.created)) {
            for (Entry child : entry.children) {
                VerifiedCopy.checkCancelled(cancellation);
                String from = srcFs.child(input.path(), child.name);
                String to = dstFs.child(output.path(), child.name);
                if (child.stat.type == Fs.Type.DIRECTORY) {
                    child.created = dstFs.mkdir(to);
                    copyDirectory(srcFs, from, dstFs, to, child, device, buffer, cancellation, observer);
                } else {
                    copyFile(srcFs, from, dstFs, to, child, device, buffer, cancellation, observer);
                }
            }
            VerifiedCopy.setMtime(dstFs, output.path(), entry.stat, observer);
            VerifiedCopy.syncDirectory(dstFs, output.path(), observer);
        }
    }

    private static void copyFile(Fs srcFs, String source, Fs dstFs, String part, Entry entry, long device,
                                 byte[] buffer, Cancellation cancellation, Observer observer) throws IOException {
        // The manifest still checks the planning snapshot before trashing. Verify the version we actually read.
        Fs.Stat initial = srcFs.lstat(source);
        if (initial == null || initial.type != Fs.Type.FILE || initial.device != entry.stat.device) {
            throw new Failed(Reason.SOURCE_CHANGED, "Source entry changed type, device or disappeared: " + entry.relative);
        }
        for (int attempt = 0; ; attempt++) {
            entry.file = new VerifiedCopy.Part();
            try {
                Fs.Stat written = VerifiedCopy.writeVerified(srcFs, source, initial, dstFs, part, buffer,
                        entry.file, cancellation, null);
                if (written.device != device) throw new Failed(Reason.IO, "Temporary file on another device");
                VerifiedCopy.setMtime(dstFs, part, initial, observer);
                entry.verified = dstFs.lstat(part);
                if (!written.sameFileAndSize(entry.verified)) throw new Failed(Reason.IO, "Staged file changed");
                return;
            } catch (IOException e) {
                Reason reason = VerifiedCopy.reason(e);
                observer.log(reason + " on attempt " + (attempt + 1) + " for " + entry.relative, e);
                if (attempt > 0 || !VerifiedCopy.retryable(reason)) throw e;
                if (entry.file.created && (entry.file.owned == null || entry.file.owned.device != device
                        || !VerifiedCopy.removePart(dstFs, part, entry.file.owned, observer))) throw e;
            }
        }
    }

    private static void verifyStaging(Fs fs, String path, Entry entry, Cancellation cancellation) throws IOException {
        VerifiedCopy.checkCancelled(cancellation);
        if (entry.file != null) {
            if (!entry.verified.unchanged(fs.lstat(path))) {
                throw new Failed(Reason.IO, "Staged file changed: " + entry.relative);
            }
            return;
        }
        try (Fs.Directory directory = fs.openDirectory(path, entry.created)) {
            String folder = directory.path();
            if (fs.list(folder, entry.children.size() + 1).size() != entry.children.size()) {
                throw new Failed(Reason.IO, "Staged directory contents changed: " + entry.relative);
            }
            for (Entry child : entry.children) verifyStaging(fs, fs.child(folder, child.name), child, cancellation);
        }
    }

    private static String publish(Fs fs, String folder, String destination, Fs.Stat parent, String name,
                                  boolean removable, String part, Fs.Stat staged, Cancellation cancellation,
                                  Observer observer)
            throws IOException {
        Set<String> existing = VerifiedCopy.names(fs, folder);
        for (int n = 0; n < VerifiedCopy.MAX_NAME_ATTEMPTS; n++) {
            VerifiedCopy.checkCancelled(cancellation);
            String candidate = VerifiedCopy.candidate(name, true, n);
            VerifiedCopy.checkName(candidate, removable);
            if (existing.contains(candidate)) continue;
            String target = fs.child(folder, candidate);
            String recorded = fs.child(destination, candidate);
            observer.reserved(recorded);
            Fs.Stat reservation = null;
            boolean release = true;
            try {
                try {
                    reservation = fs.mkdir(target);
                } catch (FileAlreadyExistsException e) {
                    continue;
                }
                if (VerifiedCopy.hasCaseClash(fs, folder, candidate)) continue;
                if (!parent.sameFile(fs.lstat(destination))) throw new Failed(Reason.IO, "Destination folder changed");
                if (!staged.sameFile(fs.lstat(part))) throw new Failed(Reason.IO, "Temporary folder changed");
                if (reservation.device != parent.device || !untouched(fs, target, reservation)) {
                    release = false;
                    observer.log("Folder reservation changed: " + recorded, null);
                    continue;
                }
                try {
                    fs.rename(part, target);
                    release = false;
                    return candidate;
                } catch (DirectoryNotEmptyException | FileAlreadyExistsException e) {
                    if (untouched(fs, target, reservation)) {
                        Failed failure = new Failed(Reason.IO, "platform refused rename onto an empty folder");
                        failure.initCause(e);
                        throw failure;
                    }
                    release = false;
                    observer.log("Folder reservation changed during rename: " + recorded, e);
                }
            } finally {
                if (release && reservation != null) releaseReservation(fs, target, reservation, observer);
                observer.reservationDone(recorded);
            }
        }
        throw new Failed(Reason.IO, "No free folder name for " + name);
    }

    private static boolean untouched(Fs fs, String path, Fs.Stat reservation) throws IOException {
        if (!reservation.sameFile(fs.lstat(path))) return false;
        try (Fs.Directory directory = fs.openDirectory(path, reservation)) {
            return fs.list(directory.path(), 1).isEmpty() && reservation.sameFile(fs.lstat(path));
        }
    }

    private static void releaseReservation(Fs fs, String path, Fs.Stat reservation, Observer observer) {
        try {
            if (untouched(fs, path, reservation)) fs.rmdir(path);
        } catch (IOException | RuntimeException e) {
            observer.log("Cannot remove empty folder reservation: " + path, e);
        }
    }

    private static boolean removeCreated(Fs fs, String path, Entry entry, Observer observer) {
        try {
            Fs.Stat current = fs.lstat(path);
            if (current == null) return true;
            if (entry.file != null) {
                return entry.file.created && entry.file.owned != null
                        && VerifiedCopy.removePart(fs, path, entry.file.owned, observer);
            }
            if (entry.created == null || !entry.created.sameFile(current)) {
                observer.log("Temporary directory identity unknown or changed: " + path, null);
                return false;
            }
            boolean clean = true;
            try (Fs.Directory directory = fs.openDirectory(path, entry.created)) {
                for (Entry child : entry.children) {
                    if (child.file == null && child.created == null) continue;
                    Fs.Stat owned = child.file == null ? child.created : child.file.owned;
                    if (owned != null && owned.device != entry.created.device) {
                        clean = false;
                        continue;
                    }
                    if (!removeCreated(fs, fs.child(directory.path(), child.name), child, observer)) clean = false;
                }
            }
            if (!clean || !entry.created.sameFile(fs.lstat(path))) return false;
            fs.rmdir(path);
            return true;
        } catch (IOException | RuntimeException e) {
            observer.log("Cannot remove temporary move entry " + entry.relative + " at " + path, e);
            return false;
        }
    }

    private static void requireDirectory(String path, Fs.Stat stat) throws Failed {
        if (stat == null) throw new Failed(Reason.IO, "Missing directory: " + path);
        if (stat.type != Fs.Type.DIRECTORY) throw new Failed(Reason.LINK, "Expected a real directory: " + path);
    }

    private static void checkSource(Fs fs, String source, String name, long device, Manifest planned,
                                    Observer observer) throws IOException {
        Manifest current = new Manifest(MAX_ENTRIES, Long.MAX_VALUE);
        walk(fs, source, name, "", fs.lstat(source), device, 0, current, Walk.COMPARE, false, () -> false);
        Map<String, String> first = new LinkedHashMap<>();
        int changes = 0;
        for (Entry old : planned.entries.values()) {
            Entry now = current.entries.get(old.relative);
            String path = old.relative.isEmpty() ? source : old.relative;
            if (now == null) {
                first.putIfAbsent("Removed source entry", path);
            } else if (!old.stat.unchanged(now.stat)) {
                if (old.stat.type != now.stat.type) first.putIfAbsent("Changed source type", path);
                if (old.stat.size != now.stat.size) first.putIfAbsent("Changed source size", path);
                if (!old.stat.mtime.equals(now.stat.mtime)) first.putIfAbsent("Changed source mtime", path);
                if (old.stat.inode != now.stat.inode) first.putIfAbsent("Changed source inode", path);
                if (old.stat.device != now.stat.device) first.putIfAbsent("Changed source device", path);
            } else {
                continue;
            }
            changes++;
        }
        for (String relative : current.entries.keySet()) {
            if (!planned.entries.containsKey(relative)) {
                first.putIfAbsent("Added source entry", relative);
                changes++;
            }
        }
        if (changes == 0) return;
        first.forEach((kind, path) -> observer.log(kind + ": " + path, null));
        throw new Failed(Reason.SOURCE_CHANGED, changes + " source entries changed: " + source);
    }
}
