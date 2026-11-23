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
    public static final String SKIP_REWIND_TO_START = "skip.rewindToStart";
    public static final boolean DEFAULT_SKIP_REWIND_TO_START = true;

    public static final String TABS_SHOW_EMPTY_FOLDERS = "tabs.showEmptyFolders";
    public static final String TABS_SHOW_HIDDEN = "tabs.showHidden";
    public static final boolean DEFAULT_TABS_SHOW_EMPTY_FOLDERS = false;
    public static final boolean DEFAULT_TABS_SHOW_HIDDEN = false;

    public static final String PASTE_ALL_TAB = "paste.allTab";

    public static final String SWITCH_TO_MEDIA_CATEGORY = "switch.toMediaCategory";
    public static final String SWITCH_ALL_TAB_TARGET = "switch.allTabTarget";
    private static final String SWITCH_LAST_FOLDER = "switch.lastFolder";

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

    public static boolean skipRewindToStart() {
        return getBool(SKIP_REWIND_TO_START, DEFAULT_SKIP_REWIND_TO_START);
    }

    public static String pasteAllTab() {
        try {
            SharedPreferences p = prefs();
            return p == null ? "off" : p.getString(PASTE_ALL_TAB, "off");
        } catch (Throwable t) {
            return "off";
        }
    }

    public static void setPasteAllTab(String value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString(PASTE_ALL_TAB, value).apply();
    }

    public static String switchToMediaCategory() {
        return switchValue(SWITCH_TO_MEDIA_CATEGORY, "auto");
    }

    public static String switchAllTabTarget() {
        return switchValue(SWITCH_ALL_TAB_TARGET, "default");
    }

    public static String switchLastFolder() {
        return switchValue(SWITCH_LAST_FOLDER, null);
    }

    public static void rememberSwitchFolder(String path) {
        if (!path.equals(switchLastFolder())) setSwitchChoice(SWITCH_LAST_FOLDER, path);
    }

    public static void setSwitchChoice(String key, String value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString(key, value).apply();
    }

    private static String switchValue(String key, String fallback) {
        try {
            SharedPreferences p = prefs();
            return p == null ? fallback : p.getString(key, fallback);
        } catch (Throwable t) {
            DiagLog.e("switch", "Cannot read " + key, t);
            return fallback;
        }
    }
}
