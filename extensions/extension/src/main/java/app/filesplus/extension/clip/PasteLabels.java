package app.filesplus.extension.clip;

final class PasteLabels {
    private PasteLabels() {}

    static String here(boolean japanese, int count) {
        return japanese ? "ここに貼り付け（" + count + " 件）" : "Paste here (" + count + ")";
    }

    static String menu(boolean japanese, String paste, int count) {
        return paste + (japanese ? "（" + count + " 件）" : " (" + count + ")");
    }
}
