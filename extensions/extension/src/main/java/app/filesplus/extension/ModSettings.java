package app.filesplus.extension;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed access to ModKit settings (SharedPreferences "modkit_prefs"). Add new keys here. */
public final class ModSettings {
    public static final String PREFS = "modkit_prefs";

    public static final String MEDIA_TRY_EXCEED_CAPABILITIES = "media.tryExceedCapabilities";
    public static final boolean DEFAULT_MEDIA_TRY_EXCEED_CAPABILITIES = true;

    public static final String SKIP_DOUBLE_TAP_SEC = "skip.doubleTapSec";
    public static final String SKIP_BUTTON_SEC = "skip.buttonSec";
    public static final int DEFAULT_SKIP_SEC = 10;
    public static final int MIN_SKIP_SEC = 1;
    public static final int MAX_SKIP_SEC = 600;

    private ModSettings() {}

    public static SharedPreferences prefs() {
        Context c = ModKit.context();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getInt(String key, int def) {
        try {
            SharedPreferences p = prefs();
            return p == null ? def : p.getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static void putInt(String key, int value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(key, value).apply();
    }

    public static boolean getBool(String key, boolean def) {
        try {
            SharedPreferences p = prefs();
            return p == null ? def : p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static void putBool(String key, boolean value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean(key, value).apply();
    }

    private static int clampSkip(int v) {
        return Math.max(MIN_SKIP_SEC, Math.min(MAX_SKIP_SEC, v));
    }

    public static int skipDoubleTapSec() {
        return clampSkip(getInt(SKIP_DOUBLE_TAP_SEC, DEFAULT_SKIP_SEC));
    }

    public static int skipButtonSec() {
        return clampSkip(getInt(SKIP_BUTTON_SEC, DEFAULT_SKIP_SEC));
    }
}
