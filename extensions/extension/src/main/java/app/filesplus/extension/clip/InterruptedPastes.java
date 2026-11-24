package app.filesplus.extension.clip;

import android.content.Context;
import android.os.Environment;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.browse.LocalFolders;
import app.filesplus.extension.ops.Fs;
import app.filesplus.extension.ops.LocalFs;
import app.filesplus.extension.ops.TemporaryMoveCleanup;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Startup cleanup and diagnostics for interrupted pastes and moves. */
public final class InterruptedPastes {
    private InterruptedPastes() {}

    /** Removes temporary entries at exact recorded paths in the original, mounted destination folder. */
    static List<String> cleanParts(Context context, Clipboard.Interrupted record) {
        String destination = record.destination;
        File folder = new File(destination);
        List<String> paths = new ArrayList<>(record.parts);
        paths.addAll(record.partFolders);
        List<String> remaining = new ArrayList<>();
        try {
            if (!folder.isAbsolute() || !folder.equals(folder.getCanonicalFile())) return paths;
            boolean mounted = false;
            for (File root : LocalFolders.roots(context)) {
                if (!folder.toPath().startsWith(root.toPath())) continue;
                String state = Environment.getExternalStorageState(root);
                if (Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                    mounted = true;
                    break;
                }
            }
            if (!mounted) return paths;

            LocalFs fs = new LocalFs();
            Fs.Stat destinationStat = fs.lstat(destination);
            if (destinationStat == null) return Collections.emptyList();
            if (destinationStat.type != Fs.Type.DIRECTORY) return paths;
            clean(fs, destination, destinationStat, record.parts, false, remaining);
            clean(fs, destination, destinationStat, record.partFolders, true, remaining);
        } catch (Throwable t) {
            DiagLog.e("clip", "Cannot check interrupted move destination: " + folder, t);
            return paths;
        }
        return remaining;
    }

    private static void clean(Fs fs, String destination, Fs.Stat parent, List<String> paths, boolean directory,
                              List<String> remaining) {
        for (String path : paths) {
            try {
                TemporaryMoveCleanup.clean(fs, destination, parent, path, directory, message -> DiagLog.i("clip", message));
            } catch (Throwable t) {
                remaining.add(path);
                DiagLog.e("clip", "Cannot remove interrupted move temporary entry: " + path, t);
            }
        }
    }

    /** One line per paste, without file access: the folder and the first reserved names. */
    public static String summary(List<Clipboard.Interrupted> list, boolean ja) {
        StringBuilder sb = new StringBuilder();
        for (Clipboard.Interrupted record : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(record.destination);
            int shown = Math.min(3, record.reserved.size());
            for (int i = 0; i < shown; i++) sb.append("\n  ").append(new File(record.reserved.get(i)).getName());
            if (record.reserved.size() > shown) {
                sb.append("\n  … +").append(record.reserved.size() - shown);
            }
        }
        sb.append(ja ? "\n（タップで詳しく）" : "\n(tap for details)");
        return sb.toString();
    }

    /** The full report: every reserved name with what is on disk now. Does file I/O: call it off the UI thread. */
    public static String report(List<Clipboard.Interrupted> list, boolean ja) {
        StringBuilder sb = new StringBuilder();
        sb.append(ja ? "貼り付けの途中でアプリが止まった記録です。名前を確保した直後（移動の直前）に止まると、"
                + "その名前に 0 バイトのファイルか空のフォルダが残ります。記録された一時ファイル・フォルダは起動時に片付けます。"
                + "不要なら、ファイルアプリで確かめて自分で削除してください。"
                : "Pastes that were cut short because the app stopped. If it stopped right after a name was reserved "
                + "(just before the move), an empty 0-byte file or an empty folder can be left at that name. "
                + "Recorded temporary files and folders are cleaned up at startup. Check any reserved names in a file manager.");
        for (Clipboard.Interrupted record : list) {
            sb.append("\n\n").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(record.at)))
                    .append("  ").append(record.destination);
            if (record.reserved.isEmpty()) {
                sb.append(ja ? "\n  確保済みの名前はありません（残りはないはずです）" : "\n  no reserved name (nothing should be left)");
            }
            for (String name : record.reserved) sb.append("\n  ").append(state(name, ja)).append("  ").append(name);
            for (String name : record.parts) sb.append("\n  ").append(ja ? "[一時ファイル]" : "[temporary file]")
                    .append("  ").append(name);
            for (String name : record.partFolders) sb.append(ja ? "\n  [一時フォルダ]" : "\n  [temporary folder]")
                    .append("  ").append(name);
        }
        return sb.toString();
    }

    /** What is at a reserved name now. */
    static String state(String path, boolean ja) {
        File file = new File(path);
        try {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return ja ? "[なし]" : "[gone]";
            if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                String[] children = file.list();
                return children != null && children.length == 0 ? (ja ? "[空のフォルダ: 残りかも]" : "[empty folder: maybe left over]")
                        : (ja ? "[中身のあるフォルダ]" : "[folder with content]");
            }
            return file.length() == 0 ? (ja ? "[0 バイト: 残りかも]" : "[0 bytes: maybe left over]")
                    : (ja ? "[ファイルあり]" : "[file present]");
        } catch (RuntimeException e) {
            return "[?]";
        }
    }
}
