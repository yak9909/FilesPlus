package app.filesplus.extension.ui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny protobuf tree: fields are kept in wire order (unknown ones included), so decode(b).encode() returns b
 * for canonical input. Pure Java, no Android and no protobuf library.
 * Wire types: 0 varint, 1 fixed64, 2 length-delimited, 5 fixed32.
 */
public final class ProtoNode {
    private static final class Field {
        final int num;
        final int wt;
        final long num64; // varint / fixed32 / fixed64 value
        final byte[] data; // LEN payload

        Field(int num, int wt, long num64, byte[] data) {
            this.num = num;
            this.wt = wt;
            this.num64 = num64;
            this.data = data;
        }
    }

    private final List<Field> fields = new ArrayList<>();

    public ProtoNode() {
    }

    // ---- decode / encode ----

    public static ProtoNode decode(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("null");
        ProtoNode n = new ProtoNode();
        int[] pos = {0};
        int end = bytes.length;
        while (pos[0] < end) {
            long tag = readVarint(bytes, pos, end);
            long numL = tag >>> 3;
            if (numL <= 0 || numL > Integer.MAX_VALUE) throw new IllegalArgumentException("bad field number");
            int num = (int) numL;
            int wt = (int) (tag & 7);
            switch (wt) {
                case 0:
                    n.fields.add(new Field(num, 0, readVarint(bytes, pos, end), null));
                    break;
                case 1:
                    n.fields.add(new Field(num, 1, readFixed(bytes, pos, end, 8), null));
                    break;
                case 5:
                    n.fields.add(new Field(num, 5, readFixed(bytes, pos, end, 4), null));
                    break;
                case 2: {
                    long len = readVarint(bytes, pos, end);
                    if (len < 0 || len > end - pos[0]) throw new IllegalArgumentException("length beyond end");
                    int l = (int) len;
                    byte[] d = new byte[l];
                    System.arraycopy(bytes, pos[0], d, 0, l);
                    pos[0] += l;
                    n.fields.add(new Field(num, 2, 0, d));
                    break;
                }
                default:
                    throw new IllegalArgumentException("bad wire type " + wt);
            }
        }
        return n;
    }

    public byte[] encode() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Field f : fields) {
            writeVarint(out, ((long) f.num << 3) | f.wt);
            switch (f.wt) {
                case 0:
                    writeVarint(out, f.num64);
                    break;
                case 1:
                    writeFixed(out, f.num64, 8);
                    break;
                case 5:
                    writeFixed(out, f.num64, 4);
                    break;
                default:
                    writeVarint(out, f.data.length);
                    out.write(f.data, 0, f.data.length);
            }
        }
        return out.toByteArray();
    }

    private static long readVarint(byte[] b, int[] pos, int end) {
        long v = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            if (pos[0] >= end) throw new IllegalArgumentException("truncated varint");
            int x = b[pos[0]++];
            v |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) return v;
        }
        throw new IllegalArgumentException("varint too long");
    }

    private static long readFixed(byte[] b, int[] pos, int end, int n) {
        if (end - pos[0] < n) throw new IllegalArgumentException("truncated fixed");
        long v = 0;
        for (int i = 0; i < n; i++) v |= (long) (b[pos[0] + i] & 0xff) << (8 * i);
        pos[0] += n;
        return v;
    }

    private static void writeVarint(java.io.ByteArrayOutputStream out, long v) {
        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static void writeFixed(java.io.ByteArrayOutputStream out, long v, int n) {
        for (int i = 0; i < n; i++) out.write((int) (v >>> (8 * i)) & 0xff);
    }

    // ---- accessors ----

    public boolean has(int field) {
        for (Field f : fields) if (f.num == field) return true;
        return false;
    }

    /** First occurrence wins for singular reads. */
    private Field first(int field) {
        for (Field f : fields) if (f.num == field) return f;
        return null;
    }

    /** Also returns fixed32/fixed64 fields as their raw (unsigned) value. */
    public long getVarint(int field, long dflt) {
        Field f = first(field);
        return f != null && f.wt != 2 ? f.num64 : dflt;
    }

    public byte[] getBytes(int field) {
        Field f = first(field);
        return f != null && f.wt == 2 ? f.data.clone() : null;
    }

    public String getString(int field) {
        Field f = first(field);
        return f != null && f.wt == 2 ? new String(f.data, StandardCharsets.UTF_8) : null;
    }

    /** Throws IllegalArgumentException when the field is not a valid message. */
    public ProtoNode getMessage(int field) {
        Field f = first(field);
        return f != null && f.wt == 2 ? decode(f.data) : null;
    }

    /** Throws IllegalArgumentException when an occurrence is not a valid message. */
    public List<ProtoNode> getMessages(int field) {
        List<ProtoNode> r = new ArrayList<>();
        for (Field f : fields) if (f.num == field && f.wt == 2) r.add(decode(f.data));
        return r;
    }

    // ---- mutators ----

    private ProtoNode set(Field nf) {
        int at = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).num == nf.num) {
                if (at < 0) {
                    at = i;
                    fields.set(i, nf);
                } else {
                    fields.remove(i--);
                }
            }
        }
        if (at < 0) fields.add(nf);
        return this;
    }

    public ProtoNode setVarint(int field, long v) {
        return set(new Field(field, 0, v, null));
    }

    public ProtoNode setBytes(int field, byte[] b) {
        return set(new Field(field, 2, 0, b.clone()));
    }

    public ProtoNode setString(int field, String s) {
        return set(new Field(field, 2, 0, s.getBytes(StandardCharsets.UTF_8)));
    }

    public ProtoNode setMessage(int field, ProtoNode m) {
        return set(new Field(field, 2, 0, m.encode()));
    }

    public ProtoNode addMessage(int field, ProtoNode m) {
        fields.add(new Field(field, 2, 0, m.encode()));
        return this;
    }

    public ProtoNode addVarint(int field, long v) {
        fields.add(new Field(field, 0, v, null));
        return this;
    }

    public ProtoNode remove(int field) {
        for (int i = 0; i < fields.size(); i++) if (fields.get(i).num == field) fields.remove(i--);
        return this;
    }

    /** Keeps only the first {@code keep} occurrences of a repeated field. */
    public ProtoNode truncateRepeated(int field, int keep) {
        int seen = 0;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).num == field && ++seen > keep) fields.remove(i--);
        }
        return this;
    }

    // ---- diagnostics ----

    public String dump() {
        try {
            StringBuilder sb = new StringBuilder();
            dump(sb, 0, 0);
            return sb.toString();
        } catch (Throwable t) {
            return "<dump failed: " + t + ">";
        }
    }

    private void dump(StringBuilder sb, int indent, int depth) {
        for (Field f : fields) {
            for (int i = 0; i < indent; i++) sb.append("  ");
            sb.append(f.num).append(" [w").append(f.wt).append("] ");
            if (f.wt == 2) {
                dumpLen(sb, f.data, indent, depth);
            } else {
                sb.append(f.num64).append('\n');
            }
        }
    }

    private static void dumpLen(StringBuilder sb, byte[] d, int indent, int depth) {
        if (d.length == 0) {
            sb.append("\"\"\n");
            return;
        }
        String text = printable(d);
        if (text == null && depth < 8) {
            try {
                ProtoNode m = decode(d);
                if (m.looksLikeMessage()) {
                    sb.append("{\n");
                    m.dump(sb, indent + 1, depth + 1);
                    for (int i = 0; i < indent; i++) sb.append("  ");
                    sb.append("}\n");
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // not a message
            }
        }
        if (text != null) {
            sb.append('"').append(text.replace("\n", "\\n").replace("\t", "\\t")).append("\"\n");
        } else {
            sb.append("0x");
            int n = Math.min(d.length, 64);
            for (int i = 0; i < n; i++) {
                sb.append(HEX[(d[i] >> 4) & 15]).append(HEX[d[i] & 15]);
            }
            if (d.length > n) sb.append("\u2026(").append(d.length).append(" bytes)");
            sb.append('\n');
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private boolean looksLikeMessage() {
        if (fields.isEmpty()) return false;
        for (Field f : fields) if (f.num > 4096) return false;
        return true;
    }

    /** The bytes as text when they are printable UTF-8, else null. */
    private static String printable(byte[] d) {
        String s = new String(d, StandardCharsets.UTF_8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\t') || c == 0x7f || c == 0xfffd) return null;
        }
        return s;
    }
}
