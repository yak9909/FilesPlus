package app.moddedfiles.extension.settings;

import android.content.Context;
import android.content.Intent;
import app.moddedfiles.extension.DiagLog;

/** SDK-only entry points used by the settings bytecode patch. */
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

    public static String entryTitle(Context context) {
        try {
            return isJapanese(context) ? "Mod 設定" : "Mod settings";
        } catch (Throwable t) {
            DiagLog.e("settings", "Cannot create entry title", t);
            return "Mod settings";
        }
    }

    public static Intent settingsIntent(Context context) {
        try {
            // Explicit component stays valid when the host package is renamed.
            return new Intent(context, ModSettingsActivity.class);
        } catch (Throwable t) {
            DiagLog.e("settings", "Cannot create settings intent", t);
            return null;
        }
    }

    public static void launchIntent(Context context, Intent intent) {
        try {
            if (intent != null) context.startActivity(intent);
        } catch (Throwable t) {
            DiagLog.e("settings", "Cannot open settings", t);
        }
    }
}
