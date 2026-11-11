package app.filesplus.extension.preview;

import java.util.List;

/**
 * Implemented (through the patch) by the app's list selection model, so the extension can hand it
 * entries without knowing any app type. The entries are the app's own file protos taken from
 * {@link SelectionStore}; the implementation merges them into the current selection.
 */
public interface SelectionTarget {
    void modKitAddSelected(List<Object> entries);
}
