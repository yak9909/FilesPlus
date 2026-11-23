package app.filesplus.extension.settings;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Environment;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.ModSettings;
import app.filesplus.extension.preview.PreviewHooks;
import app.filesplus.extension.browse.Category;
import app.filesplus.extension.browse.ViewSwitch;
import app.filesplus.extension.clip.PasteSelfTest;
import app.filesplus.extension.clip.Clipboard;
import app.filesplus.extension.clip.InterruptedPastes;
import app.filesplus.extension.smb.*;
import app.filesplus.extension.ui.OriginalScreens;
import app.filesplus.extension.ui.ScreenArgs;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Page definitions only. The app owns the activity, preferences, switches, dialogs and input layouts. */
public final class SettingsPages {
    public static final String PAGE_EXTRA = "filesplus_page";
    private static final String ACTIVITY = "com.google.android.apps.nbu.files.settings.SettingsActivity";
    private static final int REQUEST_PICK_FOLDER = 0x4f53;
    private static final int REQUEST_SMB_EDIT = 0x4f54;
    private static final Map<Activity, String> TITLES = new WeakHashMap<>();
    private final PrefBridge bridge;
    private final Activity activity;
    private final Context context;
    private final boolean japanese;
    private final String page;
    private final Object screen;
    private int nextKey;
    private String deviceReport = "";
    private String decoderReport = "";

    private SettingsPages(PrefBridge bridge, Object screen, String page) {
        this.bridge = bridge;
        this.activity = bridge.host();
        if (activity == null) throw new IllegalStateException("Settings fragment has no activity");
        this.screen = screen;
        this.page = page;
        // The preference context of the app's own builder (it carries the preference theme).
        this.context = bridge.context(screen);
        japanese = SettingsHooks.isJapanese(activity);
    }

    public static void hookError(Throwable error) {
        DiagLog.e("settings", "Original settings hook failed", error);
    }

    /** A failed page is never published: the final app call still installs its original screen. */
    public static void build(Object fragment, Object original) {
        try {
            PrefBridge bridge = (PrefBridge) fragment;
            bridge.publish(null);
            Activity host = bridge.host();
            ModKit.init(host);
            String page = host.getIntent().getStringExtra(PAGE_EXTRA);
            if (page == null) {
                SettingsPages pages = new SettingsPages(bridge, original, "app");
                // Attach the category first, like the app does, so its children join an attached hierarchy.
                Object category = pages.category("FilesPlus");
                bridge.addTo(original, category);
                pages.link(category, "root", pages.titleOf("root"));
                pages.link(category, "diag", pages.titleOf("diag"));
                return;
            }
            if (!validPage(page)) return;
            Object replacement = bridge.newScreen(original);
            SettingsPages pages = new SettingsPages(bridge, replacement, page);
            pages.populate();
            String title = pages.pageTitle();
            bridge.publish(replacement);
            TITLES.put(host, title);
            host.setTitle(title);
        } catch (Throwable t) { hookError(t); }
    }

    public static Intent intent(Context context, String pageId) {
        try {
            if (!validPage(pageId)) throw new IllegalArgumentException("Unknown settings page");
            Intent intent = new Intent().setClassName(context.getPackageName(), ACTIVITY).putExtra(PAGE_EXTRA, pageId);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Throwable t) { hookError(t); return null; }
    }

    public static void open(Context context, String pageId) {
        try {
            Intent intent = intent(context, pageId);
            if (intent != null) context.startActivity(intent);
        } catch (Throwable t) { hookError(t); }
    }

    /** SettingsActivity sets its title after placing the fragment, and can overwrite build's title. */
    public static void updateTitle(Activity activity) {
        try {
            String title = TITLES.get(activity);
            if (title != null) activity.setTitle(title);
        } catch (Throwable t) { hookError(t); }
    }

    public static void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        try {
            if (requestCode == REQUEST_SMB_EDIT) {
                // Rebuild the SMB list after an add, edit, rename or delete (posted: not inside the result dispatch).
                activity.getWindow().getDecorView().post(activity::recreate);
                return;
            }
            if (requestCode != REQUEST_PICK_FOLDER) return;
            String picked = resultCode == Activity.RESULT_OK ? OriginalScreens.readPicked(data) : null;
            DiagLog.i("screens", "Picked folder: " + picked + " (result " + resultCode + ")");
            Toast.makeText(activity, (SettingsHooks.isJapanese(activity) ? "選んだフォルダ: " : "Picked: ") + picked,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) { hookError(t); }
    }

    private static boolean validPage(String page) {
        return "root".equals(page) || "tabs".equals(page) || "skip".equals(page) || "preview".equals(page)
                || "clipboard".equals(page) || "switch".equals(page) || "smb".equals(page) || "diag".equals(page);
    }

    private static boolean hasItems(String id) {
        return "tabs".equals(id) || "skip".equals(id) || "clipboard".equals(id)
                || ("preview".equals(id) && PreviewHooks.longPressInstalled())
                || ("switch".equals(id) && ViewSwitch.installed()) || "smb".equals(id) || "diag".equals(id);
    }

    private String pageTitle() { return titleOf(page); }

    private String titleOf(String id) {
        switch (id) {
            case "tabs": return tr("フォルダタブ", "Folder tabs");
            case "skip": return tr("スキップ", "Skip");
            case "preview": return tr("プレビュー", "Preview");
            case "clipboard": return tr("クリップボード", "Clipboard");
            case "switch": return tr("表示の切り替え", "View switching");
            case "smb": return "SMB";
            case "diag": return tr("診断", "Diagnostics");
            default: return tr("FilesPlus の設定", "FilesPlus settings");
        }
    }

    private void populate() {
        switch (page) {
            case "root":
                for (String id : new String[]{"tabs", "skip", "preview", "clipboard", "switch", "smb", "diag"}) {
                    if (hasItems(id)) link(screen, id, titleOf(id));
                }
                break;
            case "tabs":
                toggle(screen, tr("空のフォルダもタブに表示", "Show empty folders as tabs"),
                        tr("ダウンロードのカテゴリだけ", "Downloads category only"),
                        ModSettings.TABS_SHOW_EMPTY_FOLDERS, ModSettings.DEFAULT_TABS_SHOW_EMPTY_FOLDERS);
                toggle(screen, tr("隠しフォルダも表示", "Show hidden folders"),
                        tr("空のフォルダのタブで", "For empty-folder tabs"),
                        ModSettings.TABS_SHOW_HIDDEN, ModSettings.DEFAULT_TABS_SHOW_HIDDEN);
                break;
            case "skip":
                skip(tr("ダブルタップのスキップ秒数", "Double-tap skip seconds"), ModSettings.SKIP_DOUBLE_TAP_SEC);
                skip(tr("ボタンのスキップ秒数", "Button skip seconds"), ModSettings.SKIP_BUTTON_SEC);
                toggle(screen, tr("先頭付近でも巻き戻す", "Rewind near the start"),
                        tr("ダブルタップの巻き戻しで、戻す秒数より手前にいるときは先頭へ戻ります。",
                                "A double-tap rewind closer to the start than the skip duration goes to the start."),
                        ModSettings.SKIP_REWIND_TO_START, ModSettings.DEFAULT_SKIP_REWIND_TO_START);
                break;
            case "preview":
                if (PreviewHooks.longPressInstalled()) {
                    toggle(screen, tr("長押しで選択", "Long-press to select"),
                            tr("プレビュー中の長押しでファイルを選択・解除",
                                    "Long-press in the preview selects or deselects the file"),
                            ModSettings.PREVIEW_LONG_PRESS_SELECT, ModSettings.DEFAULT_PREVIEW_LONG_PRESS_SELECT);
                }
                break;
            case "clipboard": clipboard(); break;
            case "switch": if (ViewSwitch.installed()) viewSwitch(); break;
            case "smb": smb(); break;
            case "diag": diagnostics(); break;
            default: break;
        }
    }

    private Object preference(String title, String summary) {
        Object pref = bridge.newPreference(context);
        bridge.prepare(pref);
        bridge.setKey(pref, "filesplus." + page + "." + nextKey++);
        bridge.setTitle(pref, title);
        if (summary != null) bridge.setSummary(pref, summary);
        return pref;
    }

    private Object category(String title) {
        Object category = bridge.newCategory(context);
        bridge.prepare(category);
        bridge.setKey(category, "filesplus.category." + page);
        bridge.setTitle(category, title);
        bridge.categoryStyle(category);
        return category;
    }

    private Object row(Object group, String title, String summary, Runnable action) {
        Object pref = preference(title, summary);
        if (action != null) bridge.setClick(pref, listener(bridge.clickType(), () -> safely(action)));
        bridge.addTo(group, pref);
        return pref;
    }

    private void link(Object group, String id, String title) {
        row(group, title, null, () -> open(activity, id));
    }

    private void clipboard() {
        String title = tr("「すべて」タブでの貼り付け", "Paste in the All tab");
        java.util.List<String> values = java.util.Arrays.asList("off", "default", "last", "ask");
        String[] labels = {
                tr("できない（既定）", "Not allowed (default)"),
                tr("カテゴリの既定フォルダ", "Category's default folder"),
                tr("最後に貼り付けたフォルダ", "Last paste folder"),
                tr("毎回選ぶ", "Ask every time")
        };
        Object pref = preference(title, labels[Math.max(0, values.indexOf(ModSettings.pasteAllTab()))]);
        bridge.setKey(pref, ModSettings.PASTE_ALL_TAB);
        bridge.setClick(pref, listener(bridge.clickType(), () -> choices(title, labels,
                Math.max(0, values.indexOf(ModSettings.pasteAllTab())), which -> {
                    ModSettings.setPasteAllTab(values.get(which));
                    bridge.setSummary(pref, labels[which]);
                    app.filesplus.extension.clip.ClipHooks.pasteSettingChanged();
                })));
        bridge.addTo(screen, pref);
    }

    private void viewSwitch() {
        switchChoice(ModSettings.SWITCH_TO_MEDIA_CATEGORY,
                tr("メディアで表示するカテゴリ", "Category for Show as media"), new String[]{"auto", "ask"},
                new String[]{tr("自動（既定）", "Automatic (default)"), tr("毎回選ぶ", "Ask every time")});
        switchChoice(ModSettings.SWITCH_ALL_TAB_TARGET,
                tr("「すべて」タブから開くフォルダ", "Folder to open from the All tab"),
                new String[]{"default", "last", "latest", "root"}, new String[]{
                    tr("カテゴリの既定フォルダ（既定）", "Category's default folder (default)"),
                    tr("最後に開いていたフォルダ", "Last opened folder"),
                    tr("そのカテゴリで最新のファイルがあるフォルダ", "Folder with the newest file"),
                    tr("内部ストレージのルート", "Internal storage root")
                });
    }

    private void switchChoice(String key, String title, String[] values, String[] labels) {
        Supplier<Integer> selected = () -> Math.max(0, java.util.Arrays.asList(values).indexOf(
                key.equals(ModSettings.SWITCH_TO_MEDIA_CATEGORY)
                        ? ModSettings.switchToMediaCategory() : ModSettings.switchAllTabTarget()));
        Object pref = preference(title, labels[selected.get()]);
        bridge.setKey(pref, key);
        bridge.setClick(pref, listener(bridge.clickType(), () -> choices(title, labels, selected.get(), which -> {
            ModSettings.setSwitchChoice(key, values[which]);
            bridge.setSummary(pref, labels[which]);
        })));
        bridge.addTo(screen, pref);
    }

    private Object listener(Class<?> type, Runnable action) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            try {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return "FilesPlus preference listener";
                }
                action.run();
            } catch (Throwable t) { hookError(t); }
            return null;
        });
    }

    private void toggle(Object group, String title, String summary, String key, boolean fallback) {
        Object pref = bridge.newSwitch(context);
        bridge.prepare(pref);
        bridge.setKey(pref, key);
        bridge.setTitle(pref, title);
        if (summary != null) bridge.setSummary(pref, summary);
        bridge.setChecked(pref, ModSettings.getBool(key, fallback));
        Class<?> type = bridge.changeType();
        Object listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            try {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return "FilesPlus switch listener";
                }
                // The app's change dispatch never applies the value itself (it always reports "not accepted"),
                // so the switch is updated here, the same way the app's own listeners do.
                boolean checked = (Boolean) args[1];
                ModSettings.putBool(key, checked);
                bridge.setChecked(pref, checked);
            } catch (Throwable t) { hookError(t); }
            return null;
        });
        bridge.setChange(pref, listener);
        bridge.addTo(group, pref);
    }

    private void skip(String title, String key) {
        Object pref = preference(title, seconds(skipValue(key)));
        bridge.setKey(pref, key);
        bridge.setClick(pref, listener(bridge.clickType(), () -> safely(() -> {
            int current = skipValue(key);
            int selected = current == 3 ? 0 : current == 5 ? 1 : current == 10 ? 2 : 3;
            choices(title, new String[]{seconds(3), seconds(5), seconds(10),
                    tr("カスタム（1〜600秒）", "Custom (1–600 seconds)")}, selected, which -> {
                if (which == 3) input(title, String.valueOf(current), InputType.TYPE_CLASS_NUMBER, value -> {
                    int number;
                    try { number = Integer.parseInt(value.trim()); } catch (NumberFormatException e) { number = 0; }
                    if (number < 1 || number > 600)
                        return tr("1〜600の整数を入力してください", "Enter a whole number from 1 to 600");
                    ModSettings.putInt(key, number);
                    bridge.setSummary(pref, seconds(number));
                    return null;
                });
                else {
                    int value = new int[]{3, 5, 10}[which];
                    ModSettings.putInt(key, value);
                    bridge.setSummary(pref, seconds(value));
                }
            });
        })));
        bridge.addTo(screen, pref);
    }

    private interface SaveInput { String save(String value); }
    private interface Choose { void select(int index); }

    /** The settings patch replaces this stub with the native Material single-choice builder. */
    public static Dialog nativeChoices(Context context, CharSequence title, CharSequence[] items, int selected,
            android.content.DialogInterface.OnClickListener listener) {
        throw new IllegalStateException("Native single-choice dialog is unavailable");
    }

    private void choices(String title, String[] items, int selected, Choose action) {
        Dialog dialog = bridge.choices(activity, title, items, selected, (d, index) -> safely(() -> {
            d.dismiss();
            action.select(index);
        }));
        dialog.show();
    }

    /** The original new-folder layout supplies both TextInputLayout and TextInputEditText. */
    private void input(String title, String value, int type, SaveInput save) {
        View view = LayoutInflater.from(activity).inflate(resource("new_folder_content", "layout"), null);
        EditText edit = view.findViewById(resource("folder_name_edit_text", "id"));
        if (edit == null) throw new IllegalStateException("Original input missing");
        // setSingleLine replaces the transformation method, so it runs before setInputType; the layout's
        // "new folder" hint is replaced by the field's own title.
        edit.setSingleLine(true);
        edit.setInputType(type);
        edit.setHint(title);
        if ((type & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD)
            edit.setTransformationMethod(PasswordTransformationMethod.getInstance());
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        edit.setText(value);
        edit.selectAll();
        Dialog dialog = bridge.dialog(activity, title, view, tr("保存", "Save"), tr("キャンセル", "Cancel"));
        dialog.show();
        View positive = dialog.findViewById(android.R.id.button1);
        if (positive == null) { dialog.dismiss(); throw new IllegalStateException("Original positive button missing"); }
        positive.setOnClickListener(v -> safely(() -> {
            String error = save.save(edit.getText().toString());
            if (error == null) dialog.dismiss();
            else edit.setError(error);
        }));
    }

    private int resource(String name, String kind) {
        int id = activity.getResources().getIdentifier(name, kind, activity.getPackageName());
        if (id == 0) throw new IllegalStateException("Missing original resource " + kind + "/" + name);
        return id;
    }

    private void report(String title, String text, boolean share) {
        TextView body = new TextView(activity);
        body.setText(text);
        body.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        Dialog dialog = bridge.dialog(activity, title, scroll, tr("操作", "Actions"), tr("閉じる", "Close"));
        dialog.show();
        View button = dialog.findViewById(android.R.id.button1);
        if (button == null) { dialog.dismiss(); throw new IllegalStateException("Original report button missing"); }
        button.setOnClickListener(v -> safely(() ->
                choices(title, new String[]{tr("コピー", "Copy"), tr("共有", "Share")}, -1, index -> {
                    if (index == 0) copy(title, text);
                    else share(title, text);
                })));
    }

    private void copy(String title, String text) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(title, text));
        toast(tr("コピーしました", "Copied"));
    }

    private void share(String title, String text) {
        activity.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), tr("共有", "Share")));
    }

    private void diagnostics() {
        String crash = app.filesplus.extension.CrashLog.last();
        if (crash != null) {
            String firstLine = crash.split("\n", 2)[0];
            row(screen, tr("前回のクラッシュ", "Last crash"), firstLine,
                    () -> report(tr("前回のクラッシュ", "Last crash"), crash, true));
            row(screen, tr("クラッシュの記録をコピー", "Copy crash record"), null, () -> copy("Last crash", crash));
            row(screen, tr("クラッシュの記録を消す", "Dismiss crash record"), null, () -> {
                app.filesplus.extension.CrashLog.clear();
                activity.getWindow().getDecorView().post(activity::recreate);
            });
        }
        row(screen, tr("クリップボードのフック", "Clipboard hooks"),
                app.filesplus.extension.clip.ClipHooks.installedHooks(), null);
        row(screen, tr("プレビューの長押しフック", "Preview long-press hook"),
                PreviewHooks.longPressInstalled() ? tr("適用済み", "Installed") : tr("未適用", "Not installed"),
                null);
        row(screen, tr("表示の切り替えのフック", "Switch view hooks"),
                ViewSwitch.hookStatus(japanese), null);
        row(screen, tr("圧縮ファイルのフック", "Archives filter hooks"),
                app.filesplus.extension.browse.ArchiveFilter.installed()
                        ? tr("適用済み", "Installed") : tr("未適用", "Not installed"), null);
        boolean smbRoots = app.filesplus.extension.smb.SmbRoots.installed();
        row(screen, tr("SMB のストレージブラウザフック", "SMB storage browser hooks"),
                smbRoots ? tr("適用済み", "Installed") : tr("未適用", "Not installed"), null);
        if (smbRoots) {
            row(screen, tr("SMB をストレージブラウザで開く", "Open SMB in the storage browser"),
                    null, () -> app.filesplus.extension.smb.SmbRoots.openFirst(activity));
            row(screen, tr("壊れた SMB 引数を試す", "Try a broken SMB argument"), null, () -> {
                boolean refused = app.filesplus.extension.smb.SmbRoots.selfTestBrokenRoot(activity);
                DiagLog.i("smb", "Broken SMB argument refused: " + refused);
                toast(refused ? tr("成功: 起動を拒否", "PASS: refused") : tr("失敗: 起動を拒否せず", "FAIL: not refused"));
            });
        }
        deviceReport = deviceReport();
        row(screen, tr("端末情報", "Device information"), deviceReport, () -> report(pageTitle(), deviceReport, true));
        Object codecs = row(screen, tr("動画デコーダー", "Video decoders"),
                tr("動画デコーダーを確認中…", "Checking video decoders…"),
                () -> report(tr("動画デコーダー", "Video decoders"), decoderReport, true));
        row(screen, tr("SMB テスト", "SMB test"),
                tr("接続・一覧・先頭 1 KB の読み取りを確かめます", "Checks connect, listing and a 1 KB read"),
                () -> activity.startActivity(intent(activity, "smb").putExtra("filesplus_smb_test", true)));
        addOriginalScreenTests();
        row(screen, tr("貼り付けの判定テスト", "Paste rules test"),
                tr("一時フォルダで、クリップボードと貼り付けの規則を確かめます",
                        "Checks the clipboard and the paste rules in a temporary folder"),
                this::runPasteTest);
        Context app = activity.getApplicationContext();
        List<Clipboard.Interrupted> interrupted = Clipboard.interrupted(app);
        if (!interrupted.isEmpty()) {
            row(screen, tr("貼り付けの中断記録", "Interrupted pastes"),
                    InterruptedPastes.summary(interrupted, japanese), () -> {
                        new Thread(() -> {
                            String result;
                            try { result = InterruptedPastes.report(Clipboard.interrupted(app), japanese); }
                            catch (Throwable t) {
                                hookError(t);
                                result = tr("中断記録を取得できません。", "Interrupted paste records unavailable.");
                            }
                            String text = result;
                            activity.runOnUiThread(() -> safely(() -> {
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                report(tr("貼り付けの中断記録", "Interrupted pastes"), text, true);
                            }));
                        }, "ModKit-interrupted-pastes").start();
                    });
            row(screen, tr("中断記録を消す", "Dismiss"), null, () -> {
                Clipboard.dismissInterrupted(app);
                activity.getWindow().getDecorView().post(activity::recreate);
            });
        }
        row(screen, tr("テキストで共有", "Share as text"), null, () ->
                share(pageTitle(), deviceReport + "\n\n" + decoderReport + "\n\n" + logReport()
                        + (crash == null ? "" : "\n\nLast crash:\n"
                                + (crash.length() > 12_000 ? crash.substring(0, 12_000) + "\n…" : crash))));
        row(screen, tr("ログを消去", "Clear log"), null, DiagLog::clear);
        row(screen, tr("ログ", "Log"), tr("タップで表示（新しい順）", "Tap to show (newest first)"),
                () -> report(tr("診断ログ", "Diagnostic log"), logReport(), true));
        row(screen, tr("ログをコピー", "Copy log"), null, () -> copy("Diagnostic log", logReport()));
        new Thread(() -> {
            String result;
            try { result = decoderReport(); }
            catch (Throwable t) { hookError(t); result = tr("動画デコーダー情報を取得できません。", "Video decoder information unavailable."); }
            String text = result;
            activity.runOnUiThread(() -> safely(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                decoderReport = text;
                bridge.setSummary(codecs, text);
            }));
        }, "ModKit-diagnostics").start();
    }

    /** Runs the clipboard / paste rules self test off the UI thread and shows the PASS / FAIL lines in a dialog. */
    private void runPasteTest() {
        toast(tr("テスト中…", "Testing…"));
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            String result;
            try { result = PasteSelfTest.runDevice(app); }
            catch (Throwable t) { hookError(t); result = "FAIL unexpected exception: " + t; }
            String text = result;
            DiagLog.i("clip", "Paste rules test:\n" + text);
            activity.runOnUiThread(() -> safely(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                report(tr("貼り付けの判定テスト", "Paste rules test"), text, true);
            }));
        }, "ModKit-paste-test").start();
    }

    private void addOriginalScreenTests() {
        row(screen, tr("DCIM をストレージブラウザで開く（2 段）", "Open DCIM in storage browser (2-level stack)"),
                null, () -> OriginalScreens.openStorage(activity, OriginalScreens.stackFor(activity,
                        new File(Environment.getExternalStorageDirectory(), "DCIM")), false));
        row(screen, tr("画像を Camera タブでメディアブラウザに開く", "Open Images → Camera tab in media browser"),
                null, () -> OriginalScreens.openMedia(activity, Category.IMAGES, "Camera", false));
        row(screen, tr("フォルダを選ぶ（選んだパスを表示）", "Pick a folder (shows the picked path in a Toast)"),
                null, () -> OriginalScreens.pickFolder(activity, REQUEST_PICK_FOLDER));
        row(screen, tr("壊れた引数を試す（開かず、Toast が出るはず）", "Try a broken param (must not launch, must toast)"),
                null, () -> {
                    boolean refused = OriginalScreens.selfTestBrokenParam(activity);
                    DiagLog.i("screens", "Broken param refused: " + refused);
                    toast(refused ? tr("成功: 起動を拒否", "PASS: refused") : tr("失敗: 起動を拒否せず", "FAIL: not refused"));
                });
        row(screen, "ScreenArgs.deviceSelfTest()", null, () -> {
            String result = ScreenArgs.deviceSelfTest() + "\n" + ScreenArgs.selfTest();
            DiagLog.i("screens", result);
            report("ScreenArgs", result, true);
        });
    }

    /** SMB has existing controls, so it is a populated page, not a placeholder. */
    private void smb() {
        if (activity.getIntent().getBooleanExtra("filesplus_smb_test", false)) {
            editConnection(null, true);
            return;
        }
        String id = activity.getIntent().getStringExtra("filesplus_connection");
        if (id != null) {
            SmbConnection connection = "new".equals(id) ? null : SmbConnectionStore.get(id);
            if (!"new".equals(id) && connection == null) throw new IllegalStateException("Connection no longer exists");
            editConnection(connection, false);
            return;
        }
        // The editor returns here when it finishes; onActivityResult then rebuilds this list.
        for (SmbConnection connection : SmbConnectionStore.all()) {
            row(screen, connection.label(), smbSummary(connection), () -> activity.startActivityForResult(
                    intent(activity, "smb").putExtra("filesplus_connection", connection.id), REQUEST_SMB_EDIT));
        }
        row(screen, tr("接続先を追加", "Add a connection"), null, () -> activity.startActivityForResult(
                intent(activity, "smb").putExtra("filesplus_connection", "new"), REQUEST_SMB_EDIT));
    }

    private static final class ConnectionForm {
        String name = "", host = "", port = "445", share = "", start = "", user = "", password = "", domain = "";
        boolean openStart;
        boolean busy;
    }

    private interface FieldValue { void set(String value); }

    private void field(String title, Supplier<String> value, int type, FieldValue save, boolean secret) {
        Object pref = preference(title, secret ? null : value.get());
        // The form is read again at click time, so the dialog never shows a value from before an earlier edit.
        bridge.setClick(pref, listener(bridge.clickType(), () -> safely(() -> input(title, value.get(), type, typed -> {
            save.set(typed);
            if (!secret) bridge.setSummary(pref, typed);
            return null;
        }))));
        bridge.addTo(screen, pref);
    }

    private void editConnection(SmbConnection existing, boolean testOnly) {
        ConnectionForm form = new ConnectionForm();
        if (existing != null) {
            form.name = existing.displayName; form.host = existing.host; form.port = String.valueOf(existing.port);
            form.share = existing.share; form.start = existing.startPath; form.user = existing.user;
            form.domain = existing.domain; form.openStart = SmbConnection.OPEN_START.equals(existing.openWhere);
        }
        int text = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        field(tr("ホスト", "Host"), () -> form.host, text | InputType.TYPE_TEXT_VARIATION_URI, v -> form.host = v, false);
        field(tr("ユーザー（空欄でゲスト）", "User (blank for guest)"), () -> form.user, text, v -> form.user = v, false);
        String passwordTitle = existing != null && SmbConnectionStore.hasPassword(existing.id)
                ? tr("パスワード（空欄で変更しない）", "Password (blank keeps it)") : tr("パスワード", "Password");
        field(passwordTitle, () -> "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                v -> form.password = v, true);
        field(tr("表示名（任意）", "Display name (optional)"), () -> form.name, text, v -> form.name = v, false);
        field(tr("ポート", "Port"), () -> form.port, InputType.TYPE_CLASS_NUMBER, v -> form.port = v, false);
        field(tr("共有名（空欄なら共有の一覧）", "Share name (blank lists shares)"), () -> form.share, text,
                v -> form.share = v, false);
        field(tr("開始フォルダ（共有名が空なら 共有名/フォルダ）", "Start folder (share/folder when no share name)"),
                () -> form.start, text, v -> form.start = v, false);
        field(tr("ドメイン（任意）", "Domain (optional)"), () -> form.domain, text, v -> form.domain = v, false);
        Object open = row(screen, tr("開く場所", "Open at"),
                form.openStart ? tr("開始フォルダ", "Start folder") : tr("前回開いていたフォルダ", "Last folder"), null);
        bridge.setClick(open, listener(bridge.clickType(), () -> safely(() -> choices(tr("開く場所", "Open at"),
                new String[]{tr("開始フォルダ", "Start folder"), tr("前回開いていたフォルダ", "Last folder")},
                form.openStart ? 0 : 1, selected -> {
                    form.openStart = selected == 0;
                    bridge.setSummary(open, form.openStart ? tr("開始フォルダ", "Start folder") : tr("前回開いていたフォルダ", "Last folder"));
                }))));
        Object status = row(screen, tr("状態", "Status"), "", null);
        if (!testOnly) row(screen, tr("保存", "Save"), null, () -> saveConnection(form, existing, status));
        row(screen, tr("接続テスト", "Connection test"), null, () -> testConnection(form, existing, status, testOnly));
        if (existing != null) {
            row(screen, tr("名前の変更", "Rename"), null, () ->
                    input(tr("名前の変更", "Rename"), form.name, text, value -> {
                        SmbConnectionStore.setDisplayName(existing.id, value);
                        activity.finish();
                        return null;
                    }));
            row(screen, tr("削除", "Delete"), null, () -> {
                Dialog dialog = bridge.dialog(activity, tr("接続先の削除", "Delete connection"),
                        reportView(tr("「" + existing.label() + "」を削除します。保存したパスワードも消えます。",
                                "Delete \"" + existing.label() + "\"? The saved password is removed too.")),
                        tr("削除", "Delete"), tr("キャンセル", "Cancel"));
                dialog.show();
                View button = dialog.findViewById(android.R.id.button1);
                if (button == null) { dialog.dismiss(); throw new IllegalStateException("Delete button missing"); }
                button.setOnClickListener(v -> safely(() -> {
                    SmbConnectionStore.delete(existing.id); dialog.dismiss(); activity.finish();
                }));
            });
        }
    }

    private SmbConnection readConnection(ConnectionForm form, SmbConnection existing) {
        int port;
        try { port = Integer.parseInt(form.port.trim()); } catch (NumberFormatException e) { port = 0; }
        if (form.host.trim().isEmpty()) throw new IllegalArgumentException(tr("ホストを入力してください", "Enter a host"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException(tr("ポートは1〜65535です", "Port must be 1–65535"));
        if (form.share.indexOf('/') >= 0 || form.share.indexOf('\\') >= 0)
            throw new IllegalArgumentException(tr("共有名に / や \\ は使えません", "No / or \\ in a share name"));
        String start = SmbPath.normalize(form.start);
        SmbConnection result = new SmbConnection(existing == null ? null : existing.id, form.name, form.host, port,
                form.share, start, form.user, form.domain,
                form.openStart ? SmbConnection.OPEN_START : SmbConnection.OPEN_LAST, existing == null ? "" : existing.lastPath);
        if (existing != null && !result.user.isEmpty() && form.password.isEmpty()
                && !result.credentialKey().equals(existing.credentialKey()))
            throw new IllegalArgumentException(tr("ホスト・ユーザーを変えたときはパスワードを入力してください",
                    "Enter the password when the host or user changes"));
        return result;
    }

    private void saveConnection(ConnectionForm form, SmbConnection existing, Object status) {
        if (form.busy) return;
        SmbConnection connection = readConnection(form, existing);
        String password = connection.user.isEmpty() ? "" : form.password.isEmpty() ? null : form.password;
        form.busy = true;
        bridge.setSummary(status, tr("保存中…", "Saving…"));
        new Thread(() -> {
            boolean saved = SmbConnectionStore.save(connection, password);
            activity.runOnUiThread(() -> safely(() -> {
                form.busy = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (saved) activity.finish();
                else bridge.setSummary(status, tr("保存できませんでした。保存データとパスワードを確認してください",
                        "Could not save. Check saved data and password."));
            }));
        }, "ModKit-smb-save").start();
    }

    private void testConnection(ConnectionForm form, SmbConnection existing, Object status, boolean fullTest) {
        if (form.busy) return;
        SmbConnection connection = readConnection(form, existing);
        String password = form.password;
        form.busy = true;
        bridge.setSummary(status, tr("接続中… 最大で1分ほどかかります", "Connecting… this may take up to a minute"));
        new Thread(() -> {
            String result;
            try {
                result = fullTest ? SmbTester.run(connection.host, connection.port, connection.user, password,
                        connection.domain, connection.share, connection.startPath)
                        : runSmbConnectionTest(connection, password, existing);
            } catch (Throwable t) { result = smbErrorText(t); }
            String report = result;
            activity.runOnUiThread(() -> safely(() -> {
                form.busy = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                bridge.setSummary(status, report);
                report(tr("SMB テストの結果", "SMB test result"), report, true);
            }));
        }, "ModKit-smb-test").start();
    }

    private View reportView(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextIsSelectable(true);
        return view;
    }

    private int skipValue(String key) {
        return ModSettings.SKIP_DOUBLE_TAP_SEC.equals(key) ? ModSettings.skipDoubleTapSec() : ModSettings.skipButtonSec();
    }

    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    private String seconds(int number) { return number + tr("秒", " seconds"); }
    private String tr(String ja, String en) { return japanese ? ja : en; }
    private String yesNo(boolean value) { return value ? tr("はい", "Yes") : tr("いいえ", "No"); }
    private void safely(Runnable action) {
        try { action.run(); }
        catch (Throwable t) {
            hookError(t);
            toast(t instanceof IllegalArgumentException ? t.getMessage()
                    : tr("操作に失敗しました。診断ログを確認してください。", "Action failed. See diagnostic log."));
        }
    }
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

    private String deviceReport() {
        String version;
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            version = info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable t) {
            DiagLog.e("diagnostics", "Cannot read app version", t);
            version = tr("取得不可", "Unavailable");
        }
        return tr("メーカー / モデル: ", "Manufacturer / model: ") + Build.MANUFACTURER + " / " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                + "\n" + tr("対応 ABI: ", "Supported ABIs: ") + String.join(", ", Build.SUPPORTED_ABIS)
                + "\n" + tr("アプリの ABI: ", "App ABI: ") + appAbi()
                + "\n" + tr("アプリ: ", "App: ") + activity.getPackageName() + "\n" + tr("バージョン: ", "Version: ") + version;
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

    private String appAbi() {
        try {
            String dir = activity.getApplicationInfo().nativeLibraryDir;
            return (android.os.Process.is64Bit() ? "64bit" : "32bit")
                    + (dir == null ? "" : " (" + dir.substring(dir.lastIndexOf('/') + 1) + ")");
        } catch (Throwable t) {
            DiagLog.e("diagnostics", "Cannot read app ABI", t);
            return "?";
        }
    }

}
