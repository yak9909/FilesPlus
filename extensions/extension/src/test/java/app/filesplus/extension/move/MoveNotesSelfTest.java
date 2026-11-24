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
                        + "\nThe original file was kept\n", false, result);
        expect("all Japanese notes",
                "SD カードで使えない文字（\" * : < > ? \\ |）や末尾の点・空白を含む名前は移動できません"
                        + "\n移動先の空き容量が足りません"
                        + "\n書き込みの検証に失敗したため残りを止めました（SD カードを確かめてください）"
                        + "\n元のファイルは残しました\n", true, result);

        TabMove.Result retained = new TabMove.Result();
        retained.moved = 1;
        retained.sourceKept = true;
        expect("source kept after success", "The original file was kept\n", false, retained);
        System.out.println("Move result notes: ALL PASS (" + checks + " checks)");
    }
}
