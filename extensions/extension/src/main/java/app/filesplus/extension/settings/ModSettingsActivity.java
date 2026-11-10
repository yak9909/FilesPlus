package app.filesplus.extension.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ModKit;
import app.filesplus.extension.ModSettings;
import java.util.Collections;
import java.util.List;

/** Resource-free settings UI using the host activity theme and SDK widgets. */
public final class ModSettingsActivity extends Activity {
    private boolean japanese;
    private int primary;
    private int secondary;
    private int accent;
    private LinearLayout sections;
    private TextView logView;
    private String deviceReport = "";
    private String decoderReport = "";
    private Button shareButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            ModKit.init(this);
            japanese = SettingsHooks.isJapanese(this);
            setTitle(SettingsHooks.entryTitle(this));
            primary = themeColor("textColorPrimary", android.R.attr.textColorPrimary, Color.DKGRAY);
            secondary = themeColor("textColorSecondary", android.R.attr.textColorSecondary, Color.GRAY);
            accent = themeColor("colorPrimary", android.R.attr.colorAccent, primary);
            int surface = themeColor("colorSurface", android.R.attr.colorBackground, Color.WHITE);

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(surface);
            setContentView(root);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
            LinearLayout toolbar = new LinearLayout(this);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            Button back = new Button(this);
            back.setText("‹");
            back.setTextSize(28);
            back.setContentDescription(tr("戻る", "Back"));
            back.setOnClickListener(v -> finish());
            toolbar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView title = text(SettingsHooks.entryTitle(this), 22, primary);
            toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(64), 1));
            root.addView(toolbar);
            ScrollView scroll = new ScrollView(this);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            sections = new LinearLayout(this);
            sections.setOrientation(LinearLayout.VERTICAL);
            sections.setPadding(0, 0, 0, dp(24));
            scroll.addView(sections);
            addSkipSection();
            addDiagnosticsSection();
        } catch (Throwable t) {
            DiagLog.e("settings", "Cannot build settings screen", t);
            TextView error = text(tr("設定を開けませんでした。", "Could not open settings."), 16, primary);
            setContentView(error);
        }
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
    }

    private void addDiagnosticsSection() {
        header(tr("診断", "Diagnostics"));
        deviceReport = deviceReport();
        details(deviceReport);
        TextView codecs = details(tr("動画デコーダーを確認中…", "Checking video decoders…"));
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(16), dp(8), dp(16), dp(8));
        shareButton = new Button(this);
        shareButton.setText(tr("テキストで共有", "Share as text"));
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> safely(this::shareDiagnostics));
        actions.addView(shareButton, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = new Button(this);
        clear.setText(tr("ログを消去", "Clear log"));
        clear.setOnClickListener(v -> safely(() -> {
            DiagLog.clear();
            refreshLog();
        }));
        actions.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        sections.addView(actions);
        TextView logTitle = details(tr("診断ログ（新しい順）", "Diagnostic log (newest first)"));
        logTitle.setTypeface(null, Typeface.BOLD);
        logView = details("");
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
                shareButton.setEnabled(true);
                refreshLog();
            }));
        }, "ModKit-diagnostics").start();
    }

    private void skipRow(String title, String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(16), dp(24), dp(16));
        row.setMinimumHeight(dp(72));
        TypedValue background = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true)) {
            row.setBackgroundResource(background.resourceId);
        }
        row.addView(text(title, 16, primary));
        TextView value = text(seconds(skipValue(key)), 14, secondary);
        row.addView(value);
        row.setFocusable(true);
        row.setOnClickListener(v -> safely(() -> chooseSkip(title, key, value)));
        sections.addView(row);
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
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(choices, selected, (dialog, which) -> safely(() -> {
                    dialog.dismiss();
                    if (which == 3) customSkip(title, key, value);
                    else saveSkip(key, presets[which], value);
                }))
                .setNegativeButton(tr("キャンセル", "Cancel"), null).show();
    }

    private void customSkip(String title, String key, TextView value) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(skipValue(key)));
        input.selectAll();
        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(24), dp(8), dp(24), 0);
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
        if (logView != null) logView.setText(logReport());
    }

    private void shareDiagnostics() {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, SettingsHooks.entryTitle(this))
                .putExtra(Intent.EXTRA_TEXT, deviceReport + "\n\n" + decoderReport + "\n\n"
                        + tr("診断ログ（新しい順）", "Diagnostic log (newest first)") + "\n" + logReport());
        startActivity(Intent.createChooser(intent, tr("診断情報を共有", "Share diagnostics")));
    }

    private void header(String title) {
        TextView header = text(title, 14, accent);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(dp(24), dp(24), dp(24), dp(8));
        header.setAccessibilityHeading(true);
        sections.addView(header);
    }

    private TextView details(String content) {
        TextView view = text(content, 14, secondary);
        view.setPadding(dp(24), dp(12), dp(24), dp(12));
        view.setTextIsSelectable(true);
        sections.addView(view);
        return view;
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
