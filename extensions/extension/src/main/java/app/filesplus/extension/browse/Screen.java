package app.filesplus.extension.browse;

import app.filesplus.extension.ui.FilesLook;

import java.util.List;

/**
 * One screen of the destination browser (DESIGN 5.11.4). {@link #load} reads what the screen shows on a background
 * thread and keeps it in fields; {@link #layout} turns it into rows on the UI thread (the grid depends on the
 * window width, so it can run again after a rotation without reading anything).
 */
abstract class Screen {
    /** The list position the user had scrolled to when the screen was left (restored when it is shown again). */
    int firstPosition;
    int firstTop;

    abstract String title(BrowseActivity host);

    /**
     * Background thread. Reads what the screen shows and returns it without touching the screen's fields, so a
     * load that has become stale (the user moved on) can simply be dropped. A failure is shown as an error row.
     */
    abstract Object load(BrowseActivity host) throws Exception;

    /** UI thread: takes over the result of the load that is still current. */
    abstract void apply(BrowseActivity host, Object data);

    /** UI thread, after {@link #apply}. */
    abstract List<Row> layout(BrowseActivity host, int columns);

    // ---- shared names -----------------------------------------------------------------------------------------

    static String label(BrowseActivity host, Category category) {
        switch (category) {
            case IMAGES: return host.app("images_label", host.tr("画像", "Images"));
            case VIDEOS: return host.app("videos_label", host.tr("動画", "Videos"));
            case AUDIO: return host.app("audio_label", host.tr("音声", "Audio"));
            case DOCUMENTS: return host.app("documents_label", host.tr("ドキュメント", "Documents"));
            default: return host.app("downloads", host.tr("ダウンロード", "Downloads"));
        }
    }

    /** Name of the app's icon for a category (also the generic icon of a file of that kind). */
    static String icon(Category category) {
        if (category == null) return "gs_note_vd_theme_24";
        switch (category) {
            case IMAGES: return "gs_image_vd_theme_24";
            case VIDEOS: return "gs_movie_vd_theme_24";
            case AUDIO: return "quantum_gm_ic_audiotrack_vd_theme_24";
            case DOWNLOADS: return "quantum_gm_ic_get_app_vd_theme_24";
            default: return "gs_note_vd_theme_24";
        }
    }

    static String volumeLabel(BrowseActivity host, LocalSource.Volume volume) {
        if (volume.primary) {
            String name = host.app("internal_storage", host.tr("内部ストレージ", "Internal storage"));
            // The app's string is a sentence fragment ("internal storage").
            return name.isEmpty() ? name : name.substring(0, 1).toUpperCase() + name.substring(1);
        }
        if (volume.description != null && !volume.description.isEmpty()) return volume.description;
        return host.app("sd_card", "SD card");
    }

    /** The volume a location is on (the deepest root that contains it), or null. */
    static LocalSource.Volume volumeOf(List<LocalSource.Volume> volumes, Location location) {
        LocalSource.Volume best = null;
        for (LocalSource.Volume volume : volumes) {
            if (location.isWithin(volume.root)
                    && (best == null || volume.root.path.length() > best.root.path.length())) {
                best = volume;
            }
        }
        return best;
    }

    static String filesText(BrowseActivity host, int count) {
        String text = FilesLook.plural(host, "files", count);
        return text != null ? text : count + " files";
    }
}
