package app.filesplus.extension;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records the last uncaught exception to a file so it can be shown in "Diagnostics" after the app restarts
 * (the user does not use adb). Chains to the previous handler, so the app still crashes as before.
 */
public final class CrashLog {
    private static final String FILE = "filesplus-last-crash.txt";
    private static final int MAX_CHARS = 60_000;
    private static volatile File file;

    private CrashLog() {}

    static void install(Context app) {
        try {
            file = new File(app.getFilesDir(), FILE);
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    record(thread, error);
                } catch (Throwable ignored) {
                    // Never block the original crash handling.
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            });
        } catch (Throwable t) {
            Log.e(ModKit.TAG, "CrashLog install failed", t);
        }
    }

    private static synchronized void record(Thread thread, Throwable error) throws Exception {
        File target = file;
        if (target == null) return;
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        // Not Log.getStackTraceString: it returns "" when the cause chain has an UnknownHostException.
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String text = time + " thread=" + thread.getName() + "\n" + trace;
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS) + "\n…(truncated)";
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    /** True when a crash record exists (cheap; no read). */
    public static boolean exists() {
        File target = file;
        return target != null && target.isFile();
    }

    /** The last recorded crash, or null when none. */
    public static String last() {
        try {
            File target = file;
            if (target == null || !target.isFile()) return null;
            return new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            DiagLog.e("crash", "Cannot read the crash record", t);
            return null;
        }
    }

    public static void clear() {
        File target = file;
        if (target != null && !target.delete() && target.exists()) DiagLog.i("crash", "Cannot delete the crash record");
    }
}
