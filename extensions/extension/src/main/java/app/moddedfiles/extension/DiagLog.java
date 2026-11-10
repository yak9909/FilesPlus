package app.moddedfiles.extension;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory ring buffer of diagnostic events, shown in "Mod settings -> Diagnostics"
 * (the user does not use adb). Also mirrored to logcat.
 */
public final class DiagLog {
    private static final int MAX = 300;
    private static final ArrayDeque<String> buf = new ArrayDeque<>();

    private DiagLog() {}

    public static void i(String area, String msg) {
        add("I", area, msg, null);
    }

    public static void e(String area, String msg, Throwable t) {
        add("E", area, msg, t);
    }

    private static synchronized void add(String level, String area, String msg, Throwable t) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder(time).append(' ').append(level).append(" [").append(area).append("] ").append(msg);
        if (t != null) sb.append(" : ").append(Log.getStackTraceString(t));
        if (buf.size() >= MAX) buf.removeFirst();
        buf.addLast(sb.toString());
        if ("E".equals(level)) Log.e(ModKit.TAG, sb.toString()); else Log.i(ModKit.TAG, sb.toString());
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(buf);
    }

    public static synchronized void clear() {
        buf.clear();
    }
}
