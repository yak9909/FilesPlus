package app.filesplus.extension.move;

import app.filesplus.extension.foldertabs.TabMove;
import app.filesplus.extension.ops.VerifiedCopy;

public final class MoveNotesSelfTest {
    private static final String SUMMARY = "Could not paste 1 item to /storage/FE1F-1CED/Download";
    private static int checks;

    private static void expect(String label, String expected, boolean japanese, TabMove.Result result) {
        String text = MoveQueue.withMoveNotes(SUMMARY, japanese, result);
        if (!(expected + SUMMARY).equals(text)) throw new AssertionError(label + ": " + text);
        checks++;
    }

    public static void main(String[] args) {
        TabMove.Result result = new TabMove.Result();
        expect("summary unchanged without notes", "", false, result);
        expect("summary unchanged without move details", "", false, null);
        result.reasons.add(VerifiedCopy.Reason.IO);
        result.reasons.add(VerifiedCopy.Reason.SIZE_MISMATCH);
        result.reasons.add(VerifiedCopy.Reason.SOURCE_CHANGED);
        result.reasons.add(VerifiedCopy.Reason.HASH_MISMATCH);
        expect("count-only failure reasons", "", true, result);

        result.reasons.add(VerifiedCopy.Reason.NAME_INVALID);
        result.reasons.add(VerifiedCopy.Reason.NO_SPACE);
        result.hashStopped = true;
        result.sourceKept = true;
        expect("all English notes",
                "Names with characters the SD card cannot store (\" * : < > ? \\ |) or a trailing dot/space cannot be moved"
                        + "\nNot enough free space at the destination"
                        + "\nStopped: written data failed verification (check the SD card)"
                        + "\nThe original was kept\n", false, result);
        expect("all Japanese notes",
                "SD カードで使えない文字（\" * : < > ? \\ |）や末尾の点・空白を含む名前は移動できません"
                        + "\n移動先の空き容量が足りません"
                        + "\n書き込みの検証に失敗したため残りを止めました（SD カードを確かめてください）"
                        + "\n元のファイル・フォルダは残しました\n", true, result);

        TabMove.Result refusedLink = new TabMove.Result();
        refusedLink.reasons.add(VerifiedCopy.Reason.TOP_LEVEL_LINK);
        expect("top-level link note in English", "Links cannot be moved\n", false, refusedLink);
        expect("top-level link note in Japanese", "リンクは移動できません\n", true, refusedLink);

        TabMove.Result refusedFolder = new TabMove.Result();
        refusedFolder.reasons.add(VerifiedCopy.Reason.LINK);
        refusedFolder.reasons.add(VerifiedCopy.Reason.TOO_LARGE);
        expect("folder refusal notes in English",
                "Folders that contain links or other storage cannot be moved\n"
                        + "The folder has too many items or is nested too deeply to move\n",
                false, refusedFolder);
        expect("folder refusal notes in Japanese",
                "リンクや別のストレージを含むフォルダは移動できません\n"
                        + "フォルダの項目が多すぎるか深すぎるため移動できません\n",
                true, refusedFolder);

        TabMove.Result retained = new TabMove.Result();
        retained.moved = 1;
        retained.sourceKept = true;
        expect("source kept after success", "The original was kept\n", false, retained);
        expect("source kept after success in Japanese", "元のファイル・フォルダは残しました\n", true, retained);
        String path = "/storage/emulated/0/Download";
        if (!("Copied 1 item(s) to " + path).equals(MoveQueue.copySummary(1, path, false)))
            throw new AssertionError("English copy summary");
        checks++;
        if (!("2 件を " + path + " にコピーしました").equals(MoveQueue.copySummary(2, path, true)))
            throw new AssertionError("Japanese copy summary");
        checks++;

        System.out.println("Move result notes: ALL PASS (" + checks + " checks)");
    }
}
