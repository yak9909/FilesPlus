package app.filesplus.extension.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.reflect.Constructor;
import java.util.Arrays;

/**
 * Wraps / unwraps the proto bytes the app's own screens take as Intent extra "activity_params"
 * (Bundle{"protoparsers": ProtoParsers$InternalDontUse}). Only wrap/unwrap/readExtra touch Android classes.
 */
public final class ScreenArgs {
    public static final String EXTRA = "activity_params";
    public static final String KEY = "protoparsers";
    public static final String PICKER_RESULT = "ADVANCED_BROWSING_PICKER_RESULT";

    private static final String CLASS = "com.google.protobuf.contrib.android.ProtoParsers$InternalDontUse";
    private static Constructor<?> ctor;

    private ScreenArgs() {
    }

    /** Bundle{"protoparsers": new InternalDontUse(bytes, null)}. */
    public static Bundle wrap(byte[] proto) {
        try {
            Bundle b = new Bundle();
            b.putParcelable(KEY, (Parcelable) constructor().newInstance(proto.clone(), null));
            return b;
        } catch (Throwable t) {
            throw new IllegalStateException("cannot create ProtoParsers wrapper", t);
        }
    }

    private static synchronized Constructor<?> constructor() throws ReflectiveOperationException {
        if (ctor == null) {
            for (Constructor<?> c : Class.forName(CLASS).getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[0] == byte[].class) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) throw new NoSuchMethodException(CLASS + "(byte[], *)");
        }
        return ctor;
    }

    /**
     * Accepts the Bundle form or the bare Parcelable. Parcel layout of the wrapper (verified in jadx):
     * writeInt(len) then writeByteArray(bytes), which itself writes len again followed by the bytes.
     */
    public static byte[] unwrap(Parcelable p) {
        Parcel parcel = null;
        try {
            if (p instanceof Bundle) {
                Bundle bundle = (Bundle) p;
                // Lazy reads with a null loader throw BadParcelableException on Android 13+.
                bundle.setClassLoader(ScreenArgs.class.getClassLoader());
                p = bundle.getParcelable(KEY);
            }
            if (p == null || !CLASS.equals(p.getClass().getName())) return null;
            parcel = Parcel.obtain();
            p.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            int len = parcel.readInt();
            byte[] bytes = parcel.createByteArray();
            return bytes != null && bytes.length == len ? bytes : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (parcel != null) parcel.recycle();
        }
    }

    public static byte[] readExtra(Intent i, String key) {
        try {
            i.setExtrasClassLoader(ScreenArgs.class.getClassLoader());
            return unwrap(i.getParcelableExtra(key));
        } catch (Throwable t) {
            return null;
        }
    }

    /** On-device check of wrap -> real Parcel round trip -> unwrap. Never throws. */
    public static String deviceSelfTest() {
        Parcel parcel = null;
        try {
            byte[] sample = b(0x0a, 0x04, 0x08, 0x01, 0x10, 0x02, 0x12, 0x01, 0x61);
            Bundle wrapped = wrap(sample);
            parcel = Parcel.obtain();
            parcel.writeBundle(wrapped);
            parcel.setDataPosition(0);
            Bundle back = parcel.readBundle(ScreenArgs.class.getClassLoader());
            byte[] got = back == null ? null : unwrap(back);
            boolean ok = Arrays.equals(sample, got) && Arrays.equals(sample, unwrap(wrapped));
            return (ok ? "PASS" : "FAIL") + " wrap/Parcel/unwrap round trip";
        } catch (Throwable t) {
            return "FAIL wrap/Parcel/unwrap round trip: " + t;
        } finally {
            if (parcel != null) parcel.recycle();
        }
    }

    // ---- self test (pure Java; run from diagnostics later) ----

    public static String selfTest() {
        StringBuilder sb = new StringBuilder();
        int[] fail = {0};
        try {
            roundTrips(sb, fail);
            storageTemplate(sb, fail);
            malformed(sb, fail);
            mutators(sb, fail);
        } catch (Throwable t) {
            check(sb, fail, "unexpected exception " + t, false);
        }
        sb.append(fail[0] == 0 ? "ALL PASS" : fail[0] + " FAILED");
        return sb.toString();
    }

    private static void check(StringBuilder sb, int[] fail, String name, boolean ok) {
        sb.append(ok ? "PASS " : "FAIL ").append(name).append('\n');
        if (!ok) fail[0]++;
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    private static void roundTrips(StringBuilder sb, int[] fail) {
        byte[][] cases = {
                b(),
                b(0x08, 0x96, 0x01),                                  // varint 150 (multi-byte)
                b(0x08, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01), // -1 as 10-byte varint
                b(0x09, 1, 2, 3, 4, 5, 6, 7, 8),                      // fixed64
                b(0x15, 1, 2, 3, 4),                                  // fixed32
                b(0x12, 0x02, 0x68, 0x69),                            // "hi"
                b(0x0a, 0x04, 0x08, 0x01, 0x10, 0x02),                // nested message {1:1,2:2}
                b(0x0a, 0x00),                                        // empty message
                b(0x08, 0x01, 0x08, 0x02, 0x12, 0x01, 0x61, 0x12, 0x01, 0x62), // repeated
                b(0xf8, 0x07, 0x05, 0x08, 0x01),                      // unknown field 127 varint then field 1
        };
        boolean ok = true;
        for (byte[] c : cases) {
            try {
                ok &= Arrays.equals(c, ProtoNode.decode(c).encode());
            } catch (Throwable t) {
                ok = false;
            }
        }
        check(sb, fail, "decode/encode round-trip (" + cases.length + " cases)", ok);
    }

    /** Template of the storage browser args: hus{1: hut{1: root, 2: [root], 4: 1, 5: 2}}. */
    private static void storageTemplate(StringBuilder sb, int[] fail) {
        String uri = "file:///storage/emulated/0";
        ProtoNode root = new ProtoNode().setString(2, uri).setString(3, uri).setVarint(7, 0x7f130270L)
                .setVarint(10, 0).setVarint(11, 123456789012L)
                .setMessage(12, new ProtoNode().setVarint(1, 1700000000L).setVarint(2, 5));
        ProtoNode hut = new ProtoNode().setMessage(1, root).addMessage(2, root).setVarint(4, 1).setVarint(5, 2);
        byte[] bytes = new ProtoNode().setMessage(1, hut).encode();

        ProtoNode back = ProtoNode.decode(bytes);
        ProtoNode h = back.getMessage(1);
        boolean ok = h != null && h.getVarint(4, -1) == 1 && h.getVarint(5, -1) == 2 && !h.has(3);
        ProtoNode r1 = ok ? h.getMessage(1) : null;
        java.util.List<ProtoNode> stack = ok ? h.getMessages(2) : null;
        ok = ok && r1 != null && stack.size() == 1;
        if (ok) {
            ProtoNode r2 = stack.get(0);
            ok = uri.equals(r1.getString(2)) && uri.equals(r1.getString(3))
                    && r1.getVarint(7, -1) == 0x7f130270L && r1.getVarint(10, -1) == 0
                    && r1.getVarint(11, -1) == 123456789012L
                    && r1.getMessage(12).getVarint(1, -1) == 1700000000L
                    && r1.getMessage(12).getVarint(2, -1) == 5
                    && Arrays.equals(r1.encode(), r2.encode())
                    && Arrays.equals(bytes, back.encode());
        }
        check(sb, fail, "storage browser template", ok);
        check(sb, fail, "dump never throws", back.dump().contains("file:///storage/emulated/0"));
    }

    private static void malformed(StringBuilder sb, int[] fail) {
        byte[][] bad = {
                b(0x08),                    // tag without value
                b(0x08, 0x80),              // truncated varint
                b(0x12, 0x05, 0x61),        // length beyond end
                b(0x0b),                    // group start (wire type 3)
                b(0x0c),                    // group end (wire type 4)
                b(0x09, 1, 2, 3),           // truncated fixed64
                b(0x0e),                    // wire type 6
                b(0x0f),                    // wire type 7
                b(0x00, 0x00),              // field number 0
                b(0x08, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01), // 11-byte varint
                b(0x12, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01), // length overflows to negative
        };
        boolean ok = true;
        for (byte[] x : bad) {
            try {
                ProtoNode.decode(x);
                ok = false;
            } catch (IllegalArgumentException expected) {
                // ok
            } catch (Throwable t) {
                ok = false;
            }
        }
        check(sb, fail, "malformed input throws IllegalArgumentException", ok);
    }

    private static void mutators(StringBuilder sb, int[] fail) {
        ProtoNode n = new ProtoNode().addVarint(1, 10).addVarint(2, 20).addVarint(1, 30).setString(3, "x");
        n.setVarint(1, 99);
        boolean ok = Arrays.equals(n.encode(), b(0x08, 99, 0x10, 20, 0x1a, 1, 'x'));
        check(sb, fail, "set replaces in place, first position kept", ok);

        ProtoNode r = new ProtoNode().addMessage(2, new ProtoNode().setVarint(1, 1))
                .addMessage(2, new ProtoNode().setVarint(1, 2)).addMessage(2, new ProtoNode().setVarint(1, 3))
                .setVarint(4, 1);
        r.truncateRepeated(2, 2);
        ok = r.getMessages(2).size() == 2 && r.getMessages(2).get(1).getVarint(1, 0) == 2 && r.getVarint(4, 0) == 1;
        r.remove(2);
        ok = ok && !r.has(2) && r.has(4);
        check(sb, fail, "truncateRepeated keeps first n, remove", ok);

        check(sb, fail, "string UTF-8", "あ".equals(new ProtoNode().setString(1, "あ").getString(1))
                && new ProtoNode().setString(1, "あ").getBytes(1).length == 3);
    }
}
