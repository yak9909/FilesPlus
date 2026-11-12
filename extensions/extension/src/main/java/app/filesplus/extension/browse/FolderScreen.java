package app.filesplus.extension.browse;

import android.text.format.Formatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Screens 4 and 5 of DESIGN 5.11.4: the inside of one folder, with the "New folder" and "Move here" buttons.
 * Reached from a category (the category's images and videos are shown as a grid) or from a storage volume (every
 * file is a dimmed row). Files cannot be tapped: the browser only picks a destination.
 */
final class FolderScreen extends Screen {
    private static final class Data {
        List<Entry> entries;
        List<String> crumbNames;
        List<Location> crumbTargets;
        String name;
        String refusal;
    }

    final Location dir;
    /** The category this folder was reached from, or null when browsing a storage volume. */
    final Category category;

    private List<Entry> entries = Collections.emptyList();
    private List<String> crumbNames = Collections.emptyList();
    private List<Location> crumbTargets = Collections.emptyList();
    private String name;
    private String refusal;

    FolderScreen(Location dir, Category category) {
        this.dir = dir;
        this.category = category;
    }

    @Override String title(BrowseActivity host) {
        return name != null ? name : dir.name();
    }

    /** Whether the screen has been loaded once (its title is known; before that dir.name() could mislead). */
    boolean hasName() {
        return name != null;
    }

    List<String> crumbNames() {
        return crumbNames;
    }

    List<Location> crumbTargets() {
        return crumbTargets;
    }

    @Override Object load(BrowseActivity host) throws Exception {
        LocalSource source = host.source();
        List<LocalSource.Volume> volumes = source.volumes();
        Data data = new Data();
        data.entries = source.list(dir, category);

        List<String> names = new ArrayList<>();
        List<Location> targets = new ArrayList<>();
        LocalSource.Volume volume = volumeOf(volumes, dir);
        String shown = dir.name();
        if (volume != null) {
            String root = volumeLabel(host, volume);
            names.add(root);
            targets.add(volume.root);
            Location current = volume.root;
            String relative = dir.path.length() > volume.root.path.length()
                    ? dir.path.substring(volume.root.path.length() + 1) : "";
            if (!relative.isEmpty()) {
                for (String segment : relative.split("/")) {
                    current = current.child(segment);
                    if (current == null) break;
                    names.add(segment);
                    targets.add(current);
                }
            } else {
                shown = root;
            }
        } else {
            names.add(dir.path);
            targets.add(dir);
        }
        data.crumbNames = names;
        data.crumbTargets = targets;
        data.name = shown;
        data.refusal = computeRefusal(host, shown);
        return data;
    }

    @Override void apply(BrowseActivity host, Object loaded) {
        Data data = (Data) loaded;
        entries = data.entries;
        crumbNames = data.crumbNames;
        crumbTargets = data.crumbTargets;
        name = data.name;
        refusal = data.refusal;
    }

    @Override List<Row> layout(final BrowseActivity host, int columns) {
        List<Row> rows = new ArrayList<>();
        List<Entry> files = new ArrayList<>();
        for (final Entry entry : entries) {
            if (entry.directory) {
                rows.add(Row.folder("gs_folder_fill1_vd_theme_24", entry.name, null, null,
                        () -> host.open(new FolderScreen(entry.location, category))));
            } else {
                files.add(entry);
            }
        }
        boolean grid = category == Category.IMAGES || category == Category.VIDEOS;
        if (grid) {
            for (int from = 0; from < files.size(); from += columns) {
                rows.add(Row.grid(new ArrayList<>(files.subList(from, Math.min(files.size(), from + columns)))));
            }
        } else {
            for (Entry file : files) {
                rows.add(Row.file(file, icon(file.category), Formatter.formatShortFileSize(host, file.size)));
            }
        }
        if (rows.isEmpty()) rows.add(Row.message(null));
        return rows;
    }

    /** Why "Move here" cannot be used in this folder (a message for the user), or null when it can. */
    String refusal() {
        return refusal;
    }

    /**
     * Background thread (it resolves symlinks and aliases such as /sdcard, so the checks cannot be bypassed by
     * the way the caller spelled a path). {@code shown} is the name the screen displays, also used in the
     * "already in X" message (at a volume root the folder's own name would be "0").
     */
    private String computeRefusal(BrowseActivity host, String shown) {
        if (!dir.file().canWrite()) return host.tr("このフォルダには書き込めません", "Cannot write to this folder");
        PickRequests.PickSpec spec = host.spec();
        Location here = PickRequests.canonical(dir);
        for (Location moving : spec.canonicalMoving()) {
            if (here.isWithin(moving)) {
                return host.tr("移動するフォルダの中には移動できません", "A folder cannot be moved into itself");
            }
        }
        List<Location> sources = spec.canonicalSources();
        if (!sources.isEmpty()) {
            boolean allHere = true;
            for (Location source : sources) {
                if (!source.equals(here)) {
                    allHere = false;
                    break;
                }
            }
            if (allHere) return host.sameDirectory(shown, Math.max(1, spec.count));
        }
        return null;
    }
}
