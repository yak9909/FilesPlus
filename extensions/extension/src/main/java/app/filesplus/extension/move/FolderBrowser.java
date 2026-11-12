package app.filesplus.extension.move;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.move.DestinationSheet.Palette;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Small folder browser in a bottom-aligned Dialog: storage roots, sub-folders (dot-folders hidden),
 * an "Up" row, a "New folder" row and a "Move here" button. Built from code only. Folders are listed
 * on a background thread and shown in a recycling ListView.
 */
public final class FolderBrowser {
    private static final String AREA = "move";

    private FolderBrowser() {}

    /** Shows the browser at the primary storage root; {@code onChosen} gets the picked folder. */
    public static void show(Activity activity, Consumer<File> onChosen) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || onChosen == null) return;
            new Browser(activity, onChosen).show();
        } catch (Throwable t) {
            DiagLog.e(AREA, "Folder browser failed", t);
        }
    }

    /** One list row: what it shows and what a tap does. */
    private static final class Item {
        final String primary;
        final String secondary;
        final int color;
        final Runnable action;

        Item(String primary, String secondary, int color, Runnable action) {
            this.primary = primary;
            this.secondary = secondary;
            this.color = color;
            this.action = action;
        }
    }

    private static final class Browser {
        private final Activity activity;
        private final Consumer<File> onChosen;
        private final Palette palette;
        private final boolean ja;
        private final Map<File, String> roots;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final TextView title;
        private final TextView subtitle;
        private final TextView moveHere;
        private final ListView listView;
        private final List<Item> items = new ArrayList<>();
        private final BaseAdapter adapter;
        private final Dialog dialog;
        /** Null = the list of storage roots. */
        private File current;
        /** Bumped on every navigation so a slow listing of a folder we already left is ignored. */
        private int generation;

        Browser(Activity activity, Consumer<File> onChosen) {
            this.activity = activity;
            this.onChosen = onChosen;
            this.palette = new Palette(activity);
            this.ja = DestinationSheet.japanese(activity);
            this.roots = storageRoots(activity, ja);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(DestinationSheet.sheetBackground(activity, palette));
            root.setPadding(0, DestinationSheet.dp(activity, 12), 0, DestinationSheet.dp(activity, 8));

            title = DestinationSheet.text(activity, "", 18, palette.text, true);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            root.addView(title, DestinationSheet.padded(activity, 20, 4, 20, 0));
            subtitle = DestinationSheet.text(activity, "", 12, palette.sub, false);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            root.addView(subtitle, DestinationSheet.padded(activity, 20, 0, 20, 8));

            listView = new ListView(activity);
            listView.setDivider(null);
            listView.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            adapter = new BaseAdapter() {
                @Override public int getCount() { return items.size(); }
                @Override public Item getItem(int position) { return items.get(position); }
                @Override public long getItemId(int position) { return position; }

                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    Item item = items.get(position);
                    View row = convertView != null ? convertView
                            : DestinationSheet.row(activity, palette, "", null, palette.text);
                    DestinationSheet.bindRow(row, item.primary, item.secondary, item.color);
                    return row;
                }
            };
            listView.setAdapter(adapter);
            listView.setOnItemClickListener((parent, view, position, id) -> {
                try {
                    Item item = items.get(position);
                    if (item.action != null) item.action.run();
                } catch (Throwable t) {
                    DiagLog.e(AREA, "Folder row failed", t);
                }
            });
            root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            root.addView(DestinationSheet.divider(activity, palette),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            LinearLayout buttons = new LinearLayout(activity);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            buttons.setPadding(DestinationSheet.dp(activity, 12), DestinationSheet.dp(activity, 4),
                    DestinationSheet.dp(activity, 12), 0);
            TextView cancel = button(ja ? "キャンセル" : "Cancel", palette.sub);
            cancel.setOnClickListener(v -> dismiss());
            moveHere = button(ja ? "ここに移動" : "Move here", palette.accent);
            moveHere.setOnClickListener(v -> {
                try {
                    File target = current;
                    if (target == null) return;
                    dismiss();
                    onChosen.accept(target);
                } catch (Throwable t) {
                    DiagLog.e(AREA, "Choosing a folder failed", t);
                }
            });
            buttons.addView(cancel);
            buttons.addView(moveHere);
            root.addView(buttons, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.75f);
            dialog = DestinationSheet.newBottomDialog(activity, root, height, this::goBack);

            File primary = Environment.getExternalStorageDirectory();
            current = primary != null && primary.isDirectory() ? primary : null;
            render();
        }

        void show() {
            dialog.show();
        }

        private void dismiss() {
            generation++;
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
                // Already gone.
            }
        }

        /** Back key: one folder up; at a storage root (or the root list) it is not consumed and closes. */
        private boolean goBack() {
            if (current == null || isRoot(current)) return false;
            go(current.getParentFile());
            return true;
        }

        private TextView button(String label, int color) {
            TextView view = DestinationSheet.text(activity, label, 15, color, true);
            view.setGravity(Gravity.CENTER);
            view.setMinimumHeight(DestinationSheet.dp(activity, 48));
            view.setPadding(DestinationSheet.dp(activity, 16), DestinationSheet.dp(activity, 8),
                    DestinationSheet.dp(activity, 16), DestinationSheet.dp(activity, 8));
            view.setBackground(DestinationSheet.pressFeedback(palette));
            view.setClickable(true);
            return view;
        }

        private boolean isRoot(File folder) {
            for (File root : roots.keySet()) {
                if (root.getPath().equals(folder.getPath())) return true;
            }
            return false;
        }

        private void go(File folder) {
            current = folder;
            render();
        }

        private void setItems(List<Item> next) {
            items.clear();
            items.addAll(next);
            adapter.notifyDataSetChanged();
            listView.setSelection(0);
        }

        private void render() {
            try {
                final int mine = ++generation;
                if (current == null) {
                    title.setText(ja ? "ストレージ" : "Storage");
                    subtitle.setText("");
                    setMoveHereEnabled(false);
                    List<Item> rows = new ArrayList<>();
                    for (Map.Entry<File, String> root : roots.entrySet()) {
                        File folder = root.getKey();
                        rows.add(new Item(root.getValue(), folder.getPath(), palette.text, () -> go(folder)));
                    }
                    setItems(rows);
                    return;
                }
                final File folder = current;
                String name = folder.getName().isEmpty() ? folder.getPath() : folder.getName();
                String rootLabel = roots.get(folder);
                title.setText(rootLabel != null ? rootLabel : name);
                subtitle.setText(folder.getPath());
                setMoveHereEnabled(false);

                // The fixed rows show at once; the folder names follow from a background thread.
                final List<Item> head = new ArrayList<>();
                if (!isRoot(folder) || roots.size() > 1) {
                    head.add(new Item(ja ? "↑ 上のフォルダ" : "↑ Up", null, palette.accent,
                            () -> go(isRoot(folder) ? null : folder.getParentFile())));
                }
                head.add(new Item(ja ? "＋ 新しいフォルダ" : "+ New folder", null, palette.accent,
                        () -> promptNewFolder(folder)));
                List<Item> loading = new ArrayList<>(head);
                loading.add(new Item(ja ? "読み込み中…" : "Loading…", null, palette.sub, null));
                setItems(loading);

                new Thread(() -> {
                    List<Item> rows = new ArrayList<>(head);
                    boolean writable = false;
                    try {
                        writable = folder.canWrite();
                        File[] children = folder.listFiles(f -> !f.getName().startsWith(".") && f.isDirectory());
                        if (children == null) {
                            rows.add(new Item(ja ? "このフォルダは開けません" : "Cannot open this folder",
                                    null, palette.sub, null));
                        } else if (children.length == 0) {
                            rows.add(new Item(ja ? "フォルダがありません" : "No folders here", null, palette.sub, null));
                        } else {
                            Arrays.sort(children, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
                            for (File child : children) {
                                rows.add(new Item(child.getName(), null, palette.text, () -> go(child)));
                            }
                        }
                    } catch (Throwable t) {
                        DiagLog.e(AREA, "Listing " + folder + " failed", t);
                        rows.add(new Item(ja ? "このフォルダは開けません" : "Cannot open this folder", null, palette.sub, null));
                    }
                    final boolean canWrite = writable;
                    main.post(() -> {
                        if (mine != generation) return; // the user already went elsewhere or closed the browser
                        setMoveHereEnabled(canWrite);
                        setItems(rows);
                    });
                }, "filesplus-folder-list").start();
            } catch (Throwable t) {
                DiagLog.e(AREA, "Rendering the folder list failed", t);
            }
        }

        private void setMoveHereEnabled(boolean enabled) {
            moveHere.setEnabled(enabled);
            moveHere.setAlpha(enabled ? 1f : 0.4f);
        }

        private void promptNewFolder(File parent) {
            try {
                EditText input = new EditText(activity);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                input.setSingleLine(true);
                input.setHint(ja ? "フォルダ名" : "Folder name");
                FrameLayout frame = new FrameLayout(activity);
                int side = DestinationSheet.dp(activity, 20);
                frame.setPadding(side, DestinationSheet.dp(activity, 8), side, 0);
                frame.addView(input, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                int theme = palette.night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                        : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
                AlertDialog prompt = new AlertDialog.Builder(activity, theme)
                        .setTitle(ja ? "新しいフォルダ" : "New folder")
                        .setView(frame)
                        .setNegativeButton(ja ? "キャンセル" : "Cancel", null)
                        .setPositiveButton(ja ? "作成" : "Create", null)
                        .create();
                prompt.setOwnerActivity(activity);
                DestinationSheet.track(prompt);
                prompt.show();
                // Set after show() so an invalid name keeps the prompt open with the typed text.
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    try {
                        if (createFolder(parent, input.getText().toString())) prompt.dismiss();
                    } catch (Throwable t) {
                        DiagLog.e(AREA, "Creating a folder failed", t);
                    }
                });
            } catch (Throwable t) {
                DiagLog.e(AREA, "New folder prompt failed", t);
            }
        }

        /** Returns true when the prompt may close (folder ready); false keeps it open. */
        private boolean createFolder(File parent, String rawName) {
            String name = rawName == null ? "" : rawName.trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                    || name.indexOf('\0') >= 0) {
                Toast.makeText(activity, ja ? "フォルダ名が正しくありません" : "Invalid folder name", Toast.LENGTH_SHORT).show();
                return false;
            }
            File folder = new File(parent, name);
            if (folder.isDirectory() || folder.mkdirs()) {
                DiagLog.i(AREA, "Folder ready: " + folder);
                go(folder);
                return true;
            }
            DiagLog.e(AREA, "Cannot create folder " + folder, null);
            Toast.makeText(activity, ja ? "フォルダを作成できませんでした" : "Could not create the folder",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** Primary external storage first, then other mounted volumes (API 30+ gives their directories). */
    private static Map<File, String> storageRoots(Context context, boolean ja) {
        Map<File, String> roots = new LinkedHashMap<>();
        try {
            File primary = Environment.getExternalStorageDirectory();
            if (primary != null) roots.put(primary, ja ? "内部ストレージ" : "Internal storage");
            if (Build.VERSION.SDK_INT >= 30) {
                StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
                if (manager != null) {
                    List<StorageVolume> volumes = new ArrayList<>(manager.getStorageVolumes());
                    for (StorageVolume volume : volumes) {
                        File dir = volume.getDirectory();
                        if (dir == null || !Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
                        boolean known = false;
                        for (File existing : roots.keySet()) {
                            if (existing.getPath().equals(dir.getPath())) known = true;
                        }
                        if (!known) roots.put(dir, volume.getDescription(context));
                    }
                }
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "Listing storage volumes failed", t);
        }
        return roots;
    }
}
