package app.filesplus.extension.settings;

import android.content.Context;
import app.filesplus.extension.DiagLog;

/** SDK-only helpers for the settings pages (the app's own settings screen opens them; see SettingsPages). */
public final class SettingsHooks {
    private SettingsHooks() {}

    public static boolean isJapanese(Context context) {
        try {
            return "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        } catch (Throwable t) {
            DiagLog.e("settings", "Cannot read locale", t);
            return false;
        }
    }
}
