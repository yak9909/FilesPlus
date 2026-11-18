package app.filesplus.extension.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.widget.Toast;

import app.filesplus.extension.DiagLog;
import app.filesplus.extension.browse.Category;
import app.filesplus.extension.settings.SettingsHooks;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Launches the app's own screens (storage browser, media browser, folder picker) with hand-built protobuf
 * arguments (DESIGN 5.12, docs/research/2026-11-12-original-screens-p0.md). The screens crash in onCreate on bad
 * arguments, so every launch re-decodes the bytes and checks them first; on any problem nothing is launched, a
 * diagnostic line is logged, a Toast is shown and false is returned. Message and field names of the app are
 * obfuscated; only field numbers are used here.
 */
public final class OriginalScreens {
    private static final String AREA = "screens";

    private static final String PKG = "com.google.android.apps.nbu.files.";
    private static final String STORAGE_ACTIVITY = PKG + "advancedbrowsing.browser.AdvancedBrowsingActivity";
    private static final String PICKER_ACTIVITY = PKG + "advancedbrowsing.picker.AdvancedBrowsingPickerActivity";
    private static final String MEDIA_ACTIVITY = PKG + "documentbrowser.filebrowser.FileBrowserRegularActivity";

    // kqg (a folder): field numbers.
    private static final int K_NAME = 1;
    private static final int K_URI = 2;
    private static final int K_EXISTS_URI = 3;
    private static final int K_NAME_RES = 7;
    private static final int K_KIND = 10;
    private static final int K_MODIFIED = 12;
    // hut: field numbers.
    private static final int H_ROOT = 1;
    private static final int H_STACK = 2;
    private static final int H_MODE = 4;
    private static final int H_ENTRY = 5;
    // jcp (media browser): field numbers.
    private static final int J_TAB = 1;
    private static final int J_CATEGORY = 3;
    private static final int J_ENTRY = 4;

    public static final int KIND_INTERNAL = 0;
    public static final int KIND_SD_CARD = 1;
    public static final int KIND_UNKNOWN = 2;
    public static final int KIND_USB = 3;
    public static final int KIND_ADOPTABLE_SD_CARD = 4;

    private static final int MODE_BROWSE = 1;
    private static final int MODE_MOVE = 3;
    private static final int ENTRY_FROM_LIST = 2;

    private OriginalScreens() {
    }

    /** One folder of the breadcrumb: the URI (kqg field 2), the display name and the storage kind (field 10). */
    public static final class FolderRef {
        private final String uri;
        private final String title;
        private final int storageKind;

        public FolderRef(String uri, String title, int storageKind) {
            this.uri = uri;
            this.title = title;
            this.storageKind = storageKind;
        }

        public String uri() {
            return uri;
        }

        public String title() {
            return title;
        }

        public int storageKind() {
            return storageKind;
        }

        @Override
        public String toString() {
            return "FolderRef{" + uri + ", " + title + ", kind " + storageKind + "}";
        }
    }

    // ---- stack ----

    /**
     * The breadcrumb from the root of the volume holding {@code folder} down to {@code folder}. Empty when the
     * folder is not under a known volume (or on any error).
     */
    public static List<FolderRef> stackFor(Context ctx, File folder) {
        try {
            if (folder == null) return Collections.emptyList();
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            File target = folder.getAbsoluteFile();
            File canonical = target;
            try {
                canonical = target.getCanonicalFile();
            } catch (Throwable ignored) {
                // keep the absolute path
            }
            for (StorageVolume volume : sm.getStorageVolumes()) {
                File root = volume.getDirectory();
                if (root == null) continue;
                File under = under(root, target) ? target : under(root, canonical) ? canonical : null;
                if (under == null) continue;
                // Only the primary volume (internal) and removable volumes (treated as SD card). API 31 gives no
                // reliable way to tell a USB OTG drive from an SD card (both are removable /storage/XXXX-XXXX), so
                // only a "USB" in the description is used as a hint; the app itself opens USB through content://
                // tree URIs, which this class does not build. Non-removable secondary volumes (adoptable) are refused.
                if (!volume.isPrimary()) {
                    if (!volume.isRemovable() || looksLikeUsb(ctx, volume)) return Collections.emptyList();
                }
                int kind = volume.isPrimary() ? KIND_INTERNAL : KIND_SD_CARD;
                List<File> chain = new ArrayList<>();
                for (File f = under; f != null; f = f.getParentFile()) {
                    chain.add(f);
                    if (f.equals(root)) break;
                }
                Collections.reverse(chain);
                List<FolderRef> out = new ArrayList<>();
                for (int i = 0; i < chain.size(); i++) {
                    File f = chain.get(i);
                    String title = i == 0 ? rootTitle(ctx, kind, volume) : f.getName();
                    out.add(new FolderRef(Uri.fromFile(f).toString(), title, kind));
                }
                return out;
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "stackFor failed", t);
        }
        return Collections.emptyList();
    }

    private static boolean looksLikeUsb(Context ctx, StorageVolume volume) {
        try {
            String d = volume.getDescription(ctx);
            return d != null && d.toLowerCase(java.util.Locale.ROOT).contains("usb");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean under(File root, File file) {
        String r = root.getPath();
        String p = file.getPath();
        return p.equals(r) || p.startsWith(r.endsWith("/") ? r : r + "/");
    }

    private static String rootTitle(Context ctx, int kind, StorageVolume volume) {
        int id = rootNameRes(ctx, kind);
        if (id != 0) {
            try {
                return ctx.getString(id);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        String d = null;
        try {
            d = volume.getDescription(ctx);
        } catch (Throwable ignored) {
            // fall through
        }
        return d != null && !d.isEmpty() ? d : kind == KIND_INTERNAL ? "Internal storage" : "SD card";
    }

    /** The app's own string resource for the volume's name (looked up by name; the id changes between builds). */
    private static int rootNameRes(Context ctx, int kind) {
        String name = kind == KIND_INTERNAL ? "internal_storage" : kind == KIND_SD_CARD ? "sd_card" : null;
        if (name == null) return 0;
        try {
            return ctx.getResources().getIdentifier(name, "string", ctx.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- launchers ----

    /**
     * Opens the storage browser at the last entry of {@code stack} (root first). With {@code replace} the calling
     * screen is finished after the new one has been started.
     * <p>
     * A stack of more than one entry needs the "Open folder stack" patch (the onCreate hook); without it Back
     * would show an empty screen, so such a launch is refused. Any patch whose code calls this with a stack must
     * {@code dependsOn(openStackPatch)} (app.filesplus.patches.screens), so the user cannot untick the hook.
     */
    public static boolean openStorage(Activity from, List<FolderRef> stack, boolean replace) {
        try {
            if (stack != null && stack.size() > 1 && !stackHookPresent()) {
                return fail(from, "refused: the \"Open folder stack\" patch is not applied", null,
                        "「Open folder stack」パッチが当たっていません", "The \"Open folder stack\" patch is not applied");
            }
            byte[] hut = buildHut(from, stack, MODE_BROWSE, ENTRY_FROM_LIST);
            byte[] args = new ProtoNode().setMessage(1, ProtoNode.decode(hut)).encode();
            return launch(from, STORAGE_ACTIVITY, args, false, 0, replace);
        } catch (Throwable t) {
            return fail(from, "openStorage failed", t);
        }
    }

    /**
     * Opens the media browser of a category. {@code tabNameOrNull} is the display name of the tab to select first
     * (case-insensitive match; the first tab with that name wins).
     */
    public static boolean openMedia(Activity from, Category c, String tabNameOrNull, boolean replace) {
        try {
            int isd = mediaCategory(c);
            if (isd == 0) return fail(from, "openMedia: unsupported category " + c, null);
            ProtoNode jcp = new ProtoNode();
            if (tabNameOrNull != null && !tabNameOrNull.isEmpty()) jcp.setString(J_TAB, tabNameOrNull);
            jcp.setVarint(J_CATEGORY, isd).setVarint(J_ENTRY, 1);
            return launch(from, MEDIA_ACTIVITY, jcp.encode(), false, 0, replace);
        } catch (Throwable t) {
            return fail(from, "openMedia failed", t);
        }
    }

    /** Opens the app's folder picker (move mode) at internal storage; the result arrives as {@link #readPicked}. */
    public static boolean pickFolder(Activity from, int requestCode) {
        try {
            File internal = android.os.Environment.getExternalStorageDirectory();
            List<FolderRef> stack = stackFor(from, internal);
            byte[] hut = buildHut(from, stack, MODE_MOVE, 0);
            byte[] args = new ProtoNode().setMessage(1, ProtoNode.decode(hut)).encode();
            return launch(from, PICKER_ACTIVITY, args, true, requestCode, false);
        } catch (Throwable t) {
            return fail(from, "pickFolder failed", t);
        }
    }

    /** The folder path the picker returned (from onActivityResult's data), or null on any problem. */
    public static String readPicked(Intent result) {
        try {
            if (result == null) return null;
            byte[] bytes = ScreenArgs.readExtra(result, ScreenArgs.PICKER_RESULT);
            if (bytes == null) return null;
            ProtoNode folder = ProtoNode.decode(bytes).getMessage(2);
            if (folder == null) return null;
            String e = folder.getString(K_URI);
            if (e == null || e.isEmpty()) return null;
            return Uri.parse(e).getPath();
        } catch (Throwable t) {
            DiagLog.e(AREA, "readPicked failed", t);
            return null;
        }
    }

    /** Category enum (isd) of the app, or 0 when this category has no media screen. */
    private static int mediaCategory(Category c) {
        if (c == null) return 0;
        switch (c) {
            case DOWNLOADS:
                return 2;
            case IMAGES:
                return 3;
            case VIDEOS:
                return 4;
            case AUDIO:
                return 5;
            case DOCUMENTS:
                return 6;
            default:
                return 0;
        }
    }

    // ---- encoding ----

    /** hut{1: root, 2: [stack...], 4: mode, 5: entry (omitted when 0)}. */
    private static byte[] buildHut(Context ctx, List<FolderRef> stack, int mode, int entry) {
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("empty stack");
        ProtoNode root = folder(ctx, stack, 0);
        ProtoNode hut = new ProtoNode().setMessage(H_ROOT, root);
        for (int i = 0; i < stack.size(); i++) hut.addMessage(H_STACK, folder(ctx, stack, i));
        hut.setVarint(H_MODE, mode);
        if (entry != 0) hut.setVarint(H_ENTRY, entry);
        return hut.encode();
    }

    /** kqg for stack[i]: a root has f = e, a child has f = the parent's URI (P0 report 1.4 / 1.5). */
    private static ProtoNode folder(Context ctx, List<FolderRef> stack, int i) {
        FolderRef ref = stack.get(i);
        ProtoNode k = new ProtoNode();
        if (i == 0) {
            int res = rootNameRes(ctx, ref.storageKind());
            if (res != 0) k.setVarint(K_NAME_RES, res);
            else k.setString(K_NAME, ref.title());
            k.setString(K_URI, ref.uri()).setString(K_EXISTS_URI, ref.uri());
        } else {
            k.setString(K_NAME, ref.title()).setString(K_URI, ref.uri())
                    .setString(K_EXISTS_URI, stack.get(i - 1).uri());
        }
        k.setVarint(K_KIND, ref.storageKind());
        long modified = modifiedMillis(ref.uri());
        if (modified > 0) {
            k.setMessage(K_MODIFIED, new ProtoNode().setVarint(1, modified / 1000)
                    .setVarint(2, (modified % 1000) * 1_000_000L));
        }
        return k;
    }

    private static long modifiedMillis(String uri) {
        try {
            if (uri.startsWith("file:")) return new File(Uri.parse(uri).getPath()).lastModified();
        } catch (Throwable ignored) {
            // no timestamp
        }
        return 0;
    }

    // ---- validation and launch ----

    private static boolean launch(Activity from, String className, byte[] args, boolean forResult, int requestCode,
                                  boolean replace) {
        String problem = validate(from, className, args);
        if (problem != null) return fail(from, "refused " + simple(className) + ": " + problem, null);
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(from, className));
        intent.putExtra(ScreenArgs.EXTRA, ScreenArgs.wrap(args));
        if (from.getPackageManager().resolveActivity(intent, 0) == null) {
            return fail(from, "refused " + simple(className) + ": activity not declared in the manifest", null);
        }
        DiagLog.i(AREA, "launch " + simple(className) + "\n" + ProtoNode.decode(args).dump());
        if (forResult) {
            from.startActivityForResult(intent, requestCode);
        } else {
            from.startActivity(intent);
            if (replace) from.finish();
        }
        return true;
    }

    /** Returns null when the bytes look safe for the screen, else a short reason. Never throws. */
    private static String validate(Context ctx, String className, byte[] args) {
        try {
            Class.forName(className, false, OriginalScreens.class.getClassLoader());
        } catch (Throwable t) {
            return "class not found";
        }
        try {
            if (args == null) return "no arguments";
            ProtoNode top = ProtoNode.decode(args);
            if (MEDIA_ACTIVITY.equals(className)) return validateMedia(top);
            return validateBrowser(top, PICKER_ACTIVITY.equals(className));
        } catch (Throwable t) {
            return "arguments do not decode (" + t + ")";
        }
    }

    private static String validateMedia(ProtoNode jcp) {
        long category = jcp.getVarint(J_CATEGORY, 0);
        if (category < 2 || category > 6) return "bad category " + category; // what openMedia can send
        return null;
    }

    private static String validateBrowser(ProtoNode wrapper, boolean picker) {
        ProtoNode hut = wrapper.getMessage(1);
        if (hut == null) return "no hut";
        long mode = hut.getVarint(H_MODE, 0);
        if (mode < (picker ? 2 : 1) || mode > 4) return "bad mode " + mode;
        ProtoNode root = hut.getMessage(H_ROOT);
        if (root == null) return "no root";
        String problem = checkFolder(root);
        if (problem != null) return "root: " + problem;
        List<ProtoNode> stack = hut.getMessages(H_STACK);
        if (stack.isEmpty()) return "empty stack";
        if (!root.getString(K_URI).equals(stack.get(0).getString(K_URI))) return "stack does not start at the root";
        for (int i = 0; i < stack.size(); i++) {
            problem = checkFolder(stack.get(i));
            if (problem != null) return "stack[" + i + "]: " + problem;
            ProtoNode k = stack.get(i);
            if (i == 0) {
                if (!k.getString(K_URI).equals(k.getString(K_EXISTS_URI))) return "stack[0]: f differs from e";
                continue;
            }
            // Linked: f is the parent's e, and (for file URIs) e is a direct child of the parent's folder.
            ProtoNode parent = stack.get(i - 1);
            if (!k.getString(K_EXISTS_URI).equals(parent.getString(K_URI))) return "stack[" + i + "]: f is not the parent";
            String path = k.getString(K_URI).startsWith("file:") ? Uri.parse(k.getString(K_URI)).getPath() : null;
            String parentPath = parent.getString(K_URI).startsWith("file:")
                    ? Uri.parse(parent.getString(K_URI)).getPath() : null;
            if (path != null && parentPath != null && !parentPath.equals(new File(path).getParent())) {
                return "stack[" + i + "]: not a direct child of the previous folder";
            }
        }
        return null;
    }

    /** e, f and a name must be present; a file:// folder must exist as a directory. */
    private static String checkFolder(ProtoNode kqg) {
        String e = kqg.getString(K_URI);
        String f = kqg.getString(K_EXISTS_URI);
        if (e == null || e.isEmpty()) return "no uri";
        if (f == null || f.isEmpty()) return "no exists-uri";
        if (!kqg.has(K_NAME) && !kqg.has(K_NAME_RES)) return "no name";
        if (kqg.has(K_NAME) && kqg.getString(K_NAME).isEmpty()) return "empty name";
        for (String uri : new String[]{e, f}) {
            if (uri.startsWith("file:")) {
                String path = Uri.parse(uri).getPath();
                if (path == null || !new File(path).isDirectory()) return "not a directory: " + uri;
            }
        }
        return null;
    }

    private static volatile Boolean stackHook;

    /** Whether the patched onCreate hook (a declared "modKitPlaceInitial" on the storage activity) exists. */
    private static boolean stackHookPresent() {
        Boolean cached = stackHook;
        if (cached != null) return cached;
        boolean found = false;
        try {
            for (Method m : Class.forName(STORAGE_ACTIVITY, false, OriginalScreens.class.getClassLoader())
                    .getDeclaredMethods()) {
                if (m.getName().equals("modKitPlaceInitial")) {
                    found = true;
                    break;
                }
            }
        } catch (Throwable t) {
            DiagLog.e(AREA, "cannot inspect the storage activity", t);
        }
        stackHook = found;
        return found;
    }

    private static boolean fail(Activity from, String message, Throwable t) {
        return fail(from, message, t, "画面を開けませんでした（診断ログを確認してください）",
                "Could not open the screen (see the diagnostic log)");
    }

    private static boolean fail(Activity from, String message, Throwable t, String ja, String en) {
        DiagLog.e(AREA, message, t);
        try {
            Toast.makeText(from, SettingsHooks.isJapanese(from) ? ja : en, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // nothing more to do
        }
        return false;
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    // ---- diagnostics: a broken request that must be refused ----

    /** Builds a storage-browser request whose folder does not exist; must not launch. Returns true when refused. */
    public static boolean selfTestBrokenParam(Activity from) {
        try {
            String uri = Uri.fromFile(new File("/storage/emulated/0/FilesPlus-no-such-folder")).toString();
            FolderRef bogus = new FolderRef(uri, "missing", KIND_INTERNAL);
            return !openStorage(from, Collections.singletonList(bogus), false);
        } catch (Throwable t) {
            DiagLog.e(AREA, "broken-param self test failed", t);
            return false;
        }
    }

    // ---- AdvancedBrowsingActivity.onCreate hook (patch "Open folder stack") ----

    /**
     * Called by the patched onCreate instead of calling the peer's "place the first fragment" method straight away.
     * Returns the list of hut messages to place one after another (stack prefixes of length 1..k, the last one is
     * {@code hut} itself) when the stack has more than one entry, otherwise null: then the patch calls the original
     * method once with the original hut, exactly as the app does. Never throws.
     */
    public static Object[] truncatedCopies(Object hut) {
        try {
            if (hut == null) return null;
            Class<?> type = hut.getClass();
            byte[] bytes = ScreenArgs.unwrap(wrapMessage(hut));
            if (bytes == null) return null;
            int k = ProtoNode.decode(bytes).getMessages(H_STACK).size();
            if (k <= 1) return null;
            DiagLog.i(AREA, "folder stack of " + k + ": placing " + k + " fragments");
            Object prototype = defaultInstance(type);
            if (prototype == null) return null;
            Object[] out = (Object[]) java.lang.reflect.Array.newInstance(type, k);
            for (int i = 1; i < k; i++) {
                byte[] part = ProtoNode.decode(bytes).truncateRepeated(H_STACK, i).encode();
                Object copy = parse(prototype, part);
                if (copy == null || copy.getClass() != type) return null;
                out[i - 1] = copy;
            }
            out[k - 1] = hut;
            return out;
        } catch (Throwable t) {
            DiagLog.e(AREA, "truncatedCopies failed; using the original single placement", t);
            return null;
        }
    }

    /** new ProtoParsers$InternalDontUse(null, message): the message in the app's own Parcelable wrapper. */
    private static Parcelable wrapMessage(Object message) throws ReflectiveOperationException {
        Class<?> wrapper = Class.forName("com.google.protobuf.contrib.android.ProtoParsers$InternalDontUse");
        for (Constructor<?> c : wrapper.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0] == byte[].class && p[1].isInstance(message)) {
                return (Parcelable) c.newInstance(null, message);
            }
        }
        throw new NoSuchMethodException("InternalDontUse(byte[], message)");
    }

    /** The message class's default instance: its public static final field of its own type. */
    private static Object defaultInstance(Class<?> type) throws IllegalAccessException {
        for (Field f : type.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) && f.getType() == type) {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) return v;
            }
        }
        return null;
    }

    /**
     * Parses bytes into a message of the prototype's class with the wrapper's two-argument parse method
     * (prototype, registry). The prototype must be the default instance: the parser merges it into the result.
     */
    private static Object parse(Object prototype, byte[] bytes) throws ReflectiveOperationException {
        Object wrapper = ScreenArgs.wrap(bytes).getParcelable(ScreenArgs.KEY);
        for (Method m : wrapper.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getDeclaringClass() == wrapper.getClass() && p.length == 2 && !Modifier.isStatic(m.getModifiers())
                    && p[0].isInstance(prototype) && !p[1].isPrimitive() && p[1] != Object.class
                    && m.getReturnType().isInstance(prototype)) {
                Constructor<?> registry = p[1].getDeclaredConstructor();
                registry.setAccessible(true);
                return m.invoke(wrapper, prototype, registry.newInstance());
            }
        }
        throw new NoSuchMethodException("InternalDontUse.parse(message, registry)");
    }
}
