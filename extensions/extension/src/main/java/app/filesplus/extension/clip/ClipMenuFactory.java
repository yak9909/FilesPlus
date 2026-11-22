package app.filesplus.extension.clip;

/** Implemented by the patch using the app's own menu-row constructor. */
public interface ClipMenuFactory {
    Object clipMenuRow(String label, Runnable action);
}
