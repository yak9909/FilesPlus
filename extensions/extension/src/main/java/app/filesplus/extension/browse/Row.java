package app.filesplus.extension.browse;

import java.util.List;

/** One line of a browser screen: a plain value that BrowseActivity turns into the app's own list rows. */
final class Row {
    enum Kind { HEADER, CAPSULE, FOLDER, FILE, GRID, MESSAGE }

    final Kind kind;
    CharSequence title;
    CharSequence subtitle;
    /** Name of an app drawable shown as the icon (and as the thumbnail placeholder), or null. */
    String icon;
    /** A file to show a thumbnail for (FOLDER rows use it for a bucket's newest file, FILE rows for the file). */
    Entry entry;
    /** GRID: the files of this line, in order. */
    List<Entry> cells;
    /** Null for rows that cannot be tapped (headers, files, grids, messages). */
    Runnable onClick;

    private Row(Kind kind) {
        this.kind = kind;
    }

    static Row header(CharSequence title) {
        Row row = new Row(Kind.HEADER);
        row.title = title;
        return row;
    }

    static Row capsule(String icon, CharSequence title, CharSequence subtitle, Runnable onClick) {
        Row row = new Row(Kind.CAPSULE);
        row.icon = icon;
        row.title = title;
        row.subtitle = subtitle;
        row.onClick = onClick;
        return row;
    }

    static Row folder(String icon, CharSequence title, CharSequence subtitle, Entry thumbnail, Runnable onClick) {
        Row row = new Row(Kind.FOLDER);
        row.icon = icon;
        row.title = title;
        row.subtitle = subtitle;
        row.entry = thumbnail;
        row.onClick = onClick;
        return row;
    }

    /** A file in a list: shown dimmed because nothing can be done with it in pick mode. */
    static Row file(Entry entry, String icon, CharSequence subtitle) {
        Row row = new Row(Kind.FILE);
        row.entry = entry;
        row.icon = icon;
        row.title = entry.name;
        row.subtitle = subtitle;
        return row;
    }

    static Row grid(List<Entry> cells) {
        Row row = new Row(Kind.GRID);
        row.cells = cells;
        return row;
    }

    static Row message(CharSequence text) {
        Row row = new Row(Kind.MESSAGE);
        row.title = text;
        return row;
    }
}
