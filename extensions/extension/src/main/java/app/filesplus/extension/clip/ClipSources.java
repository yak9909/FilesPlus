package app.filesplus.extension.clip;

import app.filesplus.extension.ui.ProtoNode;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Wire-number decoding only; no app classes. Enumeration and measurement run on the worker. */
final class ClipSources {
    static final class Source {
        final String path;
        final long id;
        Source(String path, long id) { this.path = path; this.id = id; }
        boolean local() { return path != null && path.startsWith("/"); }
        boolean eligible() { return local() && !trashedName(new File(path).getName()); }
    }

    static Source file(ProtoNode node) {
        String path = node.getString(1);
        String uri = node.getString(9);
        // MediaStore content URIs describe local files too; other providers are never clipboard sources.
        if (!localFileUri(uri) || trashedName(node.getString(2))) path = null;
        long id = node.getVarint(13, 0);
        if (id <= 0) id = node.getVarint(18, 0);
        return new Source(path, Math.max(0, id));
    }

    private static boolean localFileUri(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        try {
            java.net.URI parsed = java.net.URI.create(uri);
            return ("file".equals(parsed.getScheme()) && uri.startsWith("file://") && parsed.getAuthority() == null)
                    || ("content".equals(parsed.getScheme()) && "media".equals(parsed.getAuthority()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean trashedName(String name) {
        return name != null && name.startsWith(".trashed-");
    }

    static Source folder(ProtoNode node) {
        String uri = node.getString(2);
        String path = uri;
        if (uri != null && uri.startsWith("file:")) {
            java.net.URI parsed = java.net.URI.create(uri);
            path = parsed.getAuthority() == null ? parsed.getPath() : null;
        }
        return new Source(path, 0);
    }

    static Source entry(ProtoNode node) {
        if (node.has(1)) return file(node.getMessage(1));
        if (node.has(2)) return folder(node.getMessage(2));
        return new Source(null, 0);
    }

    static List<Source> individual(ProtoNode selection) {
        List<Source> out = new ArrayList<>();
        ProtoNode list = selection.getMessage(2);
        if (list != null) for (ProtoNode entry : list.getMessages(1)) out.add(entry(entry));
        return out;
    }

    static boolean hasLocalSelection(ProtoNode selection) {
        if (selection.has(1)) {
            ProtoNode all = selection.getMessage(1);
            return all != null && all.has(1) && folder(all.getMessage(1)).eligible();
        }
        for (Source source : individual(selection)) if (source.eligible()) return true;
        return false;
    }

    static List<Source> expand(ProtoNode selection) throws java.io.IOException {
        if (!selection.has(1)) return individual(selection);
        ProtoNode all = selection.getMessage(1);
        Source parent = folder(all.getMessage(1));
        if (!parent.local()) return java.util.Collections.singletonList(parent);
        java.util.Set<String> excluded = new java.util.HashSet<>();
        for (ProtoNode entry : all.getMessages(2)) {
            Source source = entry(entry);
            if (!source.local()) throw new java.io.IOException("Non-local selection exclusion");
            excluded.add(new File(source.path).getCanonicalPath().toLowerCase(java.util.Locale.ROOT));
        }
        return visibleChildren(new File(parent.path), excluded);
    }

    /** Without a safe bridge to the host's hidden-files preference, select-all excludes all dot-names. */
    static List<Source> visibleChildren(File parent, java.util.Set<String> excluded) throws java.io.IOException {
        File[] children = parent.listFiles();
        if (children == null) throw new java.io.IOException("Cannot enumerate selected folder");
        List<Source> out = new ArrayList<>();
        for (File child : children) {
            if (child.getName().startsWith(".")) continue;
            if (!excluded.contains(child.getCanonicalPath().toLowerCase(java.util.Locale.ROOT)))
                out.add(new Source(child.getAbsolutePath(), 0));
        }
        return out;
    }

    static boolean protectedPath(File file, List<File> roots) throws java.io.IOException {
        return PasteRules.protectedSource(file, roots);
    }

    private ClipSources() {}
}
