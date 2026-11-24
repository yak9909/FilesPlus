package app.filesplus.extension.ops;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/** Copies one regular file and publishes it only after a complete SHA-256 readback. */
public final class VerifiedCopy {
    private static final int CHUNK = 1024 * 1024;
    private static final long HEADROOM = 64L * CHUNK;
    public static final int MAX_NAME_ATTEMPTS = 1000;
    private static final Pattern PART = Pattern.compile("\\.filesplus-[0-9a-f]{32}-[0-9]+\\.part");

    public enum Reason { HASH_MISMATCH, SIZE_MISMATCH, SOURCE_CHANGED, IO, NO_SPACE, NAME_INVALID, CANCELLED }

    public interface Cancellation {
        boolean cancelled();
    }

    public interface Observer {
        void partStarted(String path) throws IOException;
        void partFinished(String path);
        void reserved(String path);
        void reservationDone(String path);
        void log(String message, Throwable error);
    }

    /** Shared by all items of one job, including items deferred while a preview is playing. */
    public static final class Job {
        private int consecutiveHashFailures;

        public boolean stopped() { return consecutiveHashFailures >= 2; }
    }

    public static final class Result {
        public final String target;
        public final String opId;
        public final Fs.Stat source;
        public final long bytes;
        public final Reason reason;

        private Result(String target, String opId, Fs.Stat source, Reason reason) {
            this.target = target;
            this.opId = opId;
            this.source = source;
            this.bytes = source == null ? 0 : source.size;
            this.reason = reason;
        }

        public boolean succeeded() { return reason == null; }
    }

    private static final class Failed extends IOException {
        final Reason reason;

        Failed(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }
    }

    private VerifiedCopy() {}

    public static boolean isPartName(String name) { return PART.matcher(name).matches(); }

    public static boolean mediaStoreName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith(".pending-") || lower.startsWith(".trashed-");
    }

    public static String candidate(String name, boolean directory, int n) {
        if (n == 0) return name;
        int dot = directory ? -1 : name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot)
                : name + " (" + n + ")";
    }

    private static void checkName(String name, boolean removable) throws Failed {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf(0) >= 0 || mediaStoreName(name)
                || name.getBytes(StandardCharsets.UTF_8).length > 255
                || (removable && (name.endsWith(".") || name.endsWith(" ")
                        || name.chars().anyMatch(c -> "\"*:<>?\\|".indexOf(c) >= 0)))) {
            throw new Failed(Reason.NAME_INVALID);
        }
    }

    public static Result copy(Fs srcFs, String source, Fs dstFs, String folder, String name,
                              boolean removable, Job job, Cancellation cancellation, Observer observer) {
        String opId = UUID.randomUUID().toString().replace("-", "");
        Fs.Stat initial = null;
        Reason reason = Reason.IO;
        if (job.stopped()) {
            observer.log("HASH_MISMATCH: job stopped after two mismatches", null);
            return new Result(null, opId, null, Reason.HASH_MISMATCH);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Fs.Stat current = srcFs.lstat(source);
                if (initial != null && !initial.unchanged(current)) throw new Failed(Reason.SOURCE_CHANGED);
                if (current == null || current.type != Fs.Type.FILE) throw new Failed(Reason.IO);
                initial = current;
                checkName(name, removable);
                long free = dstFs.freeSpace(folder);
                if (free < HEADROOM || initial.size > free - HEADROOM) throw new Failed(Reason.NO_SPACE);
                String target = once(srcFs, source, dstFs, folder, name, removable, initial,
                        opId, attempt, cancellation, observer);
                job.consecutiveHashFailures = 0;
                return new Result(target, opId, initial, null);
            } catch (IOException e) {
                reason = e instanceof Failed ? ((Failed) e).reason : e instanceof Fs.NoSpace ? Reason.NO_SPACE
                        : Reason.IO;
                observer.log(reason + " on attempt " + (attempt + 1) + " for " + source, e);
                if (reason != Reason.HASH_MISMATCH && reason != Reason.IO) break;
            }
        }
        job.consecutiveHashFailures = reason == Reason.HASH_MISMATCH ? job.consecutiveHashFailures + 1 : 0;
        if (job.stopped()) observer.log("HASH_MISMATCH: stopping the job after two consecutive failed items", null);
        return new Result(null, opId, initial, reason);
    }

    private static String once(Fs srcFs, String source, Fs dstFs, String folder, String name,
                               boolean removable, Fs.Stat initial, String opId, int attempt,
                               Cancellation cancellation, Observer observer) throws IOException {
        String part = dstFs.child(folder, ".filesplus-" + opId + "-" + attempt + ".part");
        Fs.Stat owned = null;
        boolean recorded = false;
        boolean created = false;
        boolean committed = false;
        try {
            checkAvailableName(dstFs, folder, name, removable);
            byte[] buffer = new byte[CHUNK];
            MessageDigest original = sha256();
            long bytes;
            InputStream input;
            try {
                input = srcFs.openRead(source, initial, true);
            } catch (Fs.SourceChanged e) {
                throw new Failed(Reason.SOURCE_CHANGED);
            }
            try (InputStream in = input) {
                if (dstFs.lstat(part) != null) throw new FileAlreadyExistsException(part);
                observer.partStarted(part);
                recorded = true;
                try (Fs.Writer out = dstFs.createExclusive(part)) {
                    created = true;
                    owned = out.stat();
                    bytes = stream(in, out, original, buffer, cancellation);
                    out.sync();
                }
            }
            if (!initial.unchanged(srcFs.lstat(source))) throw new Failed(Reason.SOURCE_CHANGED);

            Fs.Stat written = dstFs.lstat(part);
            if (!owned.sameFile(written)) throw new Failed(Reason.IO);
            MessageDigest readback = sha256();
            long verifiedBytes;
            try (InputStream in = dstFs.openRead(part, written, false)) {
                verifiedBytes = stream(in, null, readback, buffer, cancellation);
            }
            if (bytes != initial.size || verifiedBytes != bytes) throw new Failed(Reason.SIZE_MISMATCH);
            if (!MessageDigest.isEqual(original.digest(), readback.digest())) throw new Failed(Reason.HASH_MISMATCH);

            // Recheck both entries before replacing the empty reservation.
            String target = publish(dstFs, folder, name, removable, part, written, cancellation, observer);
            committed = true;
            try {
                dstFs.syncDirectory(folder);
            } catch (Throwable t) {
                observer.log("Cannot sync the destination directory " + folder, t);
            }
            try {
                dstFs.setMtime(target, initial.mtime);
            } catch (IOException | RuntimeException e) {
                observer.log("Cannot set mtime on " + target, e);
            }
            return target;
        } finally {
            if (recorded && (committed || !created || removePart(dstFs, part, owned, observer))) {
                observer.partFinished(part);
            }
        }
    }

    private static long stream(InputStream input, Fs.Writer output, MessageDigest digest, byte[] buffer,
                               Cancellation cancellation) throws IOException {
        long bytes = 0;
        while (true) {
            checkCancelled(cancellation);
            int count = input.read(buffer);
            if (count < 0) return bytes;
            if (count == 0) throw new IOException("No read progress");
            digest.update(buffer, 0, count);
            if (output != null) output.write(buffer, count);
            bytes += count;
        }
    }

    private static void checkCancelled(Cancellation cancellation) throws Failed {
        if (cancellation.cancelled()) throw new Failed(Reason.CANCELLED);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Set<String> names(Fs fs, String folder) throws IOException {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(fs.list(folder));
        return names;
    }

    /** A candidate is checked before copying, including any conflict suffix that would extend its UTF-8 length. */
    private static void checkAvailableName(Fs fs, String folder, String name, boolean removable) throws IOException {
        Set<String> existing = names(fs, folder);
        for (int n = 0; n < MAX_NAME_ATTEMPTS; n++) {
            String candidate = candidate(name, false, n);
            checkName(candidate, removable);
            if (!existing.contains(candidate)) return;
        }
        throw new Failed(Reason.IO);
    }

    private static String publish(Fs fs, String folder, String name, boolean removable, String part,
                                  Fs.Stat verified, Cancellation cancellation, Observer observer) throws IOException {
        Set<String> existing = names(fs, folder);
        for (int n = 0; n < MAX_NAME_ATTEMPTS; n++) {
            String candidate = candidate(name, false, n);
            checkName(candidate, removable);
            if (existing.contains(candidate)) continue;
            String target = fs.child(folder, candidate);
            observer.reserved(target);
            Fs.Stat reservation = null;
            boolean renamed = false;
            try {
                try (Fs.Writer placeholder = fs.createExclusive(target)) {
                    reservation = placeholder.stat();
                } catch (FileAlreadyExistsException e) {
                    continue;
                }
                checkCancelled(cancellation);
                for (String entry : fs.list(folder)) {
                    if (!candidate.equals(entry) && candidate.equalsIgnoreCase(entry)) {
                        existing.add(candidate);
                        break;
                    }
                }
                if (existing.contains(candidate)) continue;
                if (!verified.sameFileAndSize(fs.lstat(part))) throw new Failed(Reason.IO);
                Fs.Stat current = fs.lstat(target);
                if (!reservation.sameFile(current) || current.size != 0) {
                    observer.log("Reservation changed, leaving it in place: " + target, null);
                    continue;
                }
                fs.rename(part, target);
                renamed = true;
                return target;
            } finally {
                if (reservation != null && !renamed) releaseReservation(fs, target, reservation, observer);
                observer.reservationDone(target);
            }
        }
        throw new Failed(Reason.IO);
    }

    private static boolean removePart(Fs fs, String path, Fs.Stat owned, Observer observer) {
        try {
            Fs.Stat current = fs.lstat(path);
            if (current == null) return true;
            if (owned == null || !owned.sameFile(current)) {
                observer.log("Temporary file identity unknown or changed, keeping its recovery record: " + path, null);
                return false;
            }
            fs.unlink(path);
            return true;
        } catch (IOException | RuntimeException e) {
            observer.log("IO: cannot remove temporary file " + path, e);
            return false;
        }
    }

    private static void releaseReservation(Fs fs, String path, Fs.Stat reservation, Observer observer) {
        try {
            Fs.Stat current = fs.lstat(path);
            if (reservation.sameFile(current) && current.size == 0) fs.unlink(path);
        } catch (IOException | RuntimeException e) {
            observer.log("IO: cannot release reservation " + path, e);
        }
    }
}
