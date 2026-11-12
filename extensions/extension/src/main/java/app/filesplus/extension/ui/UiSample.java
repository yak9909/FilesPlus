package app.filesplus.extension.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.filesplus.extension.DiagLog;

/**
 * Diagnostics "UI sample": shows the app's own list parts that the unified Move browser will use, each built by
 * {@link FilesLook} from the app's layouts ("original") or, when that fails, by a look-alike built in code
 * ("fallback"). The result of every element is logged to DiagLog and printed under the element.
 */
public final class UiSample {
    /** Builds one element; returns null (or throws) when the app's layout cannot be used. */
    private interface Builder {
        View build() throws Exception;
    }

    private final Context context;
    private final LinearLayout column;
    private final int textPrimary;
    private final int textSecondary;

    private UiSample(Activity activity) {
        this.context = activity;
        this.textPrimary = FilesLook.themeColor(activity, "colorOnSurface", Color.DKGRAY);
        this.textSecondary = FilesLook.themeColor(activity, "colorOnSurfaceVariant", Color.GRAY);
        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = FilesLook.dp(activity, 8);
        column.setPadding(pad, pad, pad, pad);
    }

    /** Opens the sample in a dialog. */
    public static void show(Activity activity, boolean japanese) {
        UiSample sample = new UiSample(activity);
        sample.populate();
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(sample.column);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(japanese ? "UI 見本" : "UI sample")
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        FilesLook.styleDialog(activity, dialog);
        dialog.show();
    }

    private void populate() {
        element("folder_picker_list_item", this::folderPickerItem, this::folderPickerItemFallback);
        element("browse_capsule_item_view", this::capsuleItem, this::capsuleItemFallback);
        element("row_item_view (folder)", () -> rowItem(true), () -> rowItemFallback(true));
        element("row_item_view (thumbnail)", () -> rowItem(false), () -> rowItemFallback(false));
        element("square_item_view x3", this::squareStrip, this::squareStripFallback);
        element("button_section", this::buttonSection, this::buttonSectionFallback);
        element("no_files_blob", this::noFiles, this::noFilesFallback);
        element("file_filter_container", this::filterChips, this::filterChipsFallback);
    }

    /** Builds the original element, else the fallback; logs and prints which one it was. */
    private void element(String name, Builder original, Builder fallback) {
        View view = null;
        boolean isOriginal = false;
        try {
            view = original.build();
            isOriginal = view != null;
        } catch (Throwable t) {
            DiagLog.e("ui", "UI sample " + name + ": original failed", t);
        }
        if (view == null) {
            try {
                view = fallback.build();
            } catch (Throwable t) {
                DiagLog.e("ui", "UI sample " + name + ": fallback failed", t);
            }
        }
        String result = isOriginal ? "original" : view != null ? "fallback" : "failed";
        DiagLog.i("ui", "UI sample " + name + ": " + result);
        TextView caption = new TextView(context);
        caption.setText(name + " - " + result);
        caption.setTextSize(11);
        caption.setTextColor(textSecondary);
        caption.setPadding(FilesLook.dp(context, 8), FilesLook.dp(context, 12), 0, FilesLook.dp(context, 2));
        column.addView(caption);
        if (view != null) column.addView(view);
    }

    // ---- folder_picker_list_item ------------------------------------------------------------------------------

    private View folderPickerItem() {
        View view = FilesLook.inflate(context, "folder_picker_list_item", column);
        if (view == null) return null;
        ImageView icon = view.findViewById(FilesLook.id(context, "preview_image"));
        TextView title = view.findViewById(FilesLook.id(context, "title"));
        if (icon == null || title == null) return null;
        icon.setImageDrawable(FilesLook.drawable(context, "gs_folder_fill1_vd_theme_24"));
        title.setText("Camera");
        return view;
    }

    private View folderPickerItemFallback() {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(FilesLook.dp(context, 24), FilesLook.dp(context, 8), FilesLook.dp(context, 24), 0);
        ImageView icon = new ImageView(context);
        icon.setImageDrawable(FilesLook.drawable(context, "gs_folder_fill1_vd_theme_24"));
        row.addView(icon, new LinearLayout.LayoutParams(FilesLook.dp(context, 32), FilesLook.dp(context, 32)));
        TextView title = styledText("BottomSheetListItem", 14, textPrimary);
        title.setText("Camera");
        title.setPadding(FilesLook.dp(context, 16), FilesLook.dp(context, 16), FilesLook.dp(context, 16),
                FilesLook.dp(context, 16));
        row.addView(title);
        return row;
    }

    // ---- browse_capsule_item_view -----------------------------------------------------------------------------

    private View capsuleItem() {
        View view = FilesLook.inflate(context, "browse_capsule_item_view", column);
        if (view == null) return null;
        ImageView icon = view.findViewById(FilesLook.id(context, "icon"));
        TextView title = view.findViewById(FilesLook.id(context, "title"));
        TextView subtitle = view.findViewById(FilesLook.id(context, "subtitle"));
        if (icon == null || title == null || subtitle == null) return null;
        icon.setImageDrawable(FilesLook.drawable(context, "gs_image_vd_theme_24"));
        title.setText(FilesLook.string(context, "images_label", "Images"));
        subtitle.setText("1,234");
        return view;
    }

    private View capsuleItemFallback() {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(FilesLook.dp(context, 64));
        Drawable ripple = FilesLook.drawable(context, "capsule_item_ripple");
        if (ripple != null) row.setBackground(ripple);
        ImageView icon = new ImageView(context);
        icon.setImageDrawable(FilesLook.drawable(context, "gs_image_vd_theme_24"));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(FilesLook.dp(context, 34),
                FilesLook.dp(context, 34));
        iconParams.setMarginStart(FilesLook.dp(context, 16));
        iconParams.setMarginEnd(FilesLook.dp(context, 16));
        row.addView(icon, iconParams);
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = styledText("CapsuleItemGm3TitleStyle", 14, textPrimary);
        title.setText(FilesLook.string(context, "images_label", "Images"));
        TextView subtitle = styledText("CapsuleItemGm3SubtitleStyle", 12, textSecondary);
        subtitle.setText("1,234");
        texts.addView(title);
        texts.addView(subtitle);
        row.addView(texts);
        return row;
    }

    // ---- row_item_view ----------------------------------------------------------------------------------------

    private View rowItem(boolean folder) {
        View view = FilesLook.inflate(context, "row_item_view", column);
        if (view == null) return null;
        ImageView thumbnail = view.findViewById(FilesLook.id(context, "thumbnail"));
        TextView title = view.findViewById(FilesLook.id(context, "title"));
        TextView subtitle = view.findViewById(FilesLook.id(context, "subtitle"));
        if (thumbnail == null || title == null || subtitle == null) return null;
        // The app hides these per item when it binds a row; here nothing binds them.
        hide(view, "top_shadow");
        hide(view, "thumbnail_top_end_icon");
        hide(view, "saved_position_progressbar_container");
        if (folder) {
            thumbnail.setScaleType(ImageView.ScaleType.CENTER);
            thumbnail.setImageDrawable(FilesLook.drawable(context, "gs_folder_fill1_vd_theme_24"));
        } else {
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setImageDrawable(thumbnailPlaceholder());
        }
        title.setText(folder ? "2024" : "IMG_0001.jpg");
        subtitle.setText(folder ? filesCount(12) : "3.2 MB");
        return view;
    }

    private View rowItemFallback(boolean folder) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int margin = FilesLook.dimenPx(context, "row_item_horizontal_margin", 8);
        int icon = FilesLook.dimenPx(context, "advanced_browsing_list_item_image_size", 48);
        row.setPadding(margin, FilesLook.dp(context, 4), margin, FilesLook.dp(context, 4));
        ImageView thumbnail = new ImageView(context);
        thumbnail.setScaleType(folder ? ImageView.ScaleType.CENTER : ImageView.ScaleType.CENTER_CROP);
        thumbnail.setImageDrawable(folder ? FilesLook.drawable(context, "gs_folder_fill1_vd_theme_24")
                : thumbnailPlaceholder());
        row.addView(thumbnail, new LinearLayout.LayoutParams(icon, icon));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = styledText("ListItemTitleText", 16, textPrimary);
        title.setText(folder ? "2024" : "IMG_0001.jpg");
        TextView subtitle = styledText("ListItemSubtitleTextBlack", 14, textSecondary);
        subtitle.setText(folder ? filesCount(12) : "3.2 MB");
        texts.addView(title);
        texts.addView(subtitle);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
        textParams.setMarginStart(FilesLook.dp(context, 16));
        row.addView(texts, textParams);
        return row;
    }

    private String filesCount(int count) {
        String text = FilesLook.plural(context, "files", count);
        return text != null ? text : count + " files";
    }

    private Drawable thumbnailPlaceholder() {
        return new ColorDrawable(FilesLook.themeColor(context, "colorSurfaceVariant", Color.LTGRAY));
    }

    private void hide(View root, String idName) {
        int id = FilesLook.id(context, idName);
        View view = id == 0 ? null : root.findViewById(id);
        if (view != null) view.setVisibility(View.GONE);
    }

    // ---- square_item_view -------------------------------------------------------------------------------------

    private View squareStrip() {
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 1; i <= 3; i++) {
            View cell = FilesLook.inflate(context, "square_item_view", strip);
            if (cell == null) return null;
            ImageView thumbnail = cell.findViewById(FilesLook.id(context, "thumbnail"));
            TextView title = cell.findViewById(FilesLook.id(context, "title"));
            if (thumbnail == null || title == null) return null;
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setImageDrawable(thumbnailPlaceholder());
            title.setText("IMG_000" + i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            int gap = FilesLook.dp(context, 2);
            params.setMargins(gap, gap, gap, gap);
            strip.addView(cell, params);
        }
        return strip;
    }

    private View squareStripFallback() {
        LinearLayout strip = new LinearLayout(context);
        for (int i = 1; i <= 3; i++) {
            FrameLayout cell = new FrameLayout(context);
            cell.setBackground(thumbnailPlaceholder());
            TextView title = styledText("GridItemFileNameText", 12, textSecondary);
            title.setText("IMG_000" + i);
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, FilesLook.dp(context, 96), 1);
            int gap = FilesLook.dp(context, 2);
            params.setMargins(gap, gap, gap, gap);
            strip.addView(cell, params);
        }
        return strip;
    }

    // ---- advanced_browsing_picker_fragment: button_section ----------------------------------------------------

    private View buttonSection() {
        // The whole picker shell is inflated (the browser will use it); only its button bar is shown here.
        View shell = FilesLook.inflate(context, "advanced_browsing_picker_fragment", column);
        if (shell == null) return null;
        DiagLog.i("ui", "UI sample picker shell: toolbar=" + className(shell, "toolbar")
                + " breadcrumb=" + className(shell, "breadcrumb_view")
                + " content_list=" + className(shell, "content_list")
                + " progress=" + className(shell, "progress_bar"));
        View section = shell.findViewById(FilesLook.id(context, "button_section"));
        View newFolder = shell.findViewById(FilesLook.id(context, "new_folder_action"));
        View action = shell.findViewById(FilesLook.id(context, "action_button"));
        if (section == null || !(action instanceof TextView) || !(newFolder instanceof TextView)) return null;
        ((ViewGroup) section.getParent()).removeView(section);
        ((TextView) action).setText(FilesLook.string(context, "move_here", "Move here"));
        section.setVisibility(View.VISIBLE);
        section.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return section;
    }

    private String className(View root, String idName) {
        int id = FilesLook.id(context, idName);
        View view = id == 0 ? null : root.findViewById(id);
        return view == null ? "missing" : view.getClass().getSimpleName();
    }

    private View buttonSectionFallback() {
        LinearLayout buttons = new LinearLayout(context);
        buttons.setPadding(FilesLook.dp(context, 20), FilesLook.dp(context, 20), FilesLook.dp(context, 20),
                FilesLook.dp(context, 20));
        Button newFolder = new Button(context);
        newFolder.setText(FilesLook.string(context, "new_folder_button_title", "New folder"));
        Button action = new Button(context);
        action.setText(FilesLook.string(context, "move_here", "Move here"));
        buttons.addView(newFolder, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(action, new LinearLayout.LayoutParams(0, -2, 1));
        return buttons;
    }

    // ---- no_files_blob ----------------------------------------------------------------------------------------

    private View noFiles() {
        View view = FilesLook.inflate(context, "no_files_blob", column);
        if (view == null) return null;
        // The layout is match_parent in height; the sample only needs its natural size.
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private View noFilesFallback() {
        TextView text = styledText("EmptyStateText", 14, textSecondary);
        text.setText(FilesLook.string(context, "empty_state_text", "There's nothing here."));
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, FilesLook.dp(context, 24), 0, FilesLook.dp(context, 24));
        return text;
    }

    // ---- file_filter_container --------------------------------------------------------------------------------

    private View filterChips() {
        View container = FilesLook.inflate(context, "file_filter_container", column);
        if (container == null) return null;
        View group = container.findViewById(FilesLook.id(context, "file_filter_chip_group"));
        if (!(group instanceof ViewGroup)) return null;
        String[] labels = {FilesLook.string(context, "all_label", "All"),
                FilesLook.string(context, "images_label", "Images"),
                FilesLook.string(context, "videos_label", "Videos")};
        for (int i = 0; i < labels.length; i++) {
            View chip = FilesLook.inflate(context, "file_filter_item", (ViewGroup) group);
            if (!(chip instanceof CompoundButton)) return null;
            ((CompoundButton) chip).setText(labels[i]);
            ((CompoundButton) chip).setChecked(i == 0);
            ((ViewGroup) group).addView(chip);
        }
        container.setVisibility(View.VISIBLE);
        container.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return container;
    }

    private View filterChipsFallback() {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setPadding(FilesLook.dp(context, 12), FilesLook.dp(context, 8), FilesLook.dp(context, 12),
                FilesLook.dp(context, 8));
        String[] labels = {FilesLook.string(context, "all_label", "All"),
                FilesLook.string(context, "images_label", "Images"),
                FilesLook.string(context, "videos_label", "Videos")};
        for (String label : labels) {
            TextView chip = new TextView(context);
            chip.setText(label);
            chip.setTextColor(textPrimary);
            chip.setPadding(FilesLook.dp(context, 12), FilesLook.dp(context, 6), FilesLook.dp(context, 12),
                    FilesLook.dp(context, 6));
            GradientDrawable outline = new GradientDrawable();
            outline.setCornerRadius(FilesLook.dp(context, 8));
            outline.setStroke(FilesLook.dp(context, 1), textSecondary);
            chip.setBackground(outline);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMarginEnd(FilesLook.dp(context, 8));
            chips.addView(chip, params);
        }
        scroll.addView(chips);
        return scroll;
    }

    // ---- shared -----------------------------------------------------------------------------------------------

    /** A TextView with the app's named style (0 / missing style gives the plain look in {@code color}). */
    private TextView styledText(String styleName, int fallbackSp, int color) {
        int style = FilesLook.style(context, styleName);
        TextView view = new TextView(context, null, 0, style);
        if (style == 0) {
            view.setTextSize(fallbackSp);
            view.setTextColor(color);
        }
        return view;
    }
}
