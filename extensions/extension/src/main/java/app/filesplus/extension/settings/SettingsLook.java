package app.filesplus.extension.settings;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import app.filesplus.extension.DiagLog;
import app.filesplus.extension.ui.FilesLook;

/**
 * Builds settings widgets from the host app's own resources (looked up by name, never by id) so the Mod settings
 * screen looks like the original Files settings. Every builder falls back to a plain programmatic widget when a
 * resource is missing or inflation fails.
 */
final class SettingsLook {
    /** One settings row. {@code toggle} is only set for switch rows. */
    static final class Row {
        View view;
        TextView title;
        TextView summary;
        CompoundButton toggle;

        void setSummary(CharSequence text) {
            summary.setText(text);
            summary.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }

        void setEnabledLook(boolean enabled) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    private final Activity activity;
    private boolean rowSourceLogged;
    /** Failures are logged once per kind: the builders run for every row on every open. */
    private final java.util.HashSet<String> failuresLogged = new java.util.HashSet<>();
    private final int primary;
    private final int secondary;
    private final int accent;

    SettingsLook(Activity activity, int primary, int secondary, int accent) {
        this.activity = activity;
        this.primary = primary;
        this.secondary = secondary;
        this.accent = accent;
    }

    /** The theme the original settings activity applies (preference theme and list text appearances), or 0. */
    static int themeId(Context context) {
        return FilesLook.style(context, "PreferencesTheme");
    }

    private void logFailureOnce(String kind, String message, Throwable t) {
        if (failuresLogged.add(kind)) DiagLog.e("settings", message, t);
    }

    private int res(String name, String type) {
        return FilesLook.resource(activity, name, type);
    }

    private int dp(int value) {
        return FilesLook.dp(activity, value);
    }

    private int attrResource(String attrName) {
        return FilesLook.attrResource(activity, attrName);
    }

    private View inflate(String layoutName, ViewGroup parent) {
        int layout = res(layoutName, "layout");
        if (layout == 0) return null;
        return LayoutInflater.from(activity).inflate(layout, parent, false);
    }

    // ---- rows -------------------------------------------------------------------------------------------------

    /** A preference row: title, optional summary, nothing on the right. */
    Row row(ViewGroup parent, CharSequence title, CharSequence summary) {
        return build(parent, title, summary, false);
    }

    /** A preference row with the app's Material switch as its widget. */
    Row switchRow(ViewGroup parent, CharSequence title, CharSequence summary, boolean checked) {
        Row row = build(parent, title, summary, true);
        row.toggle.setChecked(checked);
        View view = row.view;
        CompoundButton toggle = row.toggle;
        view.setOnClickListener(v -> toggle.toggle());
        view.setClickable(true);
        // The row is the single accessibility target and reports the checked state; the switch is only a visual.
        toggle.setClickable(false);
        toggle.setFocusable(false);
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(toggle.isChecked());
            }
        });
        return row;
    }

    private Row build(ViewGroup parent, CharSequence title, CharSequence summary, boolean withSwitch) {
        Row row = null;
        try {
            row = inflateRow(parent, withSwitch);
        } catch (Throwable t) {
            logFailureOnce("row", "Cannot inflate the original preference row", t);
        }
        if (!rowSourceLogged) {
            rowSourceLogged = true;
            DiagLog.i("settings", row == null ? "Settings rows: fallback look (preference_material unavailable)"
                    : "Settings rows: original preference_material layout");
        }
        if (row == null) row = fallbackRow(parent, withSwitch);
        if (TextUtils.isEmpty(title)) {
            row.title.setVisibility(View.GONE);
        } else {
            row.title.setText(title);
        }
        row.setSummary(summary);
        // Rows are rebuilt from the saved settings on every start; never restore view state into them.
        row.view.setSaveFromParentEnabled(false);
        parent.addView(row.view);
        return row;
    }

    private Row inflateRow(ViewGroup parent, boolean withSwitch) {
        View view = inflate("preference_material", parent);
        if (view == null) return null;
        TextView title = view.findViewById(android.R.id.title);
        TextView summary = view.findViewById(android.R.id.summary);
        View widgetFrame = view.findViewById(android.R.id.widget_frame);
        if (title == null || summary == null || widgetFrame == null) return null;
        Row row = new Row();
        row.view = view;
        row.title = title;
        row.summary = summary;
        // Like androidx Preference with singleLineTitle=false.
        title.setSingleLine(false);
        title.setMaxLines(10);
        title.setEllipsize(null);
        summary.setMaxLines(Integer.MAX_VALUE);
        int iconFrame = res("icon_frame", "id");
        View icon = iconFrame == 0 ? null : view.findViewById(iconFrame);
        if (icon != null) icon.setVisibility(View.GONE);
        if (withSwitch) {
            row.toggle = inflateSwitch((ViewGroup) widgetFrame);
            ((ViewGroup) widgetFrame).addView(row.toggle);
            widgetFrame.setVisibility(View.VISIBLE);
        } else {
            widgetFrame.setVisibility(View.GONE);
        }
        return row;
    }

    private CompoundButton inflateSwitch(ViewGroup frame) {
        CompoundButton toggle = null;
        try {
            View widget = inflate("material_switch_widget", frame);
            if (widget instanceof CompoundButton) toggle = (CompoundButton) widget;
        } catch (Throwable t) {
            logFailureOnce("switch", "Cannot inflate the original switch", t);
        }
        if (toggle == null) toggle = new Switch(activity);
        // Every inflated switch shares @id/material_switch, so restored view state would copy one switch's value
        // into all of them (and fire their listeners).
        toggle.setSaveEnabled(false);
        return toggle;
    }

    /** The old programmatic look, used when the app's preference layout cannot be inflated. */
    private Row fallbackRow(ViewGroup parent, boolean withSwitch) {
        LinearLayout view = new LinearLayout(activity);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(24), dp(12), dp(24), dp(12));
        view.setMinimumHeight(dp(64));
        TypedValue background = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true)) {
            view.setBackgroundResource(background.resourceId);
        }
        view.setFocusable(true);
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        view.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Row row = new Row();
        row.view = view;
        row.title = plainText(16, primary);
        row.summary = plainText(14, secondary);
        texts.addView(row.title);
        texts.addView(row.summary);
        if (withSwitch) {
            row.toggle = new Switch(activity);
            row.toggle.setSaveEnabled(false);
            view.addView(row.toggle);
        }
        return row;
    }

    private TextView plainText(int size, int color) {
        TextView view = new TextView(activity);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    // ---- section header and divider ---------------------------------------------------------------------------

    /** A category header in the style of the original settings. */
    TextView header(ViewGroup parent, CharSequence title) {
        TextView header = null;
        try {
            View view = inflate("settings_preference_category", parent);
            if (view instanceof TextView) header = (TextView) view;
        } catch (Throwable t) {
            logFailureOnce("header", "Cannot inflate the original category header", t);
        }
        if (header == null) {
            header = plainText(14, accent);
            header.setTypeface(null, Typeface.BOLD);
            header.setPadding(dp(24), dp(24), dp(24), dp(8));
        }
        header.setText(title);
        header.setAccessibilityHeading(true);
        parent.addView(header);
        return header;
    }

    /** The 1 dp divider the original settings draw between a section and the next header. */
    void divider(ViewGroup parent) {
        View divider = new View(activity);
        Drawable drawable = null;
        try {
            int id = res("preference_list_divider_material", "drawable");
            if (id != 0) drawable = activity.getDrawable(id);
        } catch (Throwable t) {
            logFailureOnce("divider", "Cannot load the original divider", t);
        }
        if (drawable == null) drawable = new ColorDrawable((primary & 0x00ffffff) | 0x1f000000);
        divider.setBackground(drawable);
        int height = Math.max(drawable.getIntrinsicHeight(), 1);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, height));
    }

    // ---- toolbar and shell ------------------------------------------------------------------------------------

    /**
     * Replaces the app's MaterialToolbar (an app-private class whose methods cannot be called from here) with a
     * row of the same height, the app's back arrow and the toolbar title text appearance.
     */
    View toolbar(CharSequence title, String backDescription, View.OnClickListener onBack) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int height = dp(56);
        TypedValue size = new TypedValue();
        int sizeAttr = res("actionBarSize", "attr");
        if (sizeAttr != 0 && activity.getTheme().resolveAttribute(sizeAttr, size, true)) {
            height = TypedValue.complexToDimensionPixelSize(size.data, activity.getResources().getDisplayMetrics());
        }
        Drawable icon = null;
        try {
            int id = attrResource("homeAsUpIndicator");
            if (id == 0) id = res("abc_ic_ab_back_material", "drawable");
            if (id != 0) icon = activity.getDrawable(id);
        } catch (Throwable t) {
            DiagLog.e("settings", "Cannot load the back arrow", t);
        }
        View back;
        if (icon != null) {
            ImageButton button = new ImageButton(activity);
            button.setImageDrawable(icon);
            back = button;
        } else {
            // No arrow drawable in this build: a text chevron keeps the button visible.
            TextView chevron = plainText(32, primary);
            chevron.setText("‹");
            chevron.setGravity(Gravity.CENTER);
            back = chevron;
        }
        TypedValue ripple = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
            back.setBackgroundResource(ripple.resourceId);
        } else {
            back.setBackground(null);
        }
        back.setContentDescription(backDescription);
        back.setOnClickListener(onBack);
        bar.addView(back, new LinearLayout.LayoutParams(dp(56), height));
        TextView text = new TextView(activity);
        text.setText(title);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        int appearance = attrResource("textAppearanceTitleLarge");
        if (appearance != 0) {
            text.setTextAppearance(appearance);
        } else {
            text.setTextSize(22);
            text.setTextColor(primary);
        }
        bar.addView(text, new LinearLayout.LayoutParams(0, height, 1));
        return bar;
    }

    /** Inflates the original settings activity layout (app bar over a content frame), or returns null. */
    View[] shell(FrameLayout root) {
        try {
            View main = inflate("activity_settings_v2", root);
            int appbarId = res("appbar_layout", "id");
            int toolbarId = res("toolbar", "id");
            int contentId = res("content", "id");
            if (main == null || appbarId == 0 || toolbarId == 0 || contentId == 0) return null;
            View appbar = main.findViewById(appbarId);
            View toolbar = main.findViewById(toolbarId);
            View content = main.findViewById(contentId);
            if (!(appbar instanceof ViewGroup) || toolbar == null || !(content instanceof FrameLayout)) return null;
            ((ViewGroup) appbar).removeView(toolbar);
            return new View[] {main, appbar, content};
        } catch (Throwable t) {
            DiagLog.e("settings", "Cannot inflate the original settings layout", t);
            return null;
        }
    }

    // ---- dialogs ----------------------------------------------------------------------------------------------

    /** Draws the Material 3 dialog surface behind the dialog (see {@link FilesLook#styleDialog}). */
    void styleDialog(Dialog dialog) {
        FilesLook.styleDialog(activity, dialog);
    }

}
