package app.filesplus.extension;

import android.app.Application;
import android.content.Context;

import app.filesplus.extension.clip.Clipboard;
import app.filesplus.extension.foldertabs.TabMove;

/** Global entry of the extension. {@link #init} is called once from Application.onCreate. */
public final class ModKit {
    public static final String TAG = "ModKit";
    private static volatile Context appContext;

    private ModKit() {}

    public static void init(Context context) {
        try {
            if (appContext != null) return;
            appContext = context.getApplicationContext();
            CrashLog.install(appContext);
            DiagLog.i("init", "ModKit initialized");
            // A secondary app process must not treat the main process's active parts as stale.
            if (Application.getProcessName().equals(appContext.getApplicationInfo().processName)) {
                TabMove.fileExecutor().execute(() -> Clipboard.recoverInterrupted(appContext));
            }
            if (CrashLog.exists()) DiagLog.i("init", "A crash was recorded; see Diagnostics -> Last crash");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "init failed", t);
        }
    }

    /** Application context, or null before {@link #init}. */
    public static Context context() {
        return appContext;
    }
}
