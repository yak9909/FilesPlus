package app.filesplus.extension.browse;

import app.filesplus.extension.DiagLog;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Screen 2 of DESIGN 5.11.4: the categories (with a shortcut to Downloads) and the storage volumes. */
final class RootScreen extends Screen {
    private static final class Data {
        final List<Row> rows = new ArrayList<>();
        final Map<Category, Row> capsules = new EnumMap<>(Category.class);
    }

    private List<Row> rows = Collections.emptyList();

    @Override String title(BrowseActivity host) {
        return host.pickTitle();
    }

    @Override Object load(final BrowseActivity host) {
        final List<LocalSource.Volume> volumes = host.source().volumes();
        Data data = new Data();
        data.rows.add(Row.header(host.app("category_title", host.tr("カテゴリ", "Categories"))));
        for (final Category category : Category.values()) {
            Runnable open;
            if (category == Category.DOWNLOADS) {
                // A shortcut into the primary volume's Download folder (it has no MediaStore category).
                Location download = volumes.isEmpty() ? null : volumes.get(0).root.child("Download");
                if (download == null) continue;
                final Location target = download;
                open = () -> host.open(new FolderScreen(target, null));
            } else {
                open = () -> host.open(new CategoryScreen(category));
            }
            Row row = Row.capsule(icon(category), label(host, category), null, open);
            data.rows.add(row);
            if (category.indexed()) data.capsules.put(category, row);
        }
        data.rows.add(Row.header(host.app("root_container_fragment_title", host.tr("すべてのストレージ", "All storage"))));
        for (final LocalSource.Volume volume : volumes) {
            data.rows.add(Row.capsule(volume.primary ? "quantum_gm_ic_phone_android_vd_theme_24"
                            : "quantum_gm_ic_sd_card_vd_theme_24",
                    volumeLabel(host, volume), null, () -> host.open(new FolderScreen(volume.root, null))));
        }
        return data;
    }

    @Override void apply(final BrowseActivity host, Object loaded) {
        final Data data = (Data) loaded;
        rows = data.rows;
        // The counts come from the same aggregation the category screens use, so they also warm its cache.
        host.background(() -> loadCounts(host, data.capsules));
    }

    @Override List<Row> layout(BrowseActivity host, int columns) {
        return rows;
    }

    private void loadCounts(final BrowseActivity host, Map<Category, Row> capsules) {
        for (final Map.Entry<Category, Row> item : capsules.entrySet()) {
            if (host.isGone()) return;
            int total = 0;
            try {
                for (Bucket bucket : host.source().buckets(item.getKey())) total += bucket.count;
            } catch (Throwable t) {
                DiagLog.e("browse", "Cannot count " + item.getKey(), t);
                continue;
            }
            final String text = NumberFormat.getInstance().format(total);
            host.post(() -> {
                item.getValue().subtitle = text;
                host.changed(this);
            });
        }
    }
}
