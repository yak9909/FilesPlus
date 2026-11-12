package app.filesplus.extension.browse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Screen 3 of DESIGN 5.11.4: the folders that hold files of one category, newest first. */
final class CategoryScreen extends Screen {
    private static final class Data {
        final List<LocalSource.Volume> volumes;
        final List<Bucket> buckets;

        Data(List<LocalSource.Volume> volumes, List<Bucket> buckets) {
            this.volumes = volumes;
            this.buckets = buckets;
        }
    }

    private final Category category;
    private List<Bucket> buckets = Collections.emptyList();
    private List<LocalSource.Volume> volumes = Collections.emptyList();

    CategoryScreen(Category category) {
        this.category = category;
    }

    @Override String title(BrowseActivity host) {
        return label(host, category);
    }

    @Override Object load(BrowseActivity host) throws Exception {
        List<LocalSource.Volume> found = host.source().volumes();
        return new Data(found, host.source().buckets(category));
    }

    @Override void apply(BrowseActivity host, Object loaded) {
        Data data = (Data) loaded;
        volumes = data.volumes;
        buckets = data.buckets;
    }

    @Override List<Row> layout(final BrowseActivity host, int columns) {
        List<Row> rows = new ArrayList<>();
        for (final Bucket bucket : buckets) {
            rows.add(Row.folder("gs_folder_fill1_vd_theme_24", bucket.name,
                    filesText(host, bucket.count) + "\n" + where(host, bucket.folder), bucket.newest,
                    () -> host.open(new FolderScreen(bucket.folder, category))));
        }
        if (rows.isEmpty()) rows.add(Row.message(null));
        return rows;
    }

    /** "DCIM/Camera", with the volume's name in front on removable storage. */
    private String where(BrowseActivity host, Location folder) {
        LocalSource.Volume volume = volumeOf(volumes, folder);
        if (volume == null) return folder.path;
        String relative = folder.path.length() > volume.root.path.length()
                ? folder.path.substring(volume.root.path.length() + 1) : "";
        return volume.primary ? relative : volumeLabel(host, volume) + ": " + relative;
    }
}
