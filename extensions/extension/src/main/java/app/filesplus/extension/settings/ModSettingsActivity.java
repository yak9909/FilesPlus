package app.filesplus.extension.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.ModSettings;
import app.filesplus.extension.smb.SmbClientFactory;
import app.filesplus.extension.smb.SmbConnection;
import app.filesplus.extension.smb.SmbConnectionStore;
import app.filesplus.extension.smb.SmbEntry;
import app.filesplus.extension.smb.SmbErrors;
import app.filesplus.extension.smb.SmbPath;
import app.filesplus.extension.smb.SmbSessionPool;
import app.filesplus.extension.smb.SmbTester;
import app.filesplus.extension.smb.SmbVfs;
import app.filesplus.extension.ui.UiSample;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/** Resource-free settings UI using the host activity theme and SDK widgets. */
public final class ModSettingsActivity extends Activity {
    private boolean japanese;
    private int primary;
    private int secondary;
    private int accent;
    private LinearLayout sections;
    private SettingsLook look;
    private SettingsLook.Row logRow;
    private SettingsLook.Row logToggleRow;
    private LinearLayout logBody;
    private boolean logExpanded;
    private String deviceReport = "";
    private String decoderReport = "";
    private SettingsLook.Row shareRow;
    private AlertDialog smbDialog;
    private LinearLayout smbList;

    /** The input fields of the SMB connection dialog. */
    private static final class SmbForm {
        EditText name;
        EditText host;
        EditText port;
        EditText share;
        EditText start;
        EditText user;
        EditText password;
        EditText domain;
        RadioButton openStart;
        /** Reveals the optional fields (called when one of them has an error). */
        Runnable showAdvanced;
    }

    @Override
    protected void onCreate(Bundle state) {
        // The original settings activity runs under this theme (preference theme + list text appearances).
        int preferencesTheme = SettingsLook.themeId(this);
        if (preferencesTheme != 0) setTheme(preferencesTheme);
        super.onCreate(state);
        try {
            ModKit.init(this);
            japanese = SettingsHooks.isJapanese(this);
            setTitle(SettingsHooks.entryTitle(this));
            primary = themeColor("textColorPrimary", android.R.attr.textColorPrimary, Color.DKGRAY);
            secondary = themeColor("textColorSecondary", android.R.attr.textColorSecondary, Color.GRAY);
            accent = themeColor("colorPrimary", android.R.attr.colorAccent, primary);
            int surface = themeColor("colorSurface", android.R.attr.colorBackground, Color.WHITE);
            look = new SettingsLook(this, primary, secondary, accent);

            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(surface);
            setContentView(root);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
            String title = SettingsHooks.entryTitle(this);
            FrameLayout content = null;
            // Preferred: the original settings layout (app bar over a content frame).
            View[] shell = look.shell(root);
            if (shell != null) {
                try {
                    ViewGroup appbar = (ViewGroup) shell[1];
                    appbar.addView(look.toolbar(title, tr("戻る", "Back"), v -> finish()), 0);
                    root.addView(shell[0]);
                    content = (FrameLayout) shell[2];
                } catch (Throwable t) {
                    DiagLog.e("settings", "Cannot use the original settings layout", t);
                    root.removeAllViews();
                }
            }
            DiagLog.i("settings", "Settings screen: theme " + (preferencesTheme != 0 ? "PreferencesTheme" : "default")
                    + ", layout " + (content != null ? "original activity_settings_v2" : "fallback"));
            if (content == null) {
                // Fallback: the plain programmatic screen.
                LinearLayout column = new LinearLayout(this);
                column.setOrientation(LinearLayout.VERTICAL);
                root.addView(column, new FrameLayout.LayoutParams(-1, -1));
                column.addView(look.toolbar(title, tr("戻る", "Back"), v -> finish()));
                content = new FrameLayout(this);
                column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
            }
            ScrollView scroll = new ScrollView(this);
            content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            sections = new LinearLayout(this);
            sections.setOrientation(LinearLayout.VERTICAL);
            sections.setPadding(0, 0, 0, dp(24));
            scroll.addView(sections);
            addSkipSection();
            addFolderTabsSection();
            addSmbSection();
            addDiagnosticsSection();
        } catch (Throwable t) {
            DiagLog.e("settings", "Cannot build settings screen", t);
            TextView error = text(tr("設定を開けませんでした。", "Could not open settings."), 16, primary);
            setContentView(error);
        }
    }

    @Override
    protected void onDestroy() {
        // Avoid leaked windows; the SMB test thread checks isDestroyed() before touching the UI.
        if (smbDialog != null) smbDialog.dismiss();
        smbDialog = null;
        super.onDestroy();
    }

    /** Shows one SMB test dialog at a time and remembers it so onDestroy can dismiss it. */
    private void showSmbDialog(AlertDialog dialog) {
        if (smbDialog != null) smbDialog.dismiss();
        smbDialog = dialog;
        look.styleDialog(dialog);
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        safely(this::refreshLog);
    }

    private void addSkipSection() {
        header(tr("スキップ", "Skip"));
        skipRow(tr("ダブルタップのスキップ秒数", "Double-tap skip seconds"), ModSettings.SKIP_DOUBLE_TAP_SEC);
        skipRow(tr("ボタンのスキップ秒数", "Button skip seconds"), ModSettings.SKIP_BUTTON_SEC);
        SettingsLook.Row toStart = look.switchRow(sections, tr("先頭付近でも巻き戻す", "Rewind near the start"),
                tr("ダブルタップの巻き戻しで、戻す秒数より手前にいるときは先頭へ戻ります。",
                        "A double-tap rewind closer to the start than the skip duration goes to the start."),
                ModSettings.skipRewindToStart());
        toStart.toggle.setOnCheckedChangeListener((view, checked) ->
                safely(() -> ModSettings.putBool(ModSettings.SKIP_REWIND_TO_START, checked)));
    }

    private void addFolderTabsSection() {
        header(tr("フォルダタブ", "Folder tabs"));
        folderTabsSwitch(tr("空のフォルダもタブに表示", "Show empty folders as tabs"),
                ModSettings.TABS_SHOW_EMPTY_FOLDERS, ModSettings.DEFAULT_TABS_SHOW_EMPTY_FOLDERS);
        folderTabsSwitch(tr("隠しフォルダも表示", "Show hidden folders"),
                ModSettings.TABS_SHOW_HIDDEN, ModSettings.DEFAULT_TABS_SHOW_HIDDEN);
    }

    private void folderTabsSwitch(String title, String key, boolean defaultValue) {
        SettingsLook.Row row = look.switchRow(sections, title, null, ModSettings.getBool(key, defaultValue));
        row.toggle.setOnCheckedChangeListener((view, checked) -> safely(() -> ModSettings.putBool(key, checked)));
    }

    private void addDiagnosticsSection() {
        header(tr("診断", "Diagnostics"));
        deviceReport = deviceReport();
        details(deviceReport);
        TextView codecs = details(tr("動画デコーダーを確認中…", "Checking video decoders…"));
        addSmbTestRow();
        View uiSample = look.row(sections, tr("UI 見本", "UI sample"),
                tr("元アプリの画面部品を表示して確かめます", "Shows the app's own list parts and which are used")).view;
        uiSample.setOnClickListener(v -> safely(() -> {
            UiSample.show(this, japanese);
            refreshLog();
        }));
        shareRow = look.row(sections, tr("テキストで共有", "Share as text"), null);
        shareRow.setEnabledLook(false);
        shareRow.view.setOnClickListener(v -> safely(this::shareDiagnostics));
        SettingsLook.Row clear = look.row(sections, tr("ログを消去", "Clear log"), null);
        clear.view.setOnClickListener(v -> safely(() -> {
            DiagLog.clear();
            refreshLog();
        }));
        // The log can be long, so it stays collapsed (and last) until the row is tapped.
        logToggleRow = look.row(sections, tr("ログ", "Log"), null);
        logToggleRow.view.setOnClickListener(v -> safely(() -> {
            logExpanded = !logExpanded;
            logBody.setVisibility(logExpanded ? View.VISIBLE : View.GONE);
            refreshLog();
        }));
        logBody = new LinearLayout(this);
        logBody.setOrientation(LinearLayout.VERTICAL);
        logBody.setVisibility(View.GONE);
        sections.addView(logBody);
        SettingsLook.Row copy = look.row(logBody, tr("ログをコピー", "Copy log"), null);
        copy.view.setOnClickListener(v -> safely(() -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic log", logReport()));
            Toast.makeText(this, tr("コピーしました", "Copied"), Toast.LENGTH_SHORT).show();
        }));
        // Starts empty (so hidden); refreshLog fills it through Row.setSummary, which also makes it visible.
        logRow = look.row(logBody, null, "");
        logRow.summary.setTextIsSelectable(true);
        refreshLog();
        // Codec enumeration can be slow on vendor implementations; keep it off the UI thread.
        new Thread(() -> {
            String report;
            try {
                report = decoderReport();
            } catch (Throwable t) {
                DiagLog.e("diagnostics", "Cannot enumerate video decoders", t);
                report = tr("動画デコーダー情報を取得できません。", "Video decoder information unavailable.");
            }
            final String result = report;
            runOnUiThread(() -> safely(() -> {
                if (isFinishing() || isDestroyed()) return;
                decoderReport = result;
                codecs.setText(result);
                shareRow.setEnabledLook(true);
                refreshLog();
            }));
        }, "ModKit-diagnostics").start();
    }

    private void addSmbSection() {
        header("SMB");
        smbList = new LinearLayout(this);
        smbList.setOrientation(LinearLayout.VERTICAL);
        sections.addView(smbList);
        refreshSmbList();
    }

    private void refreshSmbList() {
        smbList.removeAllViews();
        for (SmbConnection c : SmbConnectionStore.all()) {
            View row = look.row(smbList, c.label(), smbSummary(c)).view;
            row.setOnClickListener(v -> safely(() -> showSmbEditDialog(c)));
            row.setOnLongClickListener(v -> {
                safely(() -> showSmbMenu(c));
                return true;
            });
        }
        View add = look.row(smbList, tr("＋ 接続先を追加", "+ Add a connection"), null).view;
        add.setOnClickListener(v -> safely(() -> showSmbEditDialog(null)));
    }

    /** host[:port]/share[/folder] and the account; never anything secret. */
    private String smbSummary(SmbConnection c) {
        StringBuilder line = new StringBuilder(c.host);
        if (c.port != 445) line.append(':').append(c.port);
        if (c.hasShare()) line.append('/').append(c.share);
        if (!c.startPath.isEmpty()) line.append('/').append(c.startPath);
        line.append(" · ").append(c.user.isEmpty() ? tr("ゲスト", "Guest") : c.user);
        if (!c.user.isEmpty() && !SmbConnectionStore.hasPassword(c.id)) {
            line.append(" · ").append(tr("パスワード未入力", "no password"));
        }
        return line.toString();
    }

    private void showSmbMenu(SmbConnection c) {
        String[] items = {tr("編集", "Edit"), tr("名前の変更", "Rename"), tr("削除", "Delete")};
        showSmbDialog(new AlertDialog.Builder(this).setTitle(c.label())
                .setItems(items, (dialog, which) -> safely(() -> {
                    if (which == 0) showSmbEditDialog(c);
                    else if (which == 1) showSmbRenameDialog(c);
                    else showSmbDeleteDialog(c);
                })).create());
    }

    private void showSmbRenameDialog(SmbConnection c) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(c.hostAndShare());
        input.setText(c.displayName);
        input.selectAll();
        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(20), dp(8), dp(20), 0);
        container.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(tr("名前の変更", "Rename")).setView(container)
                .setPositiveButton(tr("保存", "Save"), (d, which) -> safely(() -> {
                    // Asynchronous write; sessions and the password are untouched.
                    SmbConnectionStore.setDisplayName(c.id, input.getText().toString());
                    refreshSmbList();
                }))
                .setNegativeButton(tr("キャンセル", "Cancel"), null).create();
        showSmbDialog(dialog);
    }

    private void showSmbDeleteDialog(SmbConnection c) {
        showSmbDialog(new AlertDialog.Builder(this).setTitle(tr("接続先の削除", "Delete connection"))
                .setMessage(tr("「" + c.label() + "」を削除します。保存したパスワードも消えます。",
                        "Delete \"" + c.label() + "\"? The saved password is removed too."))
                .setPositiveButton(tr("削除", "Delete"), (d, which) -> safely(() -> {
                    SmbConnectionStore.delete(c.id);
                    refreshSmbList();
                }))
                .setNegativeButton(tr("キャンセル", "Cancel"), null).create());
    }

    /** Add (existing == null) or edit dialog. The password field is never prefilled. */
    private void showSmbEditDialog(SmbConnection existing) {
        boolean adding = existing == null;
        SmbForm f = new SmbForm();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        int plain = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        // Host, user and password are enough; everything else is optional and sits under the expander.
        f.host = smbField(form, tr("ホスト（例: 192.168.0.10）", "Host (e.g. 192.168.0.10)"), plain | InputType.TYPE_TEXT_VARIATION_URI);
        f.user = smbField(form, tr("ユーザー（空欄でゲスト）", "User (blank for guest)"), plain);
        f.password = smbField(form, tr("パスワード", "Password"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView expander = text("", 16, accent);
        expander.setPadding(0, dp(16), 0, dp(8));
        form.addView(expander);
        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        form.addView(advanced);
        f.name = smbField(advanced, tr("表示名（任意）", "Display name (optional)"), InputType.TYPE_CLASS_TEXT);
        f.port = smbField(advanced, tr("ポート", "Port"), InputType.TYPE_CLASS_NUMBER);
        f.share = smbField(advanced, tr("共有名（任意。空欄なら一覧から選ぶ）", "Share name (optional; blank lists the shares)"), plain);
        f.start = smbField(advanced, tr("開始フォルダ（任意。例: Videos/2024。共有名が空なら 共有名/フォルダ）",
                "Start folder (optional, e.g. Videos/2024; with no share name: share/folder)"), plain);
        f.domain = smbField(advanced, tr("ドメイン（任意）", "Domain (optional)"), plain);
        TextView openLabel = text(tr("開く場所", "Open at"), 14, secondary);
        openLabel.setPadding(0, dp(12), 0, 0);
        advanced.addView(openLabel);
        RadioGroup open = new RadioGroup(this);
        f.openStart = new RadioButton(this);
        f.openStart.setText(tr("開始フォルダ", "Start folder"));
        RadioButton openLast = new RadioButton(this);
        openLast.setText(tr("前回開いていたフォルダ", "Last folder"));
        f.openStart.setTextColor(primary);
        openLast.setTextColor(primary);
        open.addView(f.openStart);
        open.addView(openLast);
        advanced.addView(open);
        expander.setText(tr("▸ 詳細設定", "▸ Advanced"));
        f.showAdvanced = () -> {
            advanced.setVisibility(View.VISIBLE);
            expander.setText(tr("▾ 詳細設定", "▾ Advanced"));
        };
        expander.setOnClickListener(v -> {
            if (advanced.getVisibility() == View.VISIBLE) {
                advanced.setVisibility(View.GONE);
                expander.setText(tr("▸ 詳細設定", "▸ Advanced"));
            } else {
                f.showAdvanced.run();
            }
        });
        if (adding) {
            f.port.setText("445");
            openLast.setChecked(true);
        } else {
            f.name.setText(existing.displayName);
            f.host.setText(existing.host);
            f.port.setText(String.valueOf(existing.port));
            f.share.setText(existing.share);
            f.start.setText(existing.startPath);
            f.user.setText(existing.user);
            f.domain.setText(existing.domain);
            f.openStart.setChecked(SmbConnection.OPEN_START.equals(existing.openWhere));
            openLast.setChecked(!f.openStart.isChecked());
            // Show the optional fields at once when any of them holds a value.
            if (!existing.displayName.isEmpty() || existing.port != SmbClientFactory.DEFAULT_PORT || existing.hasShare()
                    || !existing.startPath.isEmpty() || !existing.domain.isEmpty() || f.openStart.isChecked()) {
                f.showAdvanced.run();
            }
        }
        TextView status = text("", 14, secondary);
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(12), 0, dp(8));
        form.addView(status);
        if (!adding && SmbConnectionStore.hasPassword(existing.id)) {
            f.password.setHint(tr("パスワード（空欄で変更しない）", "Password (blank keeps it)"));
            // Decrypting uses the Keystore, so check in the background and adjust the hint afterwards.
            new Thread(() -> {
                if (SmbConnectionStore.passwordReadable(existing.id)) return;
                runOnUiThread(() -> safely(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    f.password.setHint(tr("パスワード（再入力が必要）", "Password (enter it again)"));
                    if (status.getText().length() == 0) {
                        status.setText(tr("保存したパスワードを読み出せません。もう一度入力してください。",
                                "The saved password cannot be read. Please enter it again."));
                    }
                }));
            }, "ModKit-smb-check").start();
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(adding ? tr("接続先を追加", "Add connection") : tr("接続先を編集", "Edit connection")).setView(scroll)
                .setPositiveButton(tr("保存", "Save"), null)
                .setNeutralButton(tr("接続テスト", "Test"), null)
                .setNegativeButton(tr("キャンセル", "Cancel"), null).create();
        // Overridden in onShow so a failed validation keeps the dialog open.
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button test = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            save.setOnClickListener(v -> safely(() -> saveSmbForm(dialog, f, existing, status, save, test)));
            test.setOnClickListener(v -> safely(() -> testSmbForm(dialog, f, existing, status, save, test)));
        });
        showSmbDialog(dialog);
    }

    /** Builds a connection from the form, or returns null after marking the invalid fields. */
    private SmbConnection readSmbForm(SmbForm f, SmbConnection existing) {
        int port = validateSmbTarget(f.host, f.port, f.share, false);
        String start = f.start.getText().toString();
        boolean valid = port >= 0;
        try {
            start = SmbPath.normalize(start);
        } catch (IllegalArgumentException e) {
            f.start.setError(tr("「..」は使えません", "\"..\" is not allowed"));
            valid = false;
        }
        if (!valid) {
            // An error on a hidden optional field would otherwise go unnoticed.
            if (f.port.getError() != null || f.share.getError() != null || f.start.getError() != null) {
                f.showAdvanced.run();
            }
            return null;
        }
        SmbConnection c = new SmbConnection(existing == null ? null : existing.id, f.name.getText().toString(),
                f.host.getText().toString(), port, f.share.getText().toString(), start, f.user.getText().toString(),
                f.domain.getText().toString(),
                f.openStart.isChecked() ? SmbConnection.OPEN_START : SmbConnection.OPEN_LAST,
                existing == null ? "" : existing.lastPath);
        // A blank password means "keep the saved one", which must never be sent to a different server or account.
        if (existing != null && !c.user.isEmpty() && f.password.getText().length() == 0
                && !c.credentialKey().equals(existing.credentialKey())) {
            f.password.setError(tr("ホスト・ユーザーを変えたときはパスワードを入力してください",
                    "Enter the password when the host or user changes"));
            return null;
        }
        return c;
    }

    private void saveSmbForm(AlertDialog dialog, SmbForm f, SmbConnection existing, TextView status, Button save,
            Button test) {
        SmbConnection c = readSmbForm(f, existing);
        if (c == null) return;
        // null keeps the stored password, "" removes it (a guest needs none).
        String typed = f.password.getText().toString();
        String password = c.user.isEmpty() ? "" : (typed.isEmpty() ? null : typed);
        save.setEnabled(false);
        test.setEnabled(false);
        status.setText(tr("保存中…", "Saving…"));
        // Encrypting the password may use the Keystore, so keep it off the UI thread.
        new Thread(() -> {
            boolean saved = SmbConnectionStore.save(c, password);
            runOnUiThread(() -> safely(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (saved) {
                    dialog.dismiss();
                    refreshSmbList();
                } else {
                    status.setText(tr("保存できませんでした（パスワードを暗号化できない、または保存データが壊れています）",
                            "Could not save (the password cannot be encrypted, or the saved data is damaged)"));
                    save.setEnabled(true);
                    test.setEnabled(true);
                }
            }));
        }, "ModKit-smb-save").start();
    }

    private void testSmbForm(AlertDialog dialog, SmbForm f, SmbConnection existing, TextView status, Button save,
            Button test) {
        SmbConnection c = readSmbForm(f, existing);
        if (c == null) return;
        String typed = f.password.getText().toString();
        save.setEnabled(false);
        test.setEnabled(false);
        status.setText(tr("接続中…", "Connecting…"));
        // Network I/O must stay off the UI thread.
        new Thread(() -> {
            String result = runSmbConnectionTest(c, typed, existing);
            runOnUiThread(() -> safely(() -> {
                if (isFinishing() || isDestroyed()) return;
                status.setText(result);
                save.setEnabled(true);
                test.setEnabled(true);
                refreshLog();
            }));
        }, "ModKit-smb-conn-test").start();
    }

    /** One-line result of listing, stat and a 1 KB read with the entered (possibly unsaved) values. */
    private String runSmbConnectionTest(SmbConnection c, String typedPassword, SmbConnection existing) {
        // A throwaway id keeps the test away from the pooled session of the saved connection.
        SmbConnection probe = new SmbConnection(SmbConnection.newId(), c.displayName, c.host, c.port, c.share,
                c.startPath, c.user, c.domain, c.openWhere, "");
        String password = null;
        if (!c.user.isEmpty()) {
            password = !typedPassword.isEmpty() ? typedPassword
                    : (existing == null ? null : SmbConnectionStore.password(existing.id));
            // Never guess an empty password: repeated failed logons can lock the account.
            if (password == null) return tr("パスワードを入力してください", "Enter the password");
        }
        long started = System.currentTimeMillis();
        try {
            SmbVfs vfs = new SmbVfs(probe, password);
            List<SmbEntry> entries = vfs.list(probe.startPath);
            vfs.stat(probe.startPath);
            // No share and no start folder: the entries are the server's shares.
            boolean shareList = !probe.hasShare() && probe.startPath.isEmpty();
            StringBuilder result = new StringBuilder("OK: ").append(entries.size())
                    .append(shareList ? tr(" 件の共有", " shares") : tr(" 件", " entries"));
            if (shareList && !entries.isEmpty()) {
                result.append(" (");
                for (int i = 0; i < entries.size() && i < 5; i++) result.append(i > 0 ? ", " : "").append(entries.get(i).name);
                result.append(entries.size() > 5 ? ", …)" : ")");
            }
            SmbEntry firstFile = null;
            for (SmbEntry entry : entries) {
                if (!entry.isDir) {
                    firstFile = entry;
                    break;
                }
            }
            if (firstFile != null) {
                try (InputStream in = vfs.openRead(SmbPath.join(probe.startPath, firstFile.name), 0)) {
                    int count = in.read(new byte[1024]);
                    result.append(tr("、先頭 ", ", read ")).append(Math.max(count, 0)).append(" bytes (")
                            .append(firstFile.name).append(')');
                } catch (Throwable t) {
                    result.append(tr("、読み取り失敗: ", ", read failed: ")).append(SmbErrors.describe(t));
                }
            }
            return result.append(" · ").append(System.currentTimeMillis() - started).append(" ms").toString();
        } catch (Throwable t) {
            DiagLog.i("smb", "Connection test failed: " + SmbErrors.describe(t));
            return smbErrorText(t);
        } finally {
            SmbSessionPool.close(probe.id);
        }
    }

    /** A short, readable one-liner for a failure; the technical detail follows in parentheses. */
    private String smbErrorText(Throwable t) {
        String detail = " (" + SmbErrors.describe(t) + ")";
        if (SmbErrors.isPasswordMissing(t)) return tr("パスワードが読めません。入力し直してください", "Password unreadable, please re-enter");
        if (SmbErrors.isShareListUnavailable(t)) {
            return tr("サーバーが共有の一覧を返しませんでした。共有名を入力してください",
                    "The server did not list its shares. Enter the share name") + detail;
        }
        if (SmbErrors.isAuthFailure(t)) return tr("ユーザー名またはパスワードが違います", "Wrong user name or password") + detail;
        if (SmbErrors.hasStatus(t, "STATUS_BAD_NETWORK_NAME")) return tr("共有名が見つかりません", "Share not found") + detail;
        if (SmbErrors.isNotFound(t)) return tr("開始フォルダが見つかりません", "Start folder not found") + detail;
        if (SmbErrors.isUnreachable(t)) return tr("サーバーに接続できません", "Cannot reach the server") + detail;
        return tr("失敗", "Failed") + detail;
    }

    private void addSmbTestRow() {
        View row = look.row(sections, tr("SMB テスト", "SMB test"),
                tr("接続・一覧・先頭 1 KB の読み取りを確かめます", "Checks connect, listing and a 1 KB read")).view;
        row.setOnClickListener(v -> safely(this::showSmbTestDialog));
    }

    private void showSmbTestDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        int plain = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        EditText host = smbField(form, tr("ホスト（例: 192.168.0.10）", "Host (e.g. 192.168.0.10)"), plain | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = smbField(form, tr("ポート", "Port"), InputType.TYPE_CLASS_NUMBER);
        port.setText("445");
        EditText user = smbField(form, tr("ユーザー（空欄でゲスト）", "User (blank for guest)"), plain);
        EditText password = smbField(form, tr("パスワード", "Password"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText domain = smbField(form, tr("ドメイン（任意）", "Domain (optional)"), plain);
        EditText share = smbField(form, tr("共有名（任意。空欄なら共有の一覧を調べる）", "Share name (optional; blank lists the shares)"), plain);
        EditText path = smbField(form, tr("パス（任意。例: Videos/2024）", "Path (optional, e.g. Videos/2024)"), plain);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(tr("SMB テスト", "SMB test")).setView(scroll)
                .setPositiveButton(tr("実行", "Run"), null)
                .setNegativeButton(tr("キャンセル", "Cancel"), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> safely(() -> {
                    int portValue = validateSmbTarget(host, port, share, false);
                    if (portValue < 0) return;
                    String hostValue = host.getText().toString().trim();
                    String shareValue = share.getText().toString().trim();
                    String userValue = user.getText().toString();
                    String passwordValue = password.getText().toString();
                    String domainValue = domain.getText().toString();
                    String pathValue = path.getText().toString();
                    dialog.dismiss();
                    runSmbTest(hostValue, portValue, userValue, passwordValue, domainValue, shareValue, pathValue);
                })));
        showSmbDialog(dialog);
    }

    private EditText smbField(LinearLayout form, String hint, int inputType) {
        EditText field = new EditText(this);
        field.setMinHeight(dp(48));
        field.setHint(hint);
        // setSingleLine replaces the transformation method, so it must run before setInputType and the
        // password masking is applied explicitly afterwards.
        field.setSingleLine(true);
        field.setInputType(inputType);
        if ((inputType & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        // Keep credentials away from the system autofill service.
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        form.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    /** Checks host, port and share; returns the port, or -1 after marking the invalid fields. */
    private int validateSmbTarget(EditText host, EditText port, EditText share, boolean shareRequired) {
        int portValue;
        try {
            portValue = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException e) {
            portValue = 0;
        }
        boolean valid = true;
        if (host.getText().toString().trim().isEmpty()) {
            host.setError(tr("ホストを入力してください", "Enter a host"));
            valid = false;
        }
        if (portValue < 1 || portValue > 65535) {
            port.setError(tr("1〜65535の整数を入力してください", "Enter a number from 1 to 65535"));
            valid = false;
        }
        String shareValue = share.getText().toString().trim();
        if (shareValue.isEmpty()) {
            if (shareRequired) {
                share.setError(tr("共有名を入力してください", "Enter a share name"));
                valid = false;
            }
        } else if (shareValue.indexOf('/') >= 0 || shareValue.indexOf('\\') >= 0) {
            share.setError(tr("共有名に / や \\ は使えません（フォルダは開始フォルダに入力）", "No / or \\ in the share name (use the start folder)"));
            valid = false;
        }
        return valid ? portValue : -1;
    }

    private void runSmbTest(String host, int port, String user, String password, String domain, String share, String path) {
        boolean[] abandoned = {false};
        AlertDialog progress = new AlertDialog.Builder(this).setTitle(tr("SMB テスト", "SMB test"))
                .setMessage(tr("接続中… 最大で 1 分ほどかかることがあります", "Connecting… this may take up to a minute"))
                .setNegativeButton(tr("閉じる", "Close"), (d, which) -> abandoned[0] = true)
                .setOnCancelListener(d -> abandoned[0] = true).create();
        showSmbDialog(progress);
        // Network I/O must stay off the UI thread.
        new Thread(() -> {
            String report;
            try {
                report = SmbTester.run(host, port, user, password, domain, share, path);
            } catch (Throwable t) {
                DiagLog.e("smb", "SMB test crashed", t);
                report = "Error: " + t.getClass().getSimpleName();
            }
            final String result = report;
            runOnUiThread(() -> safely(() -> {
                if (abandoned[0] || isFinishing() || isDestroyed()) return;
                progress.dismiss();
                showSmbResult(result);
                refreshLog();
            }));
        }, "ModKit-smb-test").start();
    }

    private void showSmbResult(String report) {
        TextView body = text(report, 14, primary);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(24), dp(8), dp(24), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog result = new AlertDialog.Builder(this).setTitle(tr("SMB テストの結果", "SMB test result")).setView(scroll)
                .setPositiveButton(tr("共有", "Share"), (d, which) -> safely(() -> {
                    Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, tr("SMB テストの結果", "SMB test result"))
                            .putExtra(Intent.EXTRA_TEXT, report);
                    startActivity(Intent.createChooser(intent, tr("結果を共有", "Share result")));
                }))
                .setNeutralButton(tr("コピー", "Copy"), (d, which) -> safely(() -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("SMB test", report));
                    Toast.makeText(this, tr("コピーしました", "Copied"), Toast.LENGTH_SHORT).show();
                }))
                .setNegativeButton(tr("閉じる", "Close"), null).create();
        showSmbDialog(result);
    }

    private void skipRow(String title, String key) {
        SettingsLook.Row row = look.row(sections, title, seconds(skipValue(key)));
        row.view.setOnClickListener(v -> safely(() -> chooseSkip(title, key, row.summary)));
    }

    private int skipValue(String key) {
        return ModSettings.SKIP_DOUBLE_TAP_SEC.equals(key)
                ? ModSettings.skipDoubleTapSec() : ModSettings.skipButtonSec();
    }

    private void chooseSkip(String title, String key, TextView value) {
        int[] presets = {3, 5, 10};
        int current = skipValue(key);
        int selected = 3;
        for (int i = 0; i < presets.length; i++) if (presets[i] == current) selected = i;
        String[] choices = {seconds(3), seconds(5), seconds(10), tr("カスタム（1〜600秒）", "Custom (1–600 seconds)")};
        showStyled(new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(choices, selected, (dialog, which) -> safely(() -> {
                    dialog.dismiss();
                    if (which == 3) customSkip(title, key, value);
                    else saveSkip(key, presets[which], value);
                }))
                .setNegativeButton(tr("キャンセル", "Cancel"), null));
    }

    private void customSkip(String title, String key, TextView value) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(skipValue(key)));
        input.selectAll();
        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(20), dp(8), dp(20), 0);
        container.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(container)
                .setPositiveButton(tr("保存", "Save"), null)
                .setNegativeButton(tr("キャンセル", "Cancel"), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> safely(() -> {
                    int number;
                    try {
                        number = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        number = 0;
                    }
                    if (number < ModSettings.MIN_SKIP_SEC || number > ModSettings.MAX_SKIP_SEC) {
                        input.setError(tr("1〜600の整数を入力してください", "Enter a whole number from 1 to 600"));
                        return;
                    }
                    saveSkip(key, number, value);
                    dialog.dismiss();
                })));
        look.styleDialog(dialog);
        dialog.show();
    }

    private void saveSkip(String key, int number, TextView value) {
        ModSettings.putInt(key, number);
        value.setText(seconds(number));
    }

    private String deviceReport() {
        String version;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable t) {
            DiagLog.e("diagnostics", "Cannot read app version", t);
            version = tr("取得不可", "Unavailable");
        }
        return tr("メーカー / モデル: ", "Manufacturer / model: ") + Build.MANUFACTURER + " / " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                + "\n" + tr("対応 ABI: ", "Supported ABIs: ") + String.join(", ", Build.SUPPORTED_ABIS)
                + "\n" + tr("アプリの ABI: ", "App ABI: ") + appAbi()
                + "\n" + tr("アプリ: ", "App: ") + getPackageName() + "\n" + tr("バージョン: ", "Version: ") + version;
    }

    private String decoderReport() {
        StringBuilder report = new StringBuilder(tr("動画デコーダー", "Video decoders"));
        String[] types = {"video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01"};
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        for (String type : types) {
            report.append("\n\n").append(type);
            boolean found = false;
            for (MediaCodecInfo info : infos) {
                if (info.isEncoder()) continue;
                for (String supported : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(supported)) continue;
                    found = true;
                    report.append("\n\n").append(info.getName());
                    try {
                        report.append("\n").append(tr("ハードウェア: ", "Hardware: ")).append(yesNo(info.isHardwareAccelerated()))
                                .append(" / ").append(tr("ソフトウェア: ", "Software: ")).append(yesNo(info.isSoftwareOnly()));
                        MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(supported).getVideoCapabilities();
                        if (video == null) throw new IllegalStateException("No video capabilities");
                        report.append("\n").append(tr("最大幅 × 高さ（各上限）: ", "Max width × height (individual limits): "))
                                .append(video.getSupportedWidths().getUpper()).append(" × ")
                                .append(video.getSupportedHeights().getUpper());
                        probe(report, video, 1920, 1080, 60);
                        probe(report, video, 2560, 1440, 30);
                        probe(report, video, 3840, 2160, 30);
                    } catch (Throwable t) {
                        DiagLog.e("diagnostics", "Cannot read " + info.getName() + " / " + type, t);
                        report.append("\n").append(tr("機能情報を取得できません", "Capabilities unavailable"));
                    }
                }
            }
            if (!found) report.append("\n").append(tr("デコーダーなし", "No decoder found"));
        }
        return report.toString();
    }

    private void probe(StringBuilder report, MediaCodecInfo.VideoCapabilities video, int width, int height, int rate) {
        report.append("\n").append(width).append('×').append(height).append('@').append(rate).append(": ");
        try {
            report.append(yesNo(video.areSizeAndRateSupported(width, height, rate)));
        } catch (Throwable t) {
            DiagLog.e("diagnostics", "Cannot query size/rate " + width + "x" + height + "@" + rate, t);
            report.append(tr("不明", "Unknown"));
        }
    }

    private String logReport() {
        List<String> entries = DiagLog.snapshot();
        Collections.reverse(entries);
        return entries.isEmpty() ? tr("ログはありません", "No log entries") : String.join("\n\n", entries);
    }

    private void refreshLog() {
        if (logToggleRow == null) return;
        int count = DiagLog.snapshot().size();
        String state = logExpanded ? tr("タップで閉じる", "Tap to collapse") : tr("タップで表示", "Tap to expand");
        logToggleRow.setSummary(tr("新しい順 · " + count + " 件 · ", "Newest first · " + count + " entries · ") + state);
        // The text is only laid out while the log is open.
        if (logExpanded && logRow != null) logRow.setSummary(logReport());
        logToggleRow.view.setStateDescription(logExpanded ? tr("展開済み", "Expanded") : tr("折りたたみ", "Collapsed"));
    }

    private void shareDiagnostics() {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, SettingsHooks.entryTitle(this))
                .putExtra(Intent.EXTRA_TEXT, deviceReport + "\n\n" + decoderReport + "\n\n"
                        + tr("診断ログ（新しい順）", "Diagnostic log (newest first)") + "\n" + logReport());
        startActivity(Intent.createChooser(intent, tr("診断情報を共有", "Share diagnostics")));
    }

    private void header(String title) {
        // Like the original list: a divider closes the previous section.
        if (sections.getChildCount() > 0) look.divider(sections);
        look.header(sections, title);
    }

    private TextView details(String content) {
        return details(sections, content);
    }

    /** A summary-only settings row holding selectable text. */
    private TextView details(LinearLayout parent, String content) {
        TextView view = look.row(parent, null, content).summary;
        view.setTextIsSelectable(true);
        return view;
    }

    /** Applies the original dialog surface and shows the dialog. */
    private void showStyled(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        look.styleDialog(dialog);
        dialog.show();
    }

    private TextView text(String content, int size, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int themeColor(String name, int fallbackAttribute, int fallbackColor) {
        TypedValue value = new TypedValue();
        int attribute = getResources().getIdentifier(name, "attr", getPackageName());
        if (attribute == 0 || !getTheme().resolveAttribute(attribute, value, true)) {
            if (!getTheme().resolveAttribute(fallbackAttribute, value, true)) return fallbackColor;
        }
        if (value.resourceId != 0) return getColorStateList(value.resourceId).getDefaultColor();
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                ? value.data : fallbackColor;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String seconds(int number) {
        return number + tr("秒", " seconds");
    }

    private String yesNo(boolean value) {
        return value ? tr("はい", "Yes") : tr("いいえ", "No");
    }

    /** ABI the app process runs as (a 32-bit APK also runs on a 64-bit device). */
    private String appAbi() {
        try {
            String dir = getApplicationInfo().nativeLibraryDir;
            return (android.os.Process.is64Bit() ? "64bit" : "32bit")
                    + (dir == null ? "" : " (" + dir.substring(dir.lastIndexOf('/') + 1) + ")");
        } catch (Throwable t) {
            DiagLog.e("diagnostics", "Cannot read app ABI", t);
            return "?";
        }
    }

    private String tr(String ja, String en) {
        return japanese ? ja : en;
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            DiagLog.e("settings", "Settings action failed", t);
            Toast.makeText(this, tr("操作に失敗しました。診断ログを確認してください。", "Action failed. See diagnostic log."), Toast.LENGTH_LONG).show();
        }
    }
}
