package app.filesplus.extension.ops;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VerifiedFolderMoveSelfTest {
    private static final String SOURCE = "/src/Folder";
    private static final String DEST = "/dst";
    private static final long MIB = 1024 * 1024;
    private static int checks;

    private static final class Node {
        final Fs.Type type;
        final long device;
        final long inode;
        byte[] bytes;
        long reportedSize = -1;
        FileTime mtime = FileTime.fromMillis(1000);

        Node(Fs.Type type, long device, long inode, byte[] bytes) {
            this.type = type;
            this.device = device;
            this.inode = inode;
            this.bytes = bytes;
        }
    }

    private static final class FaultFs implements Fs {
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final long device;
        long inode;
        long free = 1000 * MIB;
        int makes;
        int renameCount;
        int writesSynced;
        int readsVerified;
        int failWrites;
        int flipWrites;
        boolean fillReservation;
        IOException emptyRenameFailure;
        boolean failUnlink;
        Runnable afterPublish;

        FaultFs(long device) { this.device = device; }

        void put(String path, Type type, long dev, byte... bytes) {
            nodes.put(path, new Node(type, dev, ++inode, bytes));
        }

        void dir(String path) { put(path, Type.DIRECTORY, device); }

        void file(String path, int value) { put(path, Type.FILE, device, (byte) value); }

        @Override public Stat lstat(String path) {
            Node node = nodes.get(path);
            return node == null ? null : new Stat(node.type,
                    node.reportedSize < 0 ? node.bytes.length : node.reportedSize, node.mtime, node.inode, node.device);
        }

        @Override public Directory openDirectory(String path, Stat expected) throws IOException {
            Stat current = lstat(path);
            if (current == null || current.type != Type.DIRECTORY || !expected.sameFile(current)) {
                throw new IOException("Not the expected directory " + path);
            }
            return new Directory() {
                @Override public String path() { return path; }
                @Override public void close() {}
            };
        }

        @Override public Stat mkdir(String path) throws IOException {
            if (nodes.containsKey(path)) throw new FileAlreadyExistsException(path);
            dir(path);
            makes++;
            return lstat(path);
        }

        @Override public void rmdir(String path) throws IOException {
            if (!list(path, 1).isEmpty()) throw new DirectoryNotEmptyException(path);
            if (nodes.get(path).type != Type.DIRECTORY) throw new IOException("Expected directory: " + path);
            nodes.remove(path);
        }

        @Override public InputStream openRead(String path, Stat expected, boolean checkMtime) throws IOException {
            Stat actual = lstat(path);
            if (!(checkMtime ? expected.unchanged(actual) : expected.sameFileAndSize(actual))) throw new SourceChanged(path);
            if (!checkMtime) readsVerified++;
            return new ByteArrayInputStream(nodes.get(path).bytes);
        }

        @Override public Writer createExclusive(String path) throws IOException {
            if (nodes.containsKey(path)) throw new FileAlreadyExistsException(path);
            file(path, 0);
            Node node = nodes.get(path);
            node.bytes = new byte[0];
            boolean fail = path.endsWith("/middle") && failWrites-- > 0;
            boolean flip = path.endsWith("/middle") && flipWrites-- > 0;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            return new Writer() {
                @Override public Stat stat() { return lstat(path); }

                @Override public void write(byte[] buffer, int count) throws IOException {
                    if (fail) throw new IOException("Injected EIO");
                    out.write(buffer, 0, count);
                }

                @Override public void sync() {
                    node.bytes = out.toByteArray();
                    if (flip) node.bytes[0] ^= 1;
                    writesSynced++;
                }

                @Override public void close() {}
            };
        }

        @Override public String child(String folder, String name) { return folder + "/" + name; }

        @Override public List<String> list(String folder) { return list(folder, Integer.MAX_VALUE); }

        @Override public List<String> list(String folder, int limit) {
            if (nodes.get(folder).type != Type.DIRECTORY) throw new AssertionError("Followed non-directory: " + folder);
            List<String> result = new ArrayList<>();
            String prefix = folder + "/";
            for (String path : nodes.keySet()) {
                if (path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0) {
                    result.add(path.substring(prefix.length()));
                    if (result.size() == limit) break;
                }
            }
            return result;
        }

        @Override public long freeSpace(String folder) { return free; }

        @Override public void rename(String from, String to) throws IOException {
            if (emptyRenameFailure != null && to.startsWith(DEST + "/")) throw emptyRenameFailure;
            if (fillReservation && to.equals(DEST + "/Folder")) {
                fillReservation = false;
                file(to + "/intruder", 99);
            }
            if (nodes.containsKey(to) && !list(to, 1).isEmpty()) throw new DirectoryNotEmptyException(to);
            Map<String, Node> moved = new LinkedHashMap<>();
            for (String path : new ArrayList<>(nodes.keySet())) {
                if (path.equals(from) || path.startsWith(from + "/")) {
                    moved.put(to + path.substring(from.length()), nodes.remove(path));
                }
            }
            if (moved.isEmpty()) throw new IOException("Nothing to rename");
            nodes.putAll(moved);
            renameCount++;
            if (afterPublish != null) afterPublish.run();
        }

        @Override public void syncDirectory(String folder) {}

        @Override public void unlink(String path) throws IOException {
            if (failUnlink) throw new IOException("Injected unlink failure");
            if (nodes.get(path).type == Type.DIRECTORY) throw new IOException("Expected non-directory: " + path);
            nodes.remove(path);
        }

        @Override public void setMtime(String path, FileTime mtime) { nodes.get(path).mtime = mtime; }
    }

    private static final class Fixture implements VerifiedCopy.Observer {
        final FaultFs src = new FaultFs(1);
        final FaultFs dst = new FaultFs(2);
        final List<String> recorded = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final VerifiedCopy.Job job = new VerifiedCopy.Job();
        Runnable afterPlan;
        int reservations;
        boolean removable = true;
        VerifiedFolderMove.PasteBudget budget;
        boolean trashed;
        long trashFiles;
        long trashBytes;

        Fixture() {
            src.dir("/src");
            src.dir(SOURCE);
            src.file(SOURCE + "/first", 1);
            src.dir(SOURCE + "/nested");
            src.file(SOURCE + "/nested/middle", 2);
            src.dir(SOURCE + "/empty");
            src.file(SOURCE + "/last", 3);
            dst.dir(DEST);
        }

        VerifiedFolderMove.Result move() { return move(dst, DEST); }

        VerifiedFolderMove.Result move(FaultFs targetFs, String targetFolder) {
            return VerifiedFolderMove.move(src, SOURCE, targetFs, targetFolder, "Folder", removable, job, () -> false,
                    this, (opId, count, bytes, check) -> {
                        if (dst.renameCount != 1) throw new AssertionError("Source check before final publish");
                        check.check();
                        src.rename(SOURCE, "/src/trash");
                        trashed = true;
                        trashFiles = count;
                        trashBytes = bytes;
                    }, budget);
        }

        @Override public void partStarted(String path) {
            if (!VerifiedCopy.isPartName(new File(path).getName()) || dst.nodes.containsKey(path)) {
                throw new AssertionError("Part must be recorded before creating it: " + path);
            }
            recorded.add(path);
            if (afterPlan != null) afterPlan.run();
        }

        @Override public void partFinished(String path) { recorded.remove(path); }
        @Override public void reserved(String path) { reservations++; }
        @Override public void reservationDone(String path) {}
        @Override public void log(String message, Throwable error) {
            logs.add(message);
            if (error != null) logs.add(error.getMessage());
        }
    }

    private static void expect(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }

    private static boolean has(FaultFs fs, String path, int value) {
        Node node = fs.nodes.get(path);
        return node != null && Arrays.equals(node.bytes, new byte[]{(byte) value});
    }

    private static boolean complete(Fixture f, String target, int middle) {
        return f.dst.nodes.containsKey(target + "/empty") && has(f.dst, target + "/first", 1)
                && has(f.dst, target + "/nested/middle", middle) && has(f.dst, target + "/last", 3);
    }

    private static void refused(Fixture f, VerifiedCopy.Reason reason, String label) {
        VerifiedFolderMove.Result result = f.move();
        expect(label, result.reason == reason && f.dst.makes == 0 && f.recorded.isEmpty() && !f.trashed);
    }

    private static void prechecks() {
        Fixture link = new Fixture();
        link.src.put(SOURCE + "/file-link", Fs.Type.LINK, 1);
        refused(link, VerifiedCopy.Reason.LINK, "file symlink refused without writes");
        Fixture dirLink = new Fixture();
        dirLink.src.put(SOURCE + "/nested/dir-link", Fs.Type.LINK, 1);
        dirLink.src.put(SOURCE + "/nested/dir-link/file", Fs.Type.FILE, 1);
        refused(dirLink, VerifiedCopy.Reason.LINK, "directory symlink refused without writes");
        Fixture special = new Fixture();
        special.src.put(SOURCE + "/pipe", Fs.Type.OTHER, 1);
        refused(special, VerifiedCopy.Reason.LINK, "special file refused without writes");
        Fixture mounted = new Fixture();
        mounted.src.put(SOURCE + "/nested/mount", Fs.Type.DIRECTORY, 3);
        refused(mounted, VerifiedCopy.Reason.LINK, "submount refused without writes");
        Fixture self = new Fixture();
        expect("move into self and below self refused", self.move(self.src, SOURCE).reason == VerifiedCopy.Reason.IO
                && self.move(self.src, SOURCE + "/nested").reason == VerifiedCopy.Reason.IO && self.src.makes == 0);

        for (String name : List.of("bad:name", "trailing.", "trailing ", ".pending-asset", ".trashed-asset",
                "é".repeat(128))) {
            Fixture invalid = new Fixture();
            invalid.src.file(SOURCE + "/nested/" + name, 9);
            refused(invalid, VerifiedCopy.Reason.NAME_INVALID, "invalid nested destination name: " + name);
        }
        Fixture internalDuplicate = new Fixture();
        internalDuplicate.removable = false;
        internalDuplicate.src.dir(SOURCE + "/nested/Photos");
        internalDuplicate.src.file(SOURCE + "/nested/photos", 9);
        refused(internalDuplicate, VerifiedCopy.Reason.NAME_INVALID, "mixed case duplicate refused for internal storage");
        Fixture sdDuplicate = new Fixture();
        sdDuplicate.src.file(SOURCE + "/nested/MIDDLE", 8);
        refused(sdDuplicate, VerifiedCopy.Reason.NAME_INVALID, "case-only file names refused for SD");
        Fixture space = new Fixture();
        space.dst.free = 64 * MIB + 2;
        refused(space, VerifiedCopy.Reason.NO_SPACE, "free space requires all bytes plus 64 MiB");
        Fixture deep = new Fixture();
        String path = SOURCE;
        for (int i = 0; i < 64; i++) {
            path += "/d";
            deep.src.dir(path);
        }
        deep.dst.free = 0;
        refused(deep, VerifiedCopy.Reason.NO_SPACE, "depth 64 is allowed");
        deep.src.file(path + "/too-deep", 0);
        refused(deep, VerifiedCopy.Reason.TOO_LARGE, "depth 65 refused");
        Fixture wide = new Fixture();
        for (int i = wide.src.nodes.size() - 1; i < 100_000; i++) wide.src.file(SOURCE + "/f" + i, 0);
        wide.dst.free = 0;
        refused(wide, VerifiedCopy.Reason.NO_SPACE, "100,000 manifest entries allowed");
        wide.src.file(SOURCE + "/extra", 0);
        refused(wide, VerifiedCopy.Reason.TOO_LARGE, "100,001 manifest entries refused");
    }

    private static void moves() {
        Fixture normal = new Fixture();
        VerifiedFolderMove.Result got = normal.move();
        expect("all files, empty folders and mtimes copied", complete(normal, got.target, 2)
                && normal.dst.lstat(got.target + "/nested").mtime.equals(FileTime.fromMillis(1000)));
        expect("all files synced and read back", normal.dst.writesSynced == 3 && normal.dst.readsVerified == 3);
        expect("whole source renamed once into trash", normal.trashed && normal.src.renameCount == 1
                && !normal.src.nodes.containsKey(SOURCE) && has(normal.src, "/src/trash/nested/middle", 2)
                && normal.trashFiles == 3 && normal.trashBytes == 3 && normal.recorded.isEmpty());
        expect("media paths include folders and files", got.children.equals(List.of("first", "nested",
                "nested/middle", "empty", "last")));

        Fixture added = new Fixture();
        added.afterPlan = () -> added.src.file(SOURCE + "/nested/added", 4);
        got = added.move();
        expect("entry added after plan keeps original and publishes planned files",
                got.sourceKept && complete(added, got.target, 2) && !added.trashed
                        && has(added.src, SOURCE + "/nested/added", 4)
                        && !added.dst.nodes.containsKey(got.target + "/nested/added")
                        && added.logs.contains("Added source entry: nested/added"));
        Fixture editedBeforeRead = new Fixture();
        editedBeforeRead.afterPlan = () -> {
            Node item = editedBeforeRead.src.nodes.get(SOURCE + "/nested/middle");
            item.bytes = new byte[]{7};
            item.mtime = FileTime.fromMillis(7000);
        };
        got = editedBeforeRead.move();
        expect("entry modified before reading is verified and original is kept",
                got.sourceKept && complete(editedBeforeRead, got.target, 7) && !editedBeforeRead.trashed);
        Fixture modified = new Fixture();
        modified.dst.afterPublish = () -> {
            Node item = modified.src.nodes.get(SOURCE + "/nested/middle");
            item.bytes = new byte[]{5};
            item.mtime = FileTime.fromMillis(5000);
        };
        got = modified.move();
        expect("entry modified after plan keeps original and publishes verified version",
                got.sourceKept && complete(modified, got.target, 2) && !modified.trashed
                        && has(modified.src, SOURCE + "/nested/middle", 5)
                        && modified.logs.contains("Changed source mtime: nested/middle"));
        Fixture removed = new Fixture();
        removed.dst.afterPublish = () -> removed.src.nodes.remove(SOURCE + "/last");
        got = removed.move();
        expect("removed entry keeps original and is logged", got.sourceKept && complete(removed, got.target, 2)
                && !removed.trashed && removed.logs.contains("Removed source entry: last"));

        Fixture differentStats = new Fixture();
        differentStats.dst.afterPublish = () -> {
            differentStats.src.put(SOURCE + "/first", Fs.Type.LINK, 1);
            differentStats.src.nodes.get(SOURCE + "/nested/middle").bytes = new byte[]{1, 2};
        };
        got = differentStats.move();
        expect("re-walk logs first path for each changed stat",
                got.sourceKept && complete(differentStats, got.target, 2)
                        && differentStats.logs.contains("Changed source type: first")
                        && differentStats.logs.contains("Changed source size: first")
                        && !differentStats.logs.contains("Changed source size: nested/middle")
                        && differentStats.logs.contains("Changed source inode: first"));

        Fixture filled = new Fixture();
        filled.dst.fillReservation = true;
        got = filled.move();
        expect("ENOTEMPTY during rename keeps filled reservation and chooses next name",
                (DEST + "/Folder (1)").equals(got.target) && complete(filled, got.target, 2)
                        && has(filled.dst, DEST + "/Folder/intruder", 99));
        Fixture clash = new Fixture();
        clash.dst.dir(DEST + "/folder");
        got = clash.move();
        expect("case-only clash keeps existing directory and chooses next name",
                (DEST + "/Folder (1)").equals(got.target) && clash.dst.nodes.containsKey(DEST + "/folder")
                        && complete(clash, got.target, 2));
    }

    private static void failures() throws IOException {
        for (IOException error : List.of(new DirectoryNotEmptyException("Folder"),
                new FileAlreadyExistsException("Folder"))) {
            Fixture platform = new Fixture();
            platform.dst.emptyRenameFailure = error;
            VerifiedFolderMove.Result result = platform.move();
            expect("platform error on empty reservation stops and rolls back: " + error.getClass().getSimpleName(),
                    result.reason == VerifiedCopy.Reason.IO && platform.reservations == 1
                            && platform.dst.list(DEST).isEmpty() && platform.recorded.isEmpty() && !platform.trashed
                            && has(platform.src, SOURCE + "/nested/middle", 2)
                            && platform.logs.contains("platform refused rename onto an empty folder"));
        }
        Fixture retry = new Fixture();
        retry.dst.failWrites = 1;
        VerifiedFolderMove.Result got = retry.move();
        expect("middle file retries once after EIO", complete(retry, got.target, 2) && retry.trashed);

        Fixture failed = new Fixture();
        failed.dst.failWrites = 2;
        got = failed.move();
        expect("permanent middle failure cleans staged tree and leaves original",
                got.reason == VerifiedCopy.Reason.IO && failed.dst.list(DEST).isEmpty()
                        && has(failed.src, SOURCE + "/nested/middle", 2) && !failed.trashed && failed.recorded.isEmpty());
        Fixture cleanupFailed = new Fixture();
        cleanupFailed.dst.failWrites = 2;
        cleanupFailed.dst.failUnlink = true;
        got = cleanupFailed.move();
        expect("failed cleanup keeps part record and source", got.reason == VerifiedCopy.Reason.IO
                && cleanupFailed.recorded.size() == 1 && !cleanupFailed.trashed);
        cleanupFailed.dst.failUnlink = false;
        TemporaryMoveCleanup.clean(cleanupFailed.dst, DEST, cleanupFailed.dst.lstat(DEST),
                cleanupFailed.recorded.get(0), true, message -> { throw new AssertionError(message); });
        expect("startup retries a failed rollback", cleanupFailed.dst.list(DEST).isEmpty());

        Fixture hashes = new Fixture();
        hashes.dst.flipWrites = 4;
        expect("folder hash mismatch consumes one job item after its retry",
                hashes.move().reason == VerifiedCopy.Reason.HASH_MISMATCH && !hashes.job.stopped());
        expect("two consecutive folder hash failures stop job",
                hashes.move().reason == VerifiedCopy.Reason.HASH_MISMATCH && hashes.job.stopped());
        int made = hashes.dst.makes;
        expect("stopped job leaves the remaining folder untouched",
                hashes.move().reason == VerifiedCopy.Reason.HASH_MISMATCH && hashes.dst.makes == made);
    }

    private static void recovery() throws IOException {
        FaultFs fs = new FaultFs(1);
        fs.dir(DEST);
        fs.dir("/outside");
        fs.file("/outside/keep", 9);
        String recorded = DEST + "/.filesplus-0123456789abcdef0123456789abcdef-0.part";
        String unrelated = DEST + "/.filesplus-0123456789abcdef0123456789abcdef-1.part";
        String invalid = DEST + "/.filesplus-other-0.part";
        String wrongParent = "/outside/" + new File(recorded).getName();
        fs.dir(recorded);
        fs.dir(recorded + "/dir");
        fs.file(recorded + "/dir/file", 2);
        fs.put(recorded + "/link-to-outside", Fs.Type.LINK, 1);
        fs.dir(unrelated);
        fs.file(unrelated + "/keep", 3);
        fs.dir(invalid);
        fs.dir(wrongParent);
        Fs.Stat parent = fs.lstat(DEST);
        List<String> dropped = new ArrayList<>();
        TemporaryMoveCleanup.clean(fs, DEST, parent, wrongParent, true, dropped::add);
        TemporaryMoveCleanup.clean(fs, DEST, parent, invalid, true, dropped::add);
        TemporaryMoveCleanup.clean(fs, DEST, parent, recorded, true, dropped::add);
        TemporaryMoveCleanup.clean(fs, DEST, parent, recorded, true, dropped::add);
        expect("recovery logs exact rejected record paths",
                dropped.size() == 2 && dropped.get(0).contains("outside its destination")
                        && dropped.get(0).endsWith(wrongParent) && dropped.get(1).contains("invalid name")
                        && dropped.get(1).endsWith(invalid));
        expect("startup removes only recorded matching folder and is idempotent",
                fs.lstat(recorded) == null && has(fs, unrelated + "/keep", 3) && fs.lstat(invalid) != null
                        && fs.lstat(wrongParent) != null && has(fs, "/outside/keep", 9));

        fs.put(recorded, Fs.Type.LINK, 1);
        blockedCleanup(fs, recorded, true);
        expect("startup does not traverse a part that became a symlink", fs.lstat(recorded).type == Fs.Type.LINK);
        fs.file(recorded, 8);
        blockedCleanup(fs, recorded, true);
        expect("startup does not unlink a regular file at a recorded folder path", has(fs, recorded, 8));
        fs.put(recorded, Fs.Type.DIRECTORY, 2);
        fs.file(recorded + "/mount-child", 6);
        blockedCleanup(fs, recorded, true);
        expect("startup does not enter a recorded directory on another device", has(fs, recorded + "/mount-child", 6));
        fs.dir(recorded);
        fs.nodes.remove(recorded + "/mount-child");
        fs.put(recorded + "/mount", Fs.Type.DIRECTORY, 2);
        fs.put(recorded + "/mount/hidden", Fs.Type.FILE, 2, (byte) 6);
        blockedCleanup(fs, recorded, true);
        expect("startup does not enter nested other-device directory", has(fs, recorded + "/mount/hidden", 6));
        blockedCleanup(fs, recorded, false);
        expect("legacy file record cannot remove a directory", fs.lstat(recorded).type == Fs.Type.DIRECTORY);
    }

    private static void blockedCleanup(FaultFs fs, String part, boolean directory) throws IOException {
        try {
            TemporaryMoveCleanup.clean(fs, DEST, fs.lstat(DEST), part, directory,
                    message -> { throw new AssertionError(message); });
            throw new AssertionError("Expected cleanup to stop at " + part);
        } catch (IOException expected) {
            // The caller retains this record.
        }
    }

    private static void pasteLimits() throws IOException {
        final long maxBytes = 100 * MIB;
        Fixture entries = new Fixture();
        for (int i = 0; i < 44; i++) entries.src.file(SOURCE + "/nested/extra" + i, 0);
        String sibling = "/src/sibling";
        entries.src.file(sibling, 0);
        entries.budget = VerifiedFolderMove.measurePaste(entries.src, List.of(SOURCE, sibling), 50, maxBytes);
        expect("50 current entries including a sibling accepted", entries.budget != null);
        entries.src.file(SOURCE + "/grew", 0);
        expect("51 current entries across a paste refused during preflight",
                VerifiedFolderMove.measurePaste(entries.src, List.of(SOURCE, sibling), 50, maxBytes) == null);
        refused(entries, VerifiedCopy.Reason.TOO_LARGE, "later folder growth cannot exceed measured batch budget");

        Fixture bytes = new Fixture();
        bytes.src.file(sibling, 0);
        bytes.src.nodes.get(sibling).reportedSize = maxBytes - 3;
        bytes.budget = VerifiedFolderMove.measurePaste(bytes.src, List.of(SOURCE, sibling), 50, maxBytes);
        expect("100 MiB including a sibling accepted", bytes.budget != null);
        bytes.src.nodes.get(SOURCE + "/last").reportedSize = 2;
        expect("more than 100 MiB refused during preflight",
                VerifiedFolderMove.measurePaste(bytes.src, List.of(SOURCE, sibling), 50, maxBytes) == null);
        refused(bytes, VerifiedCopy.Reason.TOO_LARGE, "later byte growth cannot exceed measured batch budget");
    }

    public static void main(String[] args) throws IOException {
        prechecks();
        pasteLimits();
        moves();
        failures();
        recovery();
        System.out.println("Verified folder move: ALL PASS (" + checks + " checks)");
    }
}
