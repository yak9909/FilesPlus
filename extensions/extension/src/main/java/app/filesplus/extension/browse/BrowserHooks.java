package app.filesplus.extension.browse;

import android.view.Menu;
import android.view.View;
import app.filesplus.extension.DiagLog;
import app.filesplus.extension.clip.ClipHooks;
import app.filesplus.extension.ui.ScreenArgs;

/** Native browser entry points shared by independently enabled patches. */
public final class BrowserHooks {
    private BrowserHooks() {}

    public static void hookError(Throwable error) {
        DiagLog.e("browse", "Browser hook failed", error);
    }

    public static byte[] folderBytes(Object folder) {
        try { return ScreenArgs.serialize(folder); }
        catch (Throwable error) { hookError(error); return null; }
    }

    public static void attachStorage(View root, byte[] folder) {
        ViewSwitch.storageView(root, folder);
    }

    public static void attachMedia(View root, String category) {
        ViewSwitch.mediaView(root, category);
        try {
            if (ClipHooks.installed()) ClipHooks.attachMediaPaste(root, category);
        } catch (Throwable error) { hookError(error); }
    }

    public static void menu(View root, Menu menu) {
        try {
            if (ClipHooks.installed()) ClipHooks.pasteMenu(root, menu);
        } catch (Throwable error) { hookError(error); }
        ViewSwitch.menu(root, menu);
    }
}
