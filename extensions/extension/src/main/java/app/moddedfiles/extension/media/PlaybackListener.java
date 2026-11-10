package app.moddedfiles.extension.media;

/** The patch adds the bundled Player.Listener interface and its callbacks to this class. */
public final class PlaybackListener {
    public PlaybackListener() {}

    static void attach(Object player) {
        throw new IllegalStateException("Media listener bridge was not patched");
    }

    static void format(Object format) {
        throw new IllegalStateException("Media format bridge was not patched");
    }
}
