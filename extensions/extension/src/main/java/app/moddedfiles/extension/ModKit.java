package app.moddedfiles.extension;

import android.content.Context;

/** Global entry of the extension. {@link #init} is called once from Application.onCreate. */
public final class ModKit {
    public static final String TAG = "ModKit";
    private static volatile Context appContext;

    private ModKit() {}

    public static void init(Context context) {
        try {
            if (appContext != null) return;
            appContext = context.getApplicationContext();
            DiagLog.i("init", "ModKit initialized");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "init failed", t);
        }
    }

    /** Application context, or null before {@link #init}. */
    public static Context context() {
        return appContext;
    }
}
