package app.filesplus.extension.clip;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;

import org.json.JSONObject;

import app.filesplus.extension.browse.Category;
import app.filesplus.extension.browse.LocalFolders;
import app.filesplus.extension.browse.Location;
import app.filesplus.extension.move.MoveQueue;
import app.filesplus.extension.move.DestinationHistory;
import app.filesplus.extension.foldertabs.TabMove;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Self test of the clipboard and the paste rules (diagnostics: "Paste rules test"). {@link #runRules} is pure Java
 * (java.io only) and also runs on a plain JVM; {@link #runDevice} adds the checks that need the phone: its real
 * storage volumes, the clipboard store and one real {@link MoveQueue#paste}. Every file it makes lives in a
 * temporary folder that is removed again. The user's own clipboard is never written; the last-folder preference is restored.
 */
public final class PasteSelfTest {
    private static final long MB = 1024L * 1024;

    private PasteSelfTest() {}

    /** Collects PASS / FAIL lines. */
    private static final class Report {
        final StringBuilder text = new StringBuilder();
        int failed;

        void check(String name, boolean ok, String detail) {
            text.append(ok ? "PASS " : "FAIL ").append(name);
            if (!ok && detail != null) text.append("  (").append(detail).append(')');
            text.append('\n');
            if (!ok) failed++;
        }

        void rule(String name, PasteRules.Result got, PasteRules.Reason expected) {
            boolean ok = expected == null ? got.ok && !got.needsPicker : !got.ok && got.reason == expected;
            check(name, ok, "expected " + (expected == null ? "ok" : expected) + ", got " + got);
        }

        void skip(String name, String why) {
            text.append("SKIP ").append(name).append("  (").append(why).append(")\n");
        }

        String finish() {
            return text.append(failed == 0 ? "ALL PASS" : failed + " FAILED").toString();
        }
    }

    // ---- pure Java part ---------------------------------------------------------------------------------------

    /**
     * Checks every {@link PasteRules.Reason} in {@code base}, a folder that must exist and be writable and where
     * this creates two fake volumes (volA, volB) and removes nothing. Returns the report.
     */
    public static String runRules(File base) {
        Report report = new Report();
        try {
            rules(report, base.getCanonicalFile());
        } catch (Throwable t) {
            report.check("unexpected exception", false, t.toString());
        }
        return report.finish();
    }

    private static void rules(Report r, File base) throws IOException {
        File a = mkdir(new File(base, "volA"));
        File b = mkdir(new File(base, "volB"));
        List<File> roots = Arrays.asList(a, b);
        File src = mkdir(new File(a, "src"));
        File child = mkdir(new File(src, "child"));
        File dst = mkdir(new File(a, "dst"));
        File otherVolume = mkdir(new File(b, "dst2"));
        File fileA = write(new File(src, "a.txt"), "A");
        File fileB = write(new File(src, "b.txt"), "B");
        File inDst = write(new File(dst, "d.txt"), "D");
        mkdir(new File(a, "Android/data/x"));
        mkdir(new File(a, ".FilesPlus/trash"));

        // EMPTY
        r.rule("EMPTY: nothing on the clipboard", PasteRules.check(state(), folder(dst, roots)), PasteRules.Reason.EMPTY);
        // OK
        r.rule("OK: a file into another folder", PasteRules.check(state(item(fileA)), folder(dst, roots)), null);
        // SAME_FOLDER
        r.rule("SAME_FOLDER: every item is already there",
                PasteRules.check(state(item(fileA), item(fileB)), folder(src, roots)), PasteRules.Reason.SAME_FOLDER);
        r.rule("OK: only some items are already there (those are skipped)",
                PasteRules.check(state(item(fileA), item(inDst)), folder(dst, roots)), null);
        // INTO_SELF
        r.rule("INTO_SELF: a folder into its own child",
                PasteRules.check(state(item(src)), folder(child, roots)), PasteRules.Reason.INTO_SELF);
        r.rule("INTO_SELF: a folder into itself",
                PasteRules.check(state(item(src)), folder(src, roots)), PasteRules.Reason.INTO_SELF);
        r.rule("OK: a folder into a sibling", PasteRules.check(state(item(src)), folder(dst, roots)), null);
        // PROTECTED
        r.rule("PROTECTED: into Android/data",
                PasteRules.check(state(item(fileA)), folder(new File(a, "Android/data/x"), roots)), PasteRules.Reason.PROTECTED);
        r.rule("PROTECTED: into Android itself",
                PasteRules.check(state(item(fileA)), folder(new File(a, "Android"), roots)), PasteRules.Reason.PROTECTED);
        r.rule("PROTECTED: into .FilesPlus/trash",
                PasteRules.check(state(item(fileA)), folder(new File(a, ".FilesPlus/trash"), roots)), PasteRules.Reason.PROTECTED);
        r.rule("PROTECTED: into .filesplus (other case)",
                PasteRules.check(state(item(fileA)), folder(new File(a, ".filesplus"), roots)), PasteRules.Reason.PROTECTED);
        r.rule("PROTECTED: an item inside Android/",
                PasteRules.check(state(item(new File(a, "Android/data/x/f.bin"), 1, false)), folder(dst, roots)),
                PasteRules.Reason.PROTECTED);
        r.rule("PROTECTED: a place outside every volume",
                PasteRules.check(state(item(fileA)), folder(base, roots)), PasteRules.Reason.PROTECTED);
        r.rule("OK: the volume root itself is not protected", PasteRules.check(state(item(fileA)), folder(a, roots)), null);
        // NOT_WRITABLE
        r.rule("NOT_WRITABLE: a folder that does not exist",
                PasteRules.check(state(item(fileA)), folder(new File(a, "missing"), roots)), PasteRules.Reason.NOT_WRITABLE);
        r.rule("NOT_WRITABLE: a file instead of a folder",
                PasteRules.check(state(item(fileA)), folder(inDst, roots)), PasteRules.Reason.NOT_WRITABLE);
        r.rule("OK: a missing folder with createIfMissing below an existing one",
                PasteRules.check(state(item(fileA)),
                        PasteTarget.ofAllTab(Category.IMAGES, "default", new File(a, "Pictures"), null, roots)), null);
        r.rule("NOT_WRITABLE: createIfMissing but the parent is missing too",
                PasteRules.check(state(item(fileA)),
                        PasteTarget.ofAllTab(Category.IMAGES, "default", new File(a, "no/such/Pictures"), null, roots)),
                PasteRules.Reason.NOT_WRITABLE);
        // CROSS_VOLUME
        r.rule("CROSS_VOLUME: item on volume A, target on volume B",
                PasteRules.check(state(item(fileA)), folder(otherVolume, roots)), PasteRules.Reason.CROSS_VOLUME);
        // TOO_LARGE
        r.rule("TOO_LARGE: 51 items", PasteRules.check(state(many(src, 51, 1)), folder(dst, roots)), PasteRules.Reason.TOO_LARGE);
        r.rule("OK: exactly 50 items", PasteRules.check(state(many(src, 50, 1)), folder(dst, roots)), null);
        r.rule("TOO_LARGE: one item of 100MB + 1 byte",
                PasteRules.check(state(item(new File(src, "big.bin"), PasteRules.MAX_BYTES + 1, false)), folder(dst, roots)),
                PasteRules.Reason.TOO_LARGE);
        r.rule("OK: exactly 100MB",
                PasteRules.check(state(item(new File(src, "big.bin"), PasteRules.MAX_BYTES, false)), folder(dst, roots)), null);
        r.rule("TOO_LARGE: two items of 60MB",
                PasteRules.check(state(item(new File(src, "x.bin"), 60 * MB, false), item(new File(src, "y.bin"), 60 * MB, false)),
                        folder(dst, roots)), PasteRules.Reason.TOO_LARGE);
        File small = mkdir(new File(src, "small"));
        for (int i = 0; i < 3; i++) write(new File(small, "f" + i), "abc");
        ClipItem measured = item(small);
        r.check("measure: 3 small files counted and sized", measured.fileCount() == 3
                && measured.size() == 9 && !measured.overLimit(), null);
        r.rule("OK: folder with 3 small files", PasteRules.check(state(measured), folder(dst, roots)), null);
        File large = mkdir(new File(src, "large"));
        for (int i = 0; i < 51; i++) write(new File(large, "f" + i), "x");
        r.rule("TOO_LARGE: folder with 51 files", PasteRules.check(state(item(large)), folder(dst, roots)),
                PasteRules.Reason.TOO_LARGE);
        r.rule("TOO_LARGE: folder with two files totaling 120MB", PasteRules.check(state(new ClipItem(
                small.getPath(), 0, small.getName(), 120 * MB, true, 2, false)), folder(dst, roots)),
                PasteRules.Reason.TOO_LARGE);
        File bytes = mkdir(new File(src, "bytes"));
        write(new File(bytes, "one"), String.join("", Collections.nCopies(600, "x")));
        write(new File(bytes, "two"), String.join("", Collections.nCopies(600, "x")));
        ClipItem limited = ClipItem.measure(bytes, 0, 50, 1000);
        r.check("measure: recursive bytes stop above limit", limited.overLimit()
                && limited.fileCount() == 2 && limited.size() == 1200, null);
        r.rule("TOO_LARGE: old unmeasured folder", PasteRules.check(state(item(small, 0, true)),
                folder(dst, roots)), PasteRules.Reason.TOO_LARGE);
        // SMB
        r.rule("SMB: an SMB target",
                PasteRules.check(state(item(fileA)), PasteTarget.ofLocation(Location.smb("c1", "share", "a"))), PasteRules.Reason.SMB);
        r.rule("SMB: an SMB item", PasteRules.check(state(new ClipItem("smb://c1/share/a.txt", 0, "a.txt", 1, false)),
                folder(dst, roots)), PasteRules.Reason.SMB);
        // ALL_TAB_DISABLED and the other "All" tab settings
        r.rule("ALL_TAB_DISABLED: setting off",
                PasteRules.check(state(item(fileA)), PasteTarget.ofAllTab(Category.IMAGES, "off", null, null, roots)),
                PasteRules.Reason.ALL_TAB_DISABLED);
        r.rule("ALL_TAB_DISABLED: an unknown setting",
                PasteRules.check(state(item(fileA)), PasteTarget.ofAllTab(Category.IMAGES, "x", null, null, roots)),
                PasteRules.Reason.ALL_TAB_DISABLED);
        r.rule("ALL_TAB_DISABLED: last folder, none recorded",
                PasteRules.check(state(item(fileA)), PasteTarget.ofAllTab(Category.IMAGES, "last", null, null, roots)),
                PasteRules.Reason.ALL_TAB_DISABLED);
        r.rule("ALL_TAB_DISABLED: last folder is gone",
                PasteRules.check(state(item(fileA)),
                        PasteTarget.ofAllTab(Category.IMAGES, "last", null, new File(a, "gone"), roots)),
                PasteRules.Reason.ALL_TAB_DISABLED);
        r.rule("OK: last folder exists",
                PasteRules.check(state(item(fileA)), PasteTarget.ofAllTab(Category.IMAGES, "last", null, dst, roots)), null);
        PasteRules.Result ask = PasteRules.check(state(item(fileA)), PasteTarget.ofAllTab(Category.IMAGES, "ask", null, null, roots));
        r.check("OK (pick a folder first): setting ask", ask.ok && ask.needsPicker, String.valueOf(ask));
        File neverCreated = new File(dst, "intent-only.txt");
        Clipboard.Interrupted intentOnly = new Clipboard.Interrupted(dst.getPath(), System.currentTimeMillis(),
                Collections.singletonList(neverCreated.getPath()), false);
        String intentReport = InterruptedPastes.report(Collections.singletonList(intentOnly), false);
        r.check("interrupted: recorded name never created is gone and remains absent",
                intentReport.contains("[gone]  " + neverCreated.getPath()) && !neverCreated.exists(), intentReport);
        // Vanished items are not a reason: the paste skips and counts them.
        r.rule("OK: an item that no longer exists is not a rule failure",
                PasteRules.check(state(item(new File(src, "ghost.txt"), 1, false)), folder(dst, roots)), null);
    }

    private static PasteTarget folder(File folder, List<File> roots) {
        return PasteTarget.ofFolder(folder, roots);
    }

    private static ClipItem item(File file) {
        return ClipItem.measure(file);
    }

    private static ClipItem item(File file, long size, boolean isDir) {
        return new ClipItem(file.getPath(), 0, file.getName(), size, isDir);
    }

    private static Clipboard.State state(ClipItem... items) {
        return new Clipboard.State(Clipboard.Mode.CUT, Arrays.asList(items), System.currentTimeMillis());
    }

    private static ClipItem[] many(File folder, int count, long size) {
        ClipItem[] items = new ClipItem[count];
        for (int i = 0; i < count; i++) items[i] = item(new File(folder, "f" + i + ".txt"), size, false);
        return items;
    }

    private static File mkdir(File folder) throws IOException {
        Files.createDirectories(folder.toPath());
        return folder;
    }

    private static File write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ---- device part ------------------------------------------------------------------------------------------

    /**
     * The full test on the phone. Blocking (waits for the paste): call it on a background thread. Works in
     * {@code Download/.filesplus-test-<time>} (a public path is needed: Android/ is protected), removed at the end.
     */
    @SuppressWarnings("deprecation")
    public static synchronized String runDevice(Context context) {
        Report r = new Report();
        File tmp = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                ".filesplus-test-" + System.currentTimeMillis());
        String savedLast = DestinationHistory.rawLast();
        Map<String, ?> savedClipboard = new HashMap<>(context.getSharedPreferences(
                "filesplus_clipboard", Context.MODE_PRIVATE).getAll());
        Clipboard.useStoreForTest("filesplus_clipboard_selftest");
        try {
            Clipboard.dropStore(context, "filesplus_clipboard_selftest");
            File base = mkdir(tmp).getCanonicalFile();
            String rules = runRules(mkdir(new File(base, "rules")));
            r.text.append("[Rules without Android]\n").append(rules).append("\n\n");
            if (!rules.endsWith("ALL PASS")) r.failed++;
            r.text.append("[This device]\n");
            deviceRules(r, context, base);
            clipboardStore(r, context, base);
            interruptedPastes(r, context, base);
            reservationListener(r, context, base);
            realPaste(r, context, base);
        } catch (Throwable t) {
            r.check("unexpected exception", false, t.toString());
        } finally {
            try {
                DestinationHistory.restoreRawLast(savedLast);
            } finally {
                Clipboard.useStoreForTest(null);
                Clipboard.dropStore(context, "filesplus_clipboard_selftest");
                cleanUp(context, tmp);
            }
        }
        r.check("self test: real last folder unchanged", Objects.equals(savedLast, DestinationHistory.rawLast()), null);
        r.check("self test: real clipboard unchanged", savedClipboard.equals(context.getSharedPreferences(
                "filesplus_clipboard", Context.MODE_PRIVATE).getAll()), null);
        return r.finish();
    }

    /** The device's real volumes and the folders that are protected on it. */
    private static void deviceRules(Report r, Context context, File base) throws IOException {
        List<File> roots = LocalFolders.roots(context);
        File primary = roots.get(0);
        File src = mkdir(new File(base, "dev/src"));
        File child = mkdir(new File(src, "child"));
        File dst = mkdir(new File(base, "dev/dst"));
        File file = write(new File(src, "a.txt"), "A");
        r.rule("device: OK into a normal folder", PasteRules.check(state(item(file)), PasteTarget.ofFolder(dst)), null);
        r.rule("device: SAME_FOLDER", PasteRules.check(state(item(file)), PasteTarget.ofFolder(src)), PasteRules.Reason.SAME_FOLDER);
        r.rule("device: INTO_SELF", PasteRules.check(state(item(src)), PasteTarget.ofFolder(child)), PasteRules.Reason.INTO_SELF);
        r.rule("device: PROTECTED Android/data/<package>",
                PasteRules.check(state(item(file)),
                        PasteTarget.ofFolder(new File(primary, "Android/data/" + context.getPackageName()))),
                PasteRules.Reason.PROTECTED);
        r.rule("device: PROTECTED .FilesPlus",
                PasteRules.check(state(item(file)), PasteTarget.ofFolder(new File(primary, ".FilesPlus"))),
                PasteRules.Reason.PROTECTED);
        r.rule("device: NOT_WRITABLE missing folder",
                PasteRules.check(state(item(file)), PasteTarget.ofFolder(new File(base, "dev/nope"))),
                PasteRules.Reason.NOT_WRITABLE);
        r.rule("device: ALL_TAB off (default setting)",
                PasteRules.check(state(item(file)), PasteTarget.ofAllTab(Category.IMAGES, "off")),
                PasteRules.Reason.ALL_TAB_DISABLED);
        File pictures = PasteTarget.defaultFolder(Category.IMAGES);
        boolean picturesUsable = pictures != null
                && (pictures.isDirectory() ? pictures.canWrite() : pictures.getParentFile().canWrite());
        r.rule("device: ALL_TAB default folder (Pictures)",
                PasteRules.check(state(item(file)), PasteTarget.ofAllTab(Category.IMAGES, "default")),
                picturesUsable ? null : PasteRules.Reason.NOT_WRITABLE);
        if (roots.size() > 1) {
            File sd = roots.get(1);
            PasteRules.Result got = PasteRules.check(state(item(file)), PasteTarget.ofFolder(sd));
            if (!got.ok && got.reason == PasteRules.Reason.NOT_WRITABLE) {
                r.skip("device: CROSS_VOLUME to the SD card", "the SD card root is not writable: " + sd);
            } else {
                r.rule("device: CROSS_VOLUME to the SD card (" + sd + ")", got, PasteRules.Reason.CROSS_VOLUME);
            }
        } else {
            r.skip("device: CROSS_VOLUME to the SD card", "no SD card or second volume");
        }
    }

    /** Store, add and expiry after 24 hours, using only the dedicated test store. */
    private static void clipboardStore(Report r, Context context, File base) throws IOException {
        File f1 = write(new File(base, "clip1.txt"), "1");
        File f2 = write(new File(base, "clip2.txt"), "2");
        Clipboard.cut(context, Arrays.asList(item(f1), item(f2)));
        Clipboard.State got = Clipboard.get(context);
        r.check("clipboard: cut stores 2 items in order", got.items.size() == 2
                && got.items.get(0).path().equals(f1.getPath()) && got.items.get(1).name().equals("clip2.txt")
                && got.mode == Clipboard.Mode.CUT && got.createdAt > 0, got.items.size() + " item(s)");
        Clipboard.add(context, Arrays.asList(item(f2), new ClipItem(new File(base, "clip3").getPath(), 7, "clip3", 5, true)));
        got = Clipboard.get(context);
        r.check("clipboard: add keeps one copy of equal paths", got.items.size() == 3
                && got.items.get(2).isDir() && got.items.get(2).mediaId() == 7, got.items.size() + " item(s)");
        Clipboard.cut(context, Collections.singletonList(item(f1)));
        r.check("clipboard: cut replaces the contents", Clipboard.get(context).items.size() == 1, null);
        Clipboard.State old = new Clipboard.State(Clipboard.Mode.CUT, Collections.singletonList(item(f1)),
                System.currentTimeMillis() - 25L * 60 * 60 * 1000);
        Clipboard.restore(context, old);
        r.check("clipboard: contents older than 24 hours are dropped", Clipboard.get(context).isEmpty(), null);
        r.check("clipboard: ... and stay dropped", Clipboard.get(context).isEmpty(), null);
        Clipboard.cut(context, Collections.singletonList(item(f1)));
        Clipboard.clear(context);
        r.check("clipboard: clear", Clipboard.get(context).isEmpty(), null);
    }

    /** One real paste: name clash, folder, vanished item, a second paste, a refused paste and a created folder. */
    private static void realPaste(Report r, Context context, File base) throws Exception {
        File src = mkdir(new File(base, "paste/src"));
        File dst = mkdir(new File(base, "paste/dst"));
        File a = write(new File(src, "a.txt"), "new A");
        File b = write(new File(src, "b.txt"), "B");
        File sub = mkdir(new File(src, "sub"));
        write(new File(sub, "inner.txt"), "inner");
        write(new File(dst, "a.txt"), "old A");
        File ghost = new File(src, "ghost.txt"); // never created: a vanished item
        Clipboard.State state = new Clipboard.State(Clipboard.Mode.CUT,
                Arrays.asList(item(a), item(b), item(sub), item(ghost, 3, false)), System.currentTimeMillis());

        MoveQueue.PasteResult first = pasteAndWait(context, state, dst, false);
        r.check("paste: no rule refused it", first != null && first.refused == null, String.valueOf(first == null ? null : first.refused));
        r.check("paste: file, file and folder moved, ghost counted as missing",
                first != null && first.moved == 3 && first.missing == 1 && first.failed == 0 && first.sameFolder == 0,
                first == null ? "no result" : "moved " + first.moved + ", missing " + first.missing + ", failed " + first.failed);
        r.check("paste: the existing file is not overwritten", "old A".equals(read(new File(dst, "a.txt"))), read(new File(dst, "a.txt")));
        r.check("paste: the clash kept both (a (1).txt)", "new A".equals(read(new File(dst, "a (1).txt"))), read(new File(dst, "a (1).txt")));
        r.check("paste: b.txt arrived", "B".equals(read(new File(dst, "b.txt"))), null);
        r.check("paste: the folder arrived with its content", "inner".equals(read(new File(dst, "sub/inner.txt"))), null);
        r.check("paste: the sources are gone",
                !a.exists() && !b.exists() && !sub.exists(), null);

        // The same snapshot again (a double paste or a paste after a killed process): nothing is done twice.
        MoveQueue.PasteResult second = pasteAndWait(context, state, dst, false);
        r.check("paste again: everything is missing, nothing is moved twice",
                second != null && second.refused == null && second.moved == 0 && second.missing == 4 && second.failed == 0,
                second == null ? "no result" : "moved " + second.moved + ", missing " + second.missing);
        r.check("paste again: no new copy appeared", !new File(dst, "b (1).txt").exists(), null);

        // A refused paste changes nothing.
        File keep = write(new File(src, "keep.txt"), "keep");
        File protectedDir = new File(LocalFolders.roots(context).get(0), "Android/data/" + context.getPackageName());
        MoveQueue.PasteResult refused = pasteAndWait(context, new Clipboard.State(Clipboard.Mode.CUT,
                Collections.singletonList(item(keep)), System.currentTimeMillis()), protectedDir, false);
        r.check("paste: into Android/ is refused as PROTECTED and nothing moved",
                refused != null && refused.refused == PasteRules.Reason.PROTECTED && keep.exists(),
                refused == null ? "no result" : String.valueOf(refused.refused));

        // A missing destination folder is created when asked for.
        File created = new File(base, "paste/created");
        MoveQueue.PasteResult make = pasteAndWait(context, new Clipboard.State(Clipboard.Mode.CUT,
                Collections.singletonList(item(keep)), System.currentTimeMillis()), created, true);
        r.check("paste: createIfMissing makes the folder and moves into it",
                make != null && make.refused == null && make.moved == 1 && "keep".equals(read(new File(created, "keep.txt")))
                        && !keep.exists(), make == null ? "no result" : "moved " + make.moved + ", refused " + make.refused);
    }

    private static void interruptedPastes(Report r, Context context, File base) throws Exception {
        File dst = mkdir(new File(base, "interrupted"));
        File placeholder = write(new File(dst, "reserved.txt"), "");
        File emptyFolder = mkdir(new File(dst, "reserved-folder"));
        List<String> names = Arrays.asList(placeholder.getPath(), emptyFolder.getPath());
        Clipboard.injectDeadPasteForTest(context, dst.getPath(), names);
        List<Clipboard.Interrupted> records = Clipboard.interrupted(context);
        r.check("interrupted: dead process keeps reserved names", records.size() == 1
                && records.get(0).reserved.equals(names) && !records.get(0).toasted, null);
        long token = Clipboard.beginPaste(context, dst.getPath());
        r.check("interrupted: new paste does not overwrite old record",
                Clipboard.interrupted(context).size() == 1, null);
        Clipboard.reserve(context, token, placeholder.getPath());
        Clipboard.unreserve(context, token, placeholder.getPath());
        Clipboard.endPaste(context, token);
        Clipboard.markInterruptedToasted(context);
        records = Clipboard.interrupted(context);
        r.check("interrupted: toast flag persists without removing record", records.size() == 1
                && records.get(0).toasted && records.get(0).reserved.equals(names), null);
        String report = InterruptedPastes.report(records, false);
        r.check("interrupted: report marks zero-byte placeholder", report.contains(
                "[0 bytes: maybe left over]  " + placeholder.getPath()), report);
        Clipboard.dismissInterrupted(context);
        r.check("interrupted: dismiss removes records only", Clipboard.interrupted(context).isEmpty()
                && placeholder.exists() && emptyFolder.isDirectory(), null);
    }

    private static void reservationListener(Report r, Context context, File base) throws Exception {
        File src = mkdir(new File(base, "listener/src"));
        File dst = mkdir(new File(base, "listener/dst"));
        File file = write(new File(src, "a.txt"), "listener");
        File clash = write(new File(src, "b.txt"), "new");
        File existing = write(new File(dst, "b.txt"), "old");
        File failed = write(new File(src, "c.txt"), "keep");
        File missing = new File(base, "listener/missing/dst");
        CountDownLatch done = new CountDownLatch(1);
        List<String> events = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        TabMove.fileExecutor().execute(() -> {
            long token = Clipboard.beginPaste(context, dst.getPath());
            try {
                TabMove.ReservationListener listener = new TabMove.ReservationListener() {
                    @Override public void reserved(File target) {
                        Clipboard.reserve(context, token, target.getPath());
                        String stored = context.getSharedPreferences("filesplus_clipboard_selftest",
                                Context.MODE_PRIVATE).getString("paste", "");
                        // Check after the synchronous journal commit, while no placeholder should exist yet.
                        events.add("reserved:" + target.getName() + ":" + target.exists() + ":"
                                + stored.contains(JSONObject.quote(target.getPath())));
                    }
                    @Override public void done(File target) {
                        Clipboard.unreserve(context, token, target.getPath());
                        events.add("done:" + target.getName() + ":" + read(target));
                    }
                };
                TabMove.move(context, Collections.singletonList(file.getPath()), dst, listener);
                r.check("reservation listener: persisted before placeholder, then done after rename",
                        events.equals(Arrays.asList("reserved:a.txt:false:true", "done:a.txt:listener"))
                        && !file.exists(), events.toString());
                events.clear();
                TabMove.move(context, Collections.singletonList(clash.getPath()), dst, listener);
                r.check("reservation listener: taken name done before recording next candidate",
                        events.equals(Arrays.asList("reserved:b.txt:true:true", "done:b.txt:old",
                        "reserved:b (1).txt:false:true", "done:b (1).txt:new"))
                        && !clash.exists() && "old".equals(read(existing)), events.toString());
                events.clear();
                TabMove.Result result = TabMove.move(context, Collections.singletonList(failed.getPath()), missing, listener);
                r.check("reservation listener: reservation exception still calls done",
                        events.equals(Arrays.asList("reserved:c.txt:false:true", "done:c.txt:null"))
                        && result.failed == 1 && "keep".equals(read(failed)), events.toString());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                Clipboard.endPaste(context, token);
                done.countDown();
            }
        });
        awaitCompletion(done);
        r.check("reservation listener: completed without exception", error.get() == null, String.valueOf(error.get()));
    }

    private static MoveQueue.PasteResult pasteAndWait(Context context, Clipboard.State state, File dest, boolean create)
            throws InterruptedException {
        AtomicReference<MoveQueue.PasteResult> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        MoveQueue.paste(context, state, dest, create, result -> {
            out.set(result);
            done.countDown();
        });
        // Do not restore the real store while an accepted paste is still queued on the file executor.
        awaitCompletion(done);
        return out.get();
    }

    /** Drain accepted file work before resetting the test store, even if this worker is interrupted. */
    private static void awaitCompletion(CountDownLatch done) {
        boolean interrupted = false;
        for (;;) {
            try {
                done.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** Removes the temporary tree (only what this test made, no links followed) and tells the media scanner. */
    private static void cleanUp(Context context, File tmp) {
        List<String> paths = new ArrayList<>();
        try {
            if (tmp.getName().startsWith(".filesplus-test-")) delete(tmp, paths);
            if (!paths.isEmpty()) MediaScannerConnection.scanFile(context, paths.toArray(new String[0]), null, null);
        } catch (Throwable ignored) {
            // The folder is hidden and small; a leftover is harmless.
        }
    }

    private static void delete(File file, List<String> paths) {
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child, paths);
        }
        paths.add(file.getPath());
        file.delete();
    }
}
