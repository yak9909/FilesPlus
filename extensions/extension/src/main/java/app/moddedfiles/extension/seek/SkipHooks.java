package app.moddedfiles.extension.seek;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;

import java.util.Locale;

import app.moddedfiles.extension.DiagLog;
import app.moddedfiles.extension.ModSettings;

public final class SkipHooks {
    private SkipHooks() {}

    public static long doubleTapSeconds() {
        try {
            return ModSettings.skipDoubleTapSec();
        } catch (Throwable t) {
            DiagLog.e("seek", "Reading double tap duration", t);
            return 10L;
        }
    }

    public static long buttonSeconds() {
        try {
            return ModSettings.skipButtonSec();
        } catch (Throwable t) {
            DiagLog.e("seek", "Reading button duration", t);
            return 10L;
        }
    }

    public static void onFinishInflate(View root) {
        try {
            Context context = root.getContext();
            for (String name : new String[]{"rewind", "fast_forward"}) {
                int id = context.getResources().getIdentifier(name, "id", context.getPackageName());
                View button = id == 0 ? null : root.findViewById(id);
                if (button == null) continue;
                button.setOnLongClickListener(view -> {
                    try {
                        showMenu(view);
                        return true;
                    } catch (Throwable t) {
                        DiagLog.e("seek", "Opening skip menu", t);
                        return false;
                    }
                });
            }
        } catch (Throwable t) {
            DiagLog.e("seek", "Installing skip menu", t);
        }
    }

    private static boolean japanese(Context context) {
        return "ja".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private static void showMenu(View anchor) {
        Context context = anchor.getContext();
        boolean ja = japanese(context);
        PopupMenu popup = new PopupMenu(context, anchor);
        for (int seconds : new int[]{3, 5, 10, 30}) {
            popup.getMenu().add(0, seconds, seconds,
                    String.format(Locale.getDefault(), ja ? "%d秒" : "%d seconds", seconds));
        }
        popup.getMenu().add(0, 0, 100, ja ? "カスタム" : "Custom");
        popup.setOnMenuItemClickListener(item -> {
            try {
                if (item.getItemId() == 0) showCustom(context);
                else save(context, item.getItemId());
                return true;
            } catch (Throwable t) {
                DiagLog.e("seek", "Changing button duration", t);
                return false;
            }
        });
        popup.show();
    }

    private static void save(Context context, int seconds) {
        // Use the view's context so the menu also works before global initialization.
        SharedPreferences prefs = context.getSharedPreferences(ModSettings.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putInt(ModSettings.SKIP_BUTTON_SEC, seconds).apply();
    }

    private static void showCustom(Context context) {
        boolean ja = japanese(context);
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(Long.toString(buttonSeconds()));
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(ja ? "スキップ秒数 (1～600秒)" : "Skip duration (1–600 seconds)")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int seconds;
                try {
                    seconds = Integer.parseInt(input.getText().toString().trim());
                } catch (NumberFormatException e) {
                    seconds = 0;
                }
                if (seconds < ModSettings.MIN_SKIP_SEC || seconds > ModSettings.MAX_SKIP_SEC) {
                    input.setError(ja ? "1～600の整数を入力してください" : "Enter a whole number from 1 to 600");
                    return;
                }
                save(context, seconds);
                dialog.dismiss();
            } catch (Throwable t) {
                DiagLog.e("seek", "Saving custom duration", t);
            }
        });
    }
}
