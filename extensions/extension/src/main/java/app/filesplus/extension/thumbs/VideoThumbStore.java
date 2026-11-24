package app.filesplus.extension.thumbs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** WebP names are content keys. File size and mtime hold bytes and last use; .ref files map request identities. */
final class VideoThumbStore {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    final File directory;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Entry> aliases = new ConcurrentHashMap<>();
    private volatile Usage usage = new Usage(0, 0);
    private long bytes;

    static final class Usage {
        final int count;
        final long bytes;

        Usage(int count, long bytes) {
            this.count = count;
            this.bytes = bytes;
        }
    }

    static final class Source {
        final File path;
        final long bytes;
        final long modified;
        final boolean hasFileStat;

        Source(File path, long bytes, long modified, boolean hasFileStat) {
            this.path = path;
            this.bytes = bytes;
            this.modified = modified;
            this.hasFileStat = hasFileStat;
        }

        String key() { return VideoThumbStore.key(path.getPath(), bytes, modified); }

        boolean matches() {
            return !hasFileStat || (path.length() == bytes && path.lastModified() == modified);
        }
    }

    static final class Entry {
        final String key;
        final File file;
        final long bytes;
        final Set<String> aliasKeys = new HashSet<>();
        volatile Source source;
        volatile long lastUsed;
        volatile long verifyAfter;
        volatile boolean invalid;
        long savedUse;

        Entry(String key, File file) {
            this.key = key;
            this.file = file;
            bytes = file.length();
            savedUse = lastUsed = file.lastModified();
        }
        boolean sourceMatches() {
            Source identity = source;
            return identity == null || identity.matches();
        }
    }

    VideoThumbStore(File directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory.toPath());
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list video thumbnail index");
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".tmp")) Files.deleteIfExists(file.toPath());
            if (name.endsWith(".webp") && isHash(stem(file, 5)) && file.isFile()) {
                if (file.length() > 0) add(stem(file, 5), file);
                else Files.deleteIfExists(file.toPath());
            }
        }
        for (File file : files) {
            if (!file.getName().endsWith(".ref") || !isHash(stem(file, 4))) continue;
            if (!loadAlias(file)) Files.deleteIfExists(file.toPath());
        }
        updateUsage();
    }

    private boolean loadAlias(File file) throws IOException {
        long size = file.length();
        if (size < 64 || size > 32_768) return false;
        String[] fields = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII).split("\\n", -1);
        Entry entry = entries.get(fields[0]);
        if (entry == null) return false;
        if (fields.length == 4) {
            try {
                String path = new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8);
                File source = new File(path);
                if (!source.isAbsolute()) return false;
                Source stamp = new Source(source, Long.parseLong(fields[2]), Long.parseLong(fields[3]), true);
                if (!entry.key.equals(stamp.key())) return false;
                entry.source = stamp;
            } catch (IllegalArgumentException error) {
                return false;
            }
        } else if (fields.length != 1) {
            return false;
        }
        storeAlias(stem(file, 4), entry);
        return true;
    }

    static String key(String absolutePath, long bytes, long modified) {
        return hash(absolutePath + '\0' + bytes + '/' + modified);
    }

    static String hash(String value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        char[] output = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            output[i * 2] = HEX[(digest[i] & 0xff) >>> 4];
            output[i * 2 + 1] = HEX[digest[i] & 0xf];
        }
        return new String(output);
    }

    static boolean almostBlack(int[] pixels) {
        if (pixels.length == 0) return false;
        int dark = 0;
        for (int pixel : pixels) {
            int luminance = 54 * ((pixel >>> 16) & 255) + 183 * ((pixel >>> 8) & 255) + 19 * (pixel & 255);
            if (luminance < 24 * 256) dark++;
        }
        return 100L * dark >= 98L * pixels.length;
    }

    Entry lookup(String alias) {
        Entry entry = aliases.get(alias);
        return entry == null || entry.invalid ? null : entry;
    }

    Entry existing(String key) {
        Entry entry = entries.get(key);
        return entry == null || entry.invalid ? null : entry;
    }

    Usage usage() { return usage; }

    File temp(String key) { return new File(directory, key + ".webp.tmp"); }

    Entry publish(String key, File temporary) throws IOException {
        File target = new File(directory, key + ".webp");
        move(temporary, target);
        Entry entry = add(key, target);
        updateUsage();
        return entry;
    }

    void link(String alias, Entry entry, Source source) throws IOException {
        String data = entry.key;
        if (source != null && source.hasFileStat) {
            String path = Base64.getEncoder().encodeToString(source.path.getPath().getBytes(StandardCharsets.UTF_8));
            data += "\n" + path + "\n" + source.bytes + "\n" + source.modified;
            entry.source = source;
        }
        File target = new File(directory, alias + ".ref");
        File temporary = new File(directory, alias + ".ref.tmp");
        try {
            Files.write(temporary.toPath(), data.getBytes(StandardCharsets.US_ASCII));
            move(temporary, target);
            storeAlias(alias, entry);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private void storeAlias(String alias, Entry entry) {
        Entry previous = aliases.put(alias, entry);
        if (previous != null) previous.aliasKeys.remove(alias);
        entry.aliasKeys.add(alias);
    }

    void touch(Entry entry) throws IOException {
        if (existing(entry.key) != entry || entry.savedUse >= entry.lastUsed) return;
        long lastUsed = entry.lastUsed;
        if (!entry.file.setLastModified(lastUsed)) throw new IOException("Cannot update thumbnail access time");
        entry.savedUse = lastUsed;
    }

    void remove(Entry entry) throws IOException {
        if (entries.get(entry.key) != entry) return;
        entry.invalid = true;
        Files.deleteIfExists(entry.file.toPath());
        entries.remove(entry.key);
        bytes -= entry.bytes;
        updateUsage();
        for (String alias : entry.aliasKeys) {
            aliases.remove(alias, entry);
            Files.deleteIfExists(new File(directory, alias + ".ref").toPath());
        }
    }

    Entry byFile(File file) {
        if (!directory.equals(file.getParentFile()) || !file.getName().endsWith(".webp")) return null;
        return entries.get(stem(file, 5));
    }

    void trim(long limit) throws IOException {
        if (bytes <= limit) return;
        var oldest = new ArrayList<>(entries.values());
        oldest.sort(Comparator.comparingLong((Entry entry) -> entry.lastUsed).thenComparing(entry -> entry.key));
        for (Entry entry : oldest) {
            if (bytes <= limit) break;
            remove(entry);
        }
    }

    void clear() throws IOException {
        trim(0);
    }

    private Entry add(String key, File file) {
        Entry entry = new Entry(key, file);
        Entry previous = entries.put(key, entry);
        bytes += entry.bytes - (previous == null ? 0 : previous.bytes);
        return entry;
    }

    private void updateUsage() { usage = new Usage(entries.size(), bytes); }

    private static boolean isHash(String value) { return HASH.matcher(value).matches(); }

    private static String stem(File file, int suffixLength) {
        String name = file.getName();
        return name.substring(0, name.length() - suffixLength);
    }

    private static void move(File from, File to) throws IOException {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
