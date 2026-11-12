package app.filesplus.extension.browse;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * File categories of the destination browser. Each carries a MediaStore predicate (local index), an extension
 * table (SMB and plain directory listings, which have no index) and the app category names whose folder tabs
 * feed the known-folders sets (DESIGN 5.9).
 */
public enum Category {
    IMAGES("media_type = 1", ext("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng", "raw",
            "arw", "cr2", "nef", "svg"), "CATEGORY_IMAGE"),
    VIDEOS("media_type = 3", ext("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3g2", "wmv", "flv", "ts",
            "mpg", "mpeg", "ogv"), "CATEGORY_VIDEO"),
    AUDIO("media_type = 2", ext("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr", "wma", "mid",
            "midi", "mka"), "CATEGORY_AUDIO"),
    // Since API 30 the index types pdf/office/text/epub as MEDIA_TYPE_DOCUMENT (6); other document-like files are
    // plain files (media_type 0) and are told apart by MIME type. A constant: never built from user input.
    DOCUMENTS("(media_type = 6 OR (media_type = 0 AND (mime_type LIKE 'text/%' OR mime_type LIKE 'application/vnd.ms-%'"
            + " OR mime_type LIKE 'application/vnd.openxmlformats-officedocument.%'"
            + " OR mime_type LIKE 'application/vnd.oasis.opendocument.%'"
            + " OR mime_type IN ('application/pdf', 'application/msword', 'application/rtf', 'application/epub+zip'))))",
            ext("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "csv", "epub",
                    "md", "json", "xml", "html", "htm"), "CATEGORY_DOCUMENT", "CATEGORY_DOCUMENT_ONLY"),
    /** A shortcut to the Download folder, not a MediaStore category: it has no predicate and no extensions. */
    DOWNLOADS(null, ext(), "CATEGORY_DOWNLOAD");

    /** MediaStore WHERE clause for the "files" table, or null when this category is not index based. */
    public final String selection;
    private final Set<String> extensions;
    private final String[] appCategories;

    Category(String selection, Set<String> extensions, String... appCategories) {
        this.selection = selection;
        this.extensions = extensions;
        this.appCategories = appCategories;
    }

    /** True for categories that are listed from the MediaStore (everything but DOWNLOADS). */
    public boolean indexed() {
        return selection != null;
    }

    /** The category a file name belongs to by its extension, or null (also for directories' names without one). */
    public static Category ofName(String fileName) {
        String ext = extensionOf(fileName);
        if (ext == null) return null;
        for (Category category : values()) {
            if (category.extensions.contains(ext)) return category;
        }
        return null;
    }

    /** Whether the extension table says this file belongs here. DOWNLOADS never matches by name. */
    public boolean matchesName(String fileName) {
        String ext = extensionOf(fileName);
        return ext != null && extensions.contains(ext);
    }

    /** The app's category names (CATEGORY_IMAGE ...) whose known folders belong here. */
    String[] appCategories() {
        return appCategories;
    }

    private static String extensionOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> ext(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
