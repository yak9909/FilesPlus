package app.moddedfiles.extension;

/**
 * Static entry points called from patched smali.
 * Every hook must be safe: catch Throwable and fall back to the original behavior.
 */
@SuppressWarnings("unused")
public final class Hooks {
    private Hooks() {}
}
