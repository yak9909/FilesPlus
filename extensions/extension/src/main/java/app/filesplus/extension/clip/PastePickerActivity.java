package app.filesplus.extension.clip;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import app.filesplus.extension.ui.OriginalScreens;

/** Transparent relay for the native move picker. A recreated process cannot replay an old paste. */
public final class PastePickerActivity extends Activity {
    static final String TOKEN = "app.filesplus.extension.clip.PASTE_TOKEN";
    private static final int PICK_FOLDER = 1;
    private long token;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            token = getIntent().getLongExtra(TOKEN, 0);
            if (!PasteUi.waitingForPicker(token)
                    || (savedInstanceState == null && !OriginalScreens.pickFolder(this, PICK_FOLDER))) {
                PasteUi.pickerCancelled(token);
                finish();
            }
        } catch (Throwable t) {
            ClipHooks.hookError(t);
            PasteUi.pickerCancelled(token);
            finish();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER) return;
        try {
            if (resultCode == RESULT_OK && PasteUi.waitingForPicker(token)) {
                String path = OriginalScreens.readPicked(data, true);
                if (path == null) {
                    Toast.makeText(this, PasteUi.japanese(this) ? "貼り付け先を読み取れませんでした"
                            : "Could not read the paste destination", Toast.LENGTH_SHORT).show();
                }
                PasteUi.pickerResult(this, token, path);
            }
        } catch (Throwable t) {
            ClipHooks.hookError(t);
        } finally {
            PasteUi.pickerCancelled(token);
            finish();
        }
    }

    @Override protected void onDestroy() {
        try {
            if (!isChangingConfigurations()) PasteUi.pickerCancelled(token);
        } catch (Throwable t) { ClipHooks.hookError(t); }
        finally { super.onDestroy(); }
    }
}
