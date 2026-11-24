package app.filesplus.extension.ops;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class VerifiedCopySelfTest {
    private static final int MIB = 1024 * 1024;
    private static final byte[] DATA = new byte[MIB + 29];
    private static final byte[] OTHER = {4, 3, 2, 1};
    private static int checks;

    static { new Random(42).nextBytes(DATA); }

    private enum Fault { NONE, FLIP, SHORT, IO, NO_SPACE }

    private static final class Entry {
        final long inode;
        Fs.Type type = Fs.Type.FILE;
        byte[] data;
        FileTime mtime = FileTime.fromMillis(1000);

        Entry(long inode, byte[] data) {
            this.inode = inode;
            this.data = data.clone();
        }
    }

    private static final class FaultFs implements Fs {
        final Map<String, Entry> files = new HashMap<>();
        final long dev;
        long nextInode;
        long free = 128L * MIB;
        int creates;
        int partCreates;
        int syncs;
        int partReads;
        int directorySyncs;
        boolean directorySyncFails;
        boolean unknownPartIdentity;
        boolean replacePartOnClose;
        boolean unlinkFails;
        boolean changePartMtime;
        Fault fault = Fault.NONE;
        int faultsLeft;
        boolean mutationDone;
        boolean fillReservation;
        Runnable duringRead;

        FaultFs(long dev) { this.dev = dev; }

        void put(String path, byte[] data) { files.put(path, new Entry(++nextInode, data)); }

        boolean has(String path, byte[] data) {
            Entry entry = files.get(path);
            return entry != null && Arrays.equals(entry.data, data);
        }

        @Override public Stat lstat(String path) {
            Entry e = files.get(path);
            return e == null ? null : new Stat(e.type, e.data.length, e.mtime, e.inode, dev);
        }

        @Override public InputStream openRead(String path, Stat expected, boolean checkMtime) throws IOException {
            if (VerifiedCopy.isPartName(name(path))) {
                partReads++;
                if (changePartMtime) files.get(path).mtime = FileTime.fromMillis(2000);
            }
            Stat actual = lstat(path);
            if (!(checkMtime ? expected.unchanged(actual) : expected.sameFileAndSize(actual))) {
                throw new SourceChanged(path);
            }
            return new ByteArrayInputStream(files.get(path).data) {
                @Override public int read(byte[] buffer, int offset, int count) {
                    int result = super.read(buffer, offset, count);
                    if (result > 0 && duringRead != null && !mutationDone) {
                        mutationDone = true;
                        duringRead.run();
                    }
                    return result;
                }
            };
        }

        @Override public Writer createExclusive(String path) throws IOException {
            if (files.containsKey(path)) throw new FileAlreadyExistsException(path);
            creates++;
            boolean part = VerifiedCopy.isPartName(name(path));
            if (part) partCreates++;
            Fault injected = part && faultsLeft-- > 0 ? fault : Fault.NONE;
            put(path, new byte[0]);
            Entry entry = files.get(path);
            return new Writer() {
                @Override public Stat stat() throws IOException {
                    if (part && unknownPartIdentity) throw new IOException("Cannot stat the part descriptor");
                    return lstat(path);
                }

                @Override public void write(byte[] buffer, int count) throws IOException {
                    if (injected == Fault.IO) throw new IOException("EIO");
                    if (injected == Fault.NO_SPACE) throw new NoSpace(new IOException("ENOSPC"));
                    int offset = entry.data.length;
                    int wrote = injected == Fault.SHORT ? count - 1 : count;
                    entry.data = Arrays.copyOf(entry.data, offset + wrote);
                    System.arraycopy(buffer, 0, entry.data, offset, wrote);
                    if (injected == Fault.FLIP && offset == 0) entry.data[0] ^= 1;
                }

                @Override public void sync() { syncs++; }

                @Override public void close() {
                    if (part && replacePartOnClose) put(path, entry.data);
                    if (!part && fillReservation) {
                        fillReservation = false;
                        entry.data = OTHER.clone();
                    }
                }
            };
        }

        @Override public String child(String folder, String name) { return folder + "/" + name; }

        @Override public List<String> list(String folder) {
            List<String> names = new ArrayList<>();
            for (String path : files.keySet()) {
                if (path.startsWith(folder + "/")) names.add(name(path));
            }
            return names;
        }

        @Override public long freeSpace(String folder) { return free; }

        @Override public void rename(String from, String to) { files.put(to, files.remove(from)); }

        @Override public void syncDirectory(String folder) throws IOException {
            if (list(folder).stream().allMatch(VerifiedCopy::isPartName)) {
                throw new AssertionError("Destination directory synced before publishing the file");
            }
            directorySyncs++;
            if (directorySyncFails) throw new IOException("Directory fsync unsupported");
        }

        @Override public void unlink(String path) throws IOException {
            if (unlinkFails) throw new IOException("Cannot unlink part");
            files.remove(path);
        }

        @Override public void setMtime(String path, FileTime mtime) { files.get(path).mtime = mtime; }
    }

    private static final class Fixture implements VerifiedCopy.Observer {
        final FaultFs src = new FaultFs(1);
        final FaultFs dst = new FaultFs(2);
        final VerifiedCopy.Job job = new VerifiedCopy.Job();
        final List<String> parts = new ArrayList<>();
        final List<String> reservations = new ArrayList<>();

        Fixture() { src.put("/src/movie.mp4", DATA); }

        VerifiedCopy.Result copy() { return copy("movie.mp4", true, () -> false); }

        VerifiedCopy.Result copy(String name, boolean removable, VerifiedCopy.Cancellation cancellation) {
            return VerifiedCopy.copy(src, "/src/movie.mp4", dst, "/dst", name, removable, job, cancellation, this);
        }

        VerifiedCopy.Result copyItem(String name) {
            String source = "/src/" + name;
            src.put(source, DATA);
            return VerifiedCopy.copy(src, source, dst, "/dst", name, true, job, () -> false, this);
        }

        @Override public void partStarted(String path) {
            if (!VerifiedCopy.isPartName(name(path)) || dst.files.containsKey(path)) {
                throw new AssertionError("Expected a recorded part before exclusive creation");
            }
            parts.add(path);
        }

        @Override public void partFinished(String path) {
            if (!parts.remove(path) || dst.files.containsKey(path)) throw new AssertionError("Unfinished temp: " + path);
        }

        @Override public void reserved(String path) { reservations.add(path); }

        @Override public void reservationDone(String path) {
            if (!reservations.remove(path)) throw new AssertionError("Untracked reservation: " + path);
        }

        @Override public void log(String message, Throwable error) {}

        boolean clean() {
            return parts.isEmpty() && reservations.isEmpty()
                    && dst.list("/dst").stream().noneMatch(VerifiedCopy::isPartName);
        }

        boolean sourceKept() { return src.has("/src/movie.mp4", DATA); }

        boolean verified(String path) throws Exception {
            Entry entry = dst.files.get(path);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return entry != null && MessageDigest.isEqual(sha.digest(DATA), sha.digest(entry.data));
        }
    }

    private static String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    private static void check(String name, boolean passed) {
        if (!passed) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) throws Exception {
        Fixture normal = new Fixture();
        normal.src.files.get("/src/movie.mp4").mtime = FileTime.fromMillis(42);
        VerifiedCopy.Result copied = normal.copy();
        check("readback and mtime", copied.succeeded() && normal.verified(copied.target)
                && normal.dst.syncs == 1 && normal.dst.partReads == 1 && normal.dst.directorySyncs == 1
                && normal.dst.lstat(copied.target).mtime.equals(normal.src.lstat("/src/movie.mp4").mtime)
                && normal.clean() && normal.sourceKept());

        Fixture clash = new Fixture();
        clash.dst.put("/dst/MOVIE.mp4", OTHER);
        clash.dst.put("/dst/movie (1).mp4", OTHER);
        VerifiedCopy.Result renamed = clash.copy();
        check("exact and case-only collisions preserve existing files", "/dst/movie (2).mp4".equals(renamed.target)
                && clash.verified(renamed.target) && clash.dst.has("/dst/MOVIE.mp4", OTHER)
                && clash.dst.has("/dst/movie (1).mp4", OTHER) && clash.clean());

        Fixture takeover = new Fixture();
        takeover.dst.fillReservation = true;
        VerifiedCopy.Result published = takeover.copy();
        check("reservation filled by someone else", "/dst/movie (1).mp4".equals(published.target)
                && takeover.verified(published.target) && takeover.dst.has("/dst/movie.mp4", OTHER) && takeover.clean());

        Fixture growing = new Fixture();
        growing.src.duringRead = () -> growing.src.files.get("/src/movie.mp4").data = Arrays.copyOf(DATA, DATA.length + 1);
        check("source growth", growing.copy().reason == VerifiedCopy.Reason.SOURCE_CHANGED
                && growing.src.files.get("/src/movie.mp4").data.length == DATA.length + 1
                && growing.clean() && growing.dst.files.isEmpty());
        Fixture changed = new Fixture();
        changed.src.duringRead = () -> {
            Entry source = changed.src.files.get("/src/movie.mp4");
            source.data[0] ^= 1;
            source.mtime = FileTime.fromMillis(1001);
        };
        check("source rewrite with same length", changed.copy().reason == VerifiedCopy.Reason.SOURCE_CHANGED
                && changed.src.files.get("/src/movie.mp4").data[0] != DATA[0]
                && changed.clean() && changed.dst.files.isEmpty());

        for (Fault fault : new Fault[]{Fault.FLIP, Fault.IO}) {
            Fixture transientFault = new Fixture();
            transientFault.dst.fault = fault;
            transientFault.dst.faultsLeft = 1;
            VerifiedCopy.Result retry = transientFault.copy();
            check("retry succeeds after " + fault, retry.succeeded() && transientFault.verified(retry.target)
                    && transientFault.dst.partCreates == 2 && transientFault.sourceKept() && transientFault.clean());
        }

        for (Fault fault : new Fault[]{Fault.FLIP, Fault.SHORT, Fault.IO, Fault.NO_SPACE}) {
            Fixture broken = new Fixture();
            broken.dst.fault = fault;
            broken.dst.faultsLeft = 2;
            VerifiedCopy.Reason reason = switch (fault) {
                case FLIP -> VerifiedCopy.Reason.HASH_MISMATCH;
                case SHORT -> VerifiedCopy.Reason.SIZE_MISMATCH;
                case IO -> VerifiedCopy.Reason.IO;
                case NO_SPACE -> VerifiedCopy.Reason.NO_SPACE;
                default -> throw new AssertionError(fault);
            };
            check(fault + " leaves source and no output", broken.copy().reason == reason && broken.sourceKept()
                    && broken.dst.files.isEmpty() && broken.clean()
                    && broken.dst.partCreates == (fault == Fault.FLIP || fault == Fault.IO ? 2 : 1));
        }

        Fixture oneFailure = new Fixture();
        oneFailure.dst.fault = Fault.FLIP;
        oneFailure.dst.faultsLeft = 2;
        VerifiedCopy.Result firstFailure = oneFailure.copyItem("first.mp4");
        boolean stoppedAfterOne = oneFailure.job.stopped();
        oneFailure.dst.faultsLeft = 1;
        VerifiedCopy.Result retried = oneFailure.copyItem("second.mp4");
        check("one item mismatching twice does not stop the next item's retry",
                firstFailure.reason == VerifiedCopy.Reason.HASH_MISMATCH && !stoppedAfterOne
                        && retried.succeeded() && oneFailure.verified(retried.target)
                        && oneFailure.dst.partCreates == 4 && oneFailure.clean());

        Fixture consecutive = new Fixture();
        consecutive.dst.fault = Fault.FLIP;
        consecutive.dst.faultsLeft = 4;
        VerifiedCopy.Result first = consecutive.copyItem("first.mp4");
        VerifiedCopy.Result second = consecutive.copyItem("second.mp4");
        check("two consecutive failed items stop the next file",
                first.reason == VerifiedCopy.Reason.HASH_MISMATCH && second.reason == VerifiedCopy.Reason.HASH_MISMATCH
                        && consecutive.job.stopped()
                        && consecutive.copyItem("third.mp4").reason == VerifiedCopy.Reason.HASH_MISMATCH
                        && consecutive.dst.partCreates == 4 && consecutive.dst.files.isEmpty() && consecutive.clean());

        Fixture interruptedStreak = new Fixture();
        interruptedStreak.dst.fault = Fault.FLIP;
        interruptedStreak.dst.faultsLeft = 2;
        VerifiedCopy.Result before = interruptedStreak.copyItem("first.mp4");
        VerifiedCopy.Result success = interruptedStreak.copyItem("second.mp4");
        interruptedStreak.dst.faultsLeft = 2;
        VerifiedCopy.Result after = interruptedStreak.copyItem("third.mp4");
        check("failure, success, failure does not stop the job",
                before.reason == VerifiedCopy.Reason.HASH_MISMATCH && success.succeeded()
                        && after.reason == VerifiedCopy.Reason.HASH_MISMATCH && !interruptedStreak.job.stopped()
                        && interruptedStreak.dst.partCreates == 5 && interruptedStreak.clean());

        Fixture directorySync = new Fixture();
        directorySync.dst.directorySyncFails = true;
        VerifiedCopy.Result publishedDespiteSyncError = directorySync.copy();
        check("directory fsync failure leaves the verified commit successful",
                publishedDespiteSyncError.succeeded() && directorySync.verified(publishedDespiteSyncError.target)
                        && directorySync.dst.directorySyncs == 1 && directorySync.clean());

        Fixture partMtime = new Fixture();
        partMtime.dst.changePartMtime = true;
        VerifiedCopy.Result mtimeChanged = partMtime.copy();
        check("part mtime may change across readback and publish",
                mtimeChanged.succeeded() && partMtime.verified(mtimeChanged.target) && partMtime.clean());

        Fixture unknown = new Fixture();
        unknown.dst.unknownPartIdentity = true;
        check("unknown part identity leaves both attempts recorded", unknown.copy().reason == VerifiedCopy.Reason.IO
                && unknown.parts.size() == 2 && unknown.dst.files.keySet().containsAll(unknown.parts));
        Fixture replaced = new Fixture();
        replaced.dst.replacePartOnClose = true;
        check("changed part identity leaves both attempts recorded", replaced.copy().reason == VerifiedCopy.Reason.IO
                && replaced.parts.size() == 2 && replaced.dst.files.keySet().containsAll(replaced.parts));
        Fixture stuck = new Fixture();
        stuck.dst.unlinkFails = true;
        stuck.dst.fault = Fault.NO_SPACE;
        stuck.dst.faultsLeft = 1;
        check("failed part unlink leaves the part recorded", stuck.copy().reason == VerifiedCopy.Reason.NO_SPACE
                && stuck.parts.size() == 1 && stuck.dst.files.keySet().containsAll(stuck.parts));

        Fixture reserveSpace = new Fixture();
        reserveSpace.dst.free = DATA.length + 64L * MIB - 1;
        check("64 MiB headroom is required before copying", reserveSpace.copy().reason == VerifiedCopy.Reason.NO_SPACE
                && reserveSpace.dst.creates == 0 && reserveSpace.sourceKept());

        Fixture exactly = new Fixture();
        exactly.dst.free = DATA.length + 64L * MIB;
        check("exact space and 255 UTF-8 bytes allowed", exactly.copy("é".repeat(127) + "x", true, () -> false).succeeded());
        for (String invalid : new String[]{"é".repeat(128), "bad\"name", "bad*name", "bad:name", "bad<name", "bad>name",
                "bad?name", "bad\\name", "bad|name", "bad.", "bad ", ".pending-x", ".Trashed-x"}) {
            Fixture fixture = new Fixture();
            check("name refused " + invalid, fixture.copy(invalid, true, () -> false).reason == VerifiedCopy.Reason.NAME_INVALID
                    && fixture.dst.creates == 0 && fixture.sourceKept());
        }
        Fixture longClash = new Fixture();
        String longName = "a".repeat(255);
        longClash.dst.put("/dst/" + longName, OTHER);
        check("conflict suffix cannot exceed 255 bytes", longClash.copy(longName, true, () -> false).reason
                == VerifiedCopy.Reason.NAME_INVALID && longClash.dst.creates == 0 && longClash.dst.has("/dst/" + longName, OTHER));

        Fixture localName = new Fixture();
        check("internal volume accepts a colon", localName.copy("a:b", false, () -> false).succeeded());
        Fixture link = new Fixture();
        link.src.files.get("/src/movie.mp4").type = Fs.Type.LINK;
        check("link source is refused", link.copy().reason == VerifiedCopy.Reason.IO && link.dst.creates == 0);
        Fixture midCopy = new Fixture();
        midCopy.src.duringRead = () -> {};
        check("mid-copy cancellation cleans up", midCopy.copy("movie.mp4", false, () -> midCopy.src.mutationDone).reason
                == VerifiedCopy.Reason.CANCELLED && midCopy.dst.partCreates == 1 && midCopy.clean() && midCopy.sourceKept());
        System.out.println("Verified copy: ALL PASS (" + checks + " checks)");
    }
}
