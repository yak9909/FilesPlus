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
    static final int CHUNK = 1024 * 1024;
    private static final long HEADROOM = 64L * CHUNK;
    public static final int MAX_NAME_ATTEMPTS = 1000;
    private static final Pattern PART = Pattern.compile("\\.filesplus-[0-9a-f]{32}-[0-9]+\\.part");

    public enum Reason {
        HASH_MISMATCH, SIZE_MISMATCH, SOURCE_CHANGED, IO, NO_SPACE, NAME_INVALID, CANCELLED, LINK, TOP_LEVEL_LINK, TOO_LARGE
    }

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

        void finish(Reason reason, Observer observer) {
            consecutiveHashFailures = reason == Reason.HASH_MISMATCH ? consecutiveHashFailures + 1 : 0;
            if (stopped()) observer.log("HASH_MISMATCH: stopping the job after two consecutive failed items", null);
        }
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

    static final class Failed extends IOException {
        final Reason reason;

        Failed(Reason reason) { this(reason, reason.name()); }

        Failed(Reason reason, String detail) {
            super(detail);
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

    static void checkName(String name, boolean removable) throws Failed {
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
        String opId = newOpId();
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
                checkSpace(dstFs, folder, initial.size);
                String target = once(srcFs, source, dstFs, folder, name, removable, initial,
                        opId, attempt, cancellation, observer);
                job.finish(null, observer);
                return new Result(target, opId, initial, null);
            } catch (IOException e) {
                reason = reason(e);
                observer.log(reason + " on attempt " + (attempt + 1) + " for " + source, e);
                if (!retryable(reason)) break;
            }
        }
        job.finish(reason, observer);
        return new Result(null, opId, initial, reason);
    }

    private static String once(Fs srcFs, String source, Fs dstFs, String folder, String name,
                               boolean removable, Fs.Stat initial, String opId, int attempt,
                               Cancellation cancellation, Observer observer) throws IOException {
        String part = dstFs.child(folder, partName(opId, attempt));
        Part ownership = new Part();
        boolean committed = false;
        try {
            checkAvailableName(dstFs, folder, name, false, removable);
            Fs.Stat written = writeVerified(srcFs, source, initial, dstFs, part, new byte[CHUNK],
                    ownership, cancellation, observer);

            String target = publish(dstFs, folder, name, removable, part, written, cancellation, observer);
            committed = true;
            syncDirectory(dstFs, folder, observer);
            setMtime(dstFs, target, initial, observer);
            return target;
        } finally {
            if (ownership.recorded && (committed || !ownership.created
                    || removePart(dstFs, part, ownership.owned, observer))) {
                observer.partFinished(part);
            }
        }
    }

    static final class Part {
        boolean recorded;
        boolean created;
        Fs.Stat owned;
    }

    /** A staged file can use its exact name when its enclosing directory is the unpublished part. */
    static Fs.Stat writeVerified(Fs srcFs, String source, Fs.Stat initial, Fs dstFs, String part,
                                 byte[] buffer, Part ownership, Cancellation cancellation, Observer recorder)
            throws IOException {
        MessageDigest original = sha256();
        long bytes;
        InputStream input;
        try {
            input = srcFs.openRead(source, initial, true);
        } catch (Fs.SourceChanged e) {
            throw new Failed(Reason.SOURCE_CHANGED, "Source changed: " + source);
        }
        try (InputStream in = input) {
            if (dstFs.lstat(part) != null) throw new FileAlreadyExistsException(part);
            if (recorder != null) {
                recorder.partStarted(part);
                ownership.recorded = true;
            }
            try (Fs.Writer out = dstFs.createExclusive(part)) {
                ownership.created = true;
                ownership.owned = out.stat();
                bytes = stream(in, out, original, buffer, cancellation);
                out.sync();
            }
        }
        if (!initial.unchanged(srcFs.lstat(source))) throw new Failed(Reason.SOURCE_CHANGED, "Source changed: " + source);
        Fs.Stat written = dstFs.lstat(part);
        if (!ownership.owned.sameFile(written)) throw new Failed(Reason.IO);
        MessageDigest readback = sha256();
        long verifiedBytes;
        try (InputStream in = dstFs.openRead(part, written, false)) {
            verifiedBytes = stream(in, null, readback, buffer, cancellation);
        }
        if (bytes != initial.size || verifiedBytes != bytes) throw new Failed(Reason.SIZE_MISMATCH);
        if (!MessageDigest.isEqual(original.digest(), readback.digest())) throw new Failed(Reason.HASH_MISMATCH);
        return written;
    }

    static String newOpId() { return UUID.randomUUID().toString().replace("-", ""); }

    static String partName(String opId, int attempt) { return ".filesplus-" + opId + "-" + attempt + ".part"; }

    static void checkSpace(Fs fs, String folder, long bytes) throws IOException {
        long free = fs.freeSpace(folder);
        if (free < HEADROOM || bytes > free - HEADROOM) throw new Failed(Reason.NO_SPACE);
    }

    static Reason reason(IOException error) {
        if (error instanceof Failed) return ((Failed) error).reason;
        if (error instanceof Fs.NoSpace) return Reason.NO_SPACE;
        return Reason.IO;
    }

    static boolean retryable(Reason reason) { return reason == Reason.HASH_MISMATCH || reason == Reason.IO; }

    static void syncDirectory(Fs fs, String folder, Observer observer) {
        try {
            fs.syncDirectory(folder);
        } catch (Throwable t) {
            observer.log("Cannot sync the destination directory " + folder, t);
        }
    }

    static void setMtime(Fs fs, String path, Fs.Stat original, Observer observer) {
        try {
            fs.setMtime(path, original.mtime);
        } catch (IOException | RuntimeException e) {
            observer.log("Cannot set mtime on " + path, e);
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

    static void checkCancelled(Cancellation cancellation) throws Failed {
        if (cancellation.cancelled()) throw new Failed(Reason.CANCELLED);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Set<String> names(Fs fs, String folder) throws IOException {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(fs.list(folder));
        return names;
    }

    /** A candidate is checked before copying, including any conflict suffix that would extend its UTF-8 length. */
    static void checkAvailableName(Fs fs, String folder, String name, boolean directory, boolean removable)
            throws IOException {
        Set<String> existing = names(fs, folder);
        for (int n = 0; n < MAX_NAME_ATTEMPTS; n++) {
            String candidate = candidate(name, directory, n);
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
                if (hasCaseClash(fs, folder, candidate)) continue;
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

    static boolean hasCaseClash(Fs fs, String folder, String name) throws IOException {
        for (String entry : fs.list(folder)) {
            if (!name.equals(entry) && name.equalsIgnoreCase(entry)) return true;
        }
        return false;
    }

    static boolean removePart(Fs fs, String path, Fs.Stat owned, Observer observer) {
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
