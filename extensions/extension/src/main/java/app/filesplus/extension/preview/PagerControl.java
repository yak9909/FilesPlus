package app.filesplus.extension.preview;

/**
 * Implemented by the app's preview screen controller (added by the "Move queue in preview" patch).
 * Lets the extension change the preview pager without knowing any app type. All calls are on the main thread.
 */
public interface PagerControl {
    /** True when the pager holds exactly one entry (removing it would leave nothing to show). */
    boolean modKitIsLastEntry();

    /**
     * Removes the current entry from the pager and shows the next one (the app's own "remove and move to next").
     * Returns true when the list was NOT updated at once (the app reloads it from MediaStore, or nothing happened):
     * the caller must then call {@link #modKitReload()} after the file has really left MediaStore.
     */
    boolean modKitRemoveCurrent();

    /** Reloads the pager's list from MediaStore, as the app does after its own move. */
    void modKitReload();
}
