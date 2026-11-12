package app.filesplus.extension.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ImpersonationLevel;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.share.NamedPipe;
import com.hierynomus.smbj.share.PipeShare;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Minimal DCE/RPC client for the srvsvc call NetrShareEnum (opnum 15, level 1) over the "srvsvc" named pipe of
 * IPC$. It replaces a dependency on smbj-rpc, whose transitive libraries (Guava and others) would not fit the
 * single-dex limit. Only what this one call needs is implemented: connection-oriented bind (NDR, no auth),
 * one request, fragment reassembly. The encoder and parser are plain Java and testable without a server.
 */
public final class SmbShareEnum {
    /** srvsvc type: low 28 bits are the kind, 0 is a disk share; bit 31 marks special (administrative) shares. */
    static final int STYPE_DISKTREE = 0;
    /** MS-SRVS STYPE_MASK: the kind is the low byte; cluster/DFS/continuous-availability flags sit above it. */
    private static final int STYPE_KIND_MASK = 0xFF;

    private static final int PTYPE_REQUEST = 0;
    private static final int PTYPE_RESPONSE = 2;
    private static final int PTYPE_FAULT = 3;
    private static final int PTYPE_BIND = 11;
    private static final int PTYPE_BIND_ACK = 12;
    private static final int PTYPE_BIND_NAK = 13;
    private static final int PFC_FIRST_LAST = 0x03;
    private static final int PFC_FIRST_FRAG = 0x01;
    private static final int PFC_LAST_FRAG = 0x02;
    private static final int REQUEST_CALL_ID = 2;
    private static final int HEADER_SIZE = 16;
    private static final int RESPONSE_STUB_OFFSET = 24;
    /** Advertised fragment size: every response fragment then fits in one IOCTL/READ reply. */
    private static final int MAX_FRAG = 4280;
    private static final int READ_SIZE = 8192;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;
    private static final int OPNUM_NET_SHARE_ENUM = 15;
    private static final int FAULT_ACCESS_DENIED = 0x00000005;
    private static final int WERROR_ACCESS_DENIED = 5;

    // srvsvc 3.0 and NDR 2.0 as they appear on the wire (the first three UUID fields are little-endian).
    private static final byte[] SRVSVC_UUID = {(byte) 0xc8, 0x4f, 0x32, 0x4b, 0x70, 0x16, (byte) 0xd3, 0x01, 0x12, 0x78,
            0x5a, 0x47, (byte) 0xbf, 0x6e, (byte) 0xe1, (byte) 0x88};
    private static final byte[] NDR_UUID = {0x04, 0x5d, (byte) 0x88, (byte) 0x8a, (byte) 0xeb, 0x1c, (byte) 0xc9, 0x11,
            (byte) 0x9f, (byte) 0xe8, 0x08, 0x00, 0x2b, 0x10, 0x48, 0x60};

    private SmbShareEnum() {}

    /** One entry of the server's share list. */
    public static final class ShareInfo {
        public final String name;
        public final int type;
        public final String remark;

        ShareInfo(String name, int type, String remark) {
            this.name = name;
            this.type = type;
            this.remark = remark;
        }

        /** Disk share that is not administrative: not printer/device/IPC, not special, name not ending in '$'. */
        public boolean isBrowsable() {
            return (type & STYPE_KIND_MASK) == STYPE_DISKTREE && (type & 0x80000000) == 0
                    && !name.endsWith("$");
        }
    }

    /** The two pipe operations the protocol needs; lets tests drive the parser without a server. */
    interface Pipe {
        /** Writes one message and returns the first reply message. */
        byte[] transact(byte[] request) throws IOException;

        /** Reads the next message into the buffer; returns the byte count (0 or less means nothing arrived). */
        int read(byte[] buffer) throws IOException;
    }

    /**
     * Lists the shares of the server behind an IPC$ tree. Throws {@link SmbErrors.ShareListException} when the
     * server refuses or does not offer the list; other failures are IOExceptions or smbj runtime exceptions.
     */
    public static List<ShareInfo> list(PipeShare ipc, String host) throws IOException {
        EnumSet<AccessMask> access = EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_WRITE_DATA,
                AccessMask.FILE_APPEND_DATA, AccessMask.FILE_READ_EA, AccessMask.FILE_WRITE_EA,
                AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.READ_CONTROL,
                AccessMask.SYNCHRONIZE);
        NamedPipe pipe = null;
        try {
            pipe = ipc.open("srvsvc", SMB2ImpersonationLevel.Impersonation, access, null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
            final NamedPipe open = pipe;
            return list(new Pipe() {
                @Override
                public byte[] transact(byte[] request) {
                    return open.transact(request);
                }

                @Override
                public int read(byte[] buffer) {
                    return open.read(buffer);
                }
            }, host);
        } catch (RuntimeException e) {
            // Access denied or a missing srvsvc pipe: the server just does not hand out its share list.
            SmbErrors.ShareListException refused = SmbErrors.asShareListFailure(e);
            if (refused != null) throw refused;
            throw e;
        } finally {
            // A failing close must not turn a finished enumeration into an error.
            if (pipe != null) {
                try {
                    pipe.close();
                } catch (Throwable ignored) {
                    // The pipe handle goes away with the session anyway.
                }
            }
        }
    }

    static List<ShareInfo> list(Pipe pipe, String host) throws IOException {
        checkBindAck(new PduReader(pipe, pipe.transact(bindPdu(1))).next());
        PduReader reader = new PduReader(pipe, pipe.transact(enumRequestPdu(REQUEST_CALL_ID, "\\\\" + host)));
        ByteArrayOutputStream stub = new ByteArrayOutputStream();
        boolean first = true;
        while (true) {
            byte[] pdu = reader.next();
            int type = pdu[2] & 0xFF;
            if (le32(pdu, 12) != REQUEST_CALL_ID) throw new IOException("RPC reply belongs to another call");
            if (first && (pdu[3] & PFC_FIRST_FRAG) == 0) throw new IOException("RPC reply does not start with a first fragment");
            first = false;
            if (type == PTYPE_FAULT) {
                int status = pdu.length >= 28 ? le32(pdu, 24) : -1;
                if (status == FAULT_ACCESS_DENIED) throw new SmbErrors.ShareListException("Access denied by the server");
                throw new SmbErrors.ShareListException(String.format(Locale.ROOT, "RPC fault 0x%08X", status));
            }
            if (type != PTYPE_RESPONSE) throw new IOException("Unexpected RPC packet type " + type);
            if (pdu.length < RESPONSE_STUB_OFFSET) throw new IOException("Short RPC response");
            stub.write(pdu, RESPONSE_STUB_OFFSET, pdu.length - RESPONSE_STUB_OFFSET);
            if ((pdu[3] & PFC_LAST_FRAG) != 0) break;
        }
        return parseEnumStub(stub.toByteArray());
    }

    /** Sort order for display: case-insensitive by name. */
    public static void sortByName(List<ShareInfo> shares) {
        Collections.sort(shares, (a, b) -> a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT)));
    }

    // --- encoding ---

    static byte[] bindPdu(int callId) {
        ByteBuffer b = buffer(72);
        header(b, PTYPE_BIND, 72, callId);
        b.putShort((short) MAX_FRAG).putShort((short) MAX_FRAG).putInt(0); // max xmit/recv fragment, assoc group
        b.put((byte) 1).put((byte) 0).putShort((short) 0); // one context element + padding
        b.putShort((short) 0).put((byte) 1).put((byte) 0); // context id, one transfer syntax
        b.put(SRVSVC_UUID).putInt(3); // abstract syntax: srvsvc 3.0
        b.put(NDR_UUID).putInt(2); // transfer syntax: NDR 2.0
        return b.array();
    }

    static byte[] enumRequestPdu(int callId, String serverName) {
        byte[] name = (serverName + "\0").getBytes(StandardCharsets.UTF_16LE);
        int chars = name.length / 2;
        int namePad = (4 - name.length % 4) % 4;
        // ServerName ptr + string, InfoStruct (level, switch, ptr, entries read, buffer ptr), max length, resume ptr
        int stubLength = 4 + 12 + name.length + namePad + 20 + 4 + 4;
        int total = HEADER_SIZE + 8 + stubLength;
        ByteBuffer b = buffer(total);
        header(b, PTYPE_REQUEST, total, callId);
        b.putInt(stubLength).putShort((short) 0).putShort((short) OPNUM_NET_SHARE_ENUM); // alloc hint, context, opnum
        b.putInt(0x00020000); // ServerName: unique pointer
        b.putInt(chars).putInt(0).putInt(chars).put(name);
        for (int i = 0; i < namePad; i++) b.put((byte) 0);
        b.putInt(1).putInt(1).putInt(0x00020004); // InfoStruct: level 1, union tag 1, pointer to the container
        b.putInt(0).putInt(0); // container: EntriesRead 0, Buffer NULL
        b.putInt(0xFFFFFFFF); // PreferedMaximumLength: everything
        b.putInt(0); // ResumeHandle: NULL
        return b.array();
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void header(ByteBuffer b, int ptype, int fragLength, int callId) {
        b.put((byte) 5).put((byte) 0).put((byte) ptype).put((byte) PFC_FIRST_LAST);
        b.put((byte) 0x10).put((byte) 0).put((byte) 0).put((byte) 0); // little-endian, ASCII, IEEE floats
        b.putShort((short) fragLength).putShort((short) 0).putInt(callId); // no authentication
    }

    // --- decoding ---

    static void checkBindAck(byte[] pdu) throws IOException {
        int type = pdu[2] & 0xFF;
        if (type == PTYPE_BIND_NAK) throw new SmbErrors.ShareListException("The server rejected the share list service");
        if (type != PTYPE_BIND_ACK) throw new IOException("Unexpected RPC packet type " + type);
        // max xmit/recv (4), assoc group (4), secondary address length (2) + string, padding to 4, result count
        int position = HEADER_SIZE + 8;
        if (pdu.length < position + 2) throw new IOException("Short RPC bind reply");
        position += 2 + u16(pdu, position);
        position = (position + 3) & ~3;
        if (pdu.length < position + 8 || (pdu[position] & 0xFF) < 1) throw new IOException("Short RPC bind reply");
        if (u16(pdu, position + 4) != 0) throw new SmbErrors.ShareListException("The server does not offer the share list service");
    }

    /** Parses the stub of a NetrShareEnum response (level 1) and checks its trailing Windows error code. */
    static List<ShareInfo> parseEnumStub(byte[] stub) throws IOException {
        if (stub.length < 4) throw new IOException("Short share list reply");
        int werror = le32(stub, stub.length - 4);
        if (werror == WERROR_ACCESS_DENIED) throw new SmbErrors.ShareListException("Access denied by the server");
        if (werror != 0) throw new SmbErrors.ShareListException(String.format(Locale.ROOT, "Share list error %d", werror));
        try {
            Reader r = new Reader(stub);
            r.skip(8); // level and union tag
            List<ShareInfo> out = new ArrayList<>();
            if (r.u32() != 0) { // pointer to the container
                int count = r.u32();
                int bufferPointer = r.u32();
                if (bufferPointer != 0) {
                    if (r.u32() != count) throw new IOException("Malformed share list");
                    if (count < 0 || count > MAX_ENTRIES || count > stub.length / 12) throw new IOException("Malformed share list");
                    int[] namePointers = new int[count];
                    int[] types = new int[count];
                    int[] remarkPointers = new int[count];
                    for (int i = 0; i < count; i++) {
                        namePointers[i] = r.u32();
                        types[i] = r.u32();
                        remarkPointers[i] = r.u32();
                    }
                    for (int i = 0; i < count; i++) {
                        String name = namePointers[i] == 0 ? "" : r.string();
                        String remark = remarkPointers[i] == 0 ? "" : r.string();
                        out.add(new ShareInfo(name, types[i], remark));
                    }
                }
            }
            return out;
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("Malformed share list");
        }
    }

    /** Little-endian reader over a stub with NDR string and alignment helpers. */
    private static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = data;
        }

        void skip(int count) {
            need(count);
            position += count;
        }

        int u32() {
            need(4);
            int value = le32(data, position);
            position += 4;
            return value;
        }

        /** Conformant and varying string: max count, offset, actual count, UTF-16LE (NUL included), padding. */
        String string() throws IOException {
            u32(); // max count
            int offset = u32();
            int actual = u32();
            if (offset != 0 || actual < 0 || actual > 4096) throw new IOException("Malformed share list");
            need(actual * 2);
            String s = new String(data, position, actual * 2, StandardCharsets.UTF_16LE);
            position += actual * 2;
            position = (position + 3) & ~3;
            int end = s.indexOf('\0');
            return end >= 0 ? s.substring(0, end) : s;
        }

        private void need(int count) {
            if (count < 0 || position + count > data.length) throw new IndexOutOfBoundsException();
        }
    }

    /** Splits the stream of reply messages into whole PDUs, reading further messages from the pipe as needed. */
    private static final class PduReader {
        private final Pipe pipe;
        private final byte[] chunk = new byte[READ_SIZE];
        /** Unconsumed bytes are data[start, end); the array grows geometrically and is compacted when it must grow. */
        private byte[] data;
        private int start;
        private int end;
        private int total;

        PduReader(Pipe pipe, byte[] first) {
            this.pipe = pipe;
            this.data = new byte[Math.max(READ_SIZE, first == null ? 0 : first.length)];
            if (first != null) {
                System.arraycopy(first, 0, data, 0, first.length);
                end = first.length;
            }
            total = end;
        }

        byte[] next() throws IOException {
            fill(HEADER_SIZE);
            if (data[start] != 5) throw new IOException("Not an RPC reply");
            if ((data[start + 4] & 0x10) == 0) throw new IOException("Big-endian RPC replies are not supported");
            int length = u16(data, start + 8);
            if (length < HEADER_SIZE) throw new IOException("Malformed RPC reply");
            fill(length);
            byte[] pdu = new byte[length];
            System.arraycopy(data, start, pdu, 0, length);
            start += length;
            return pdu;
        }

        /** Makes at least size unconsumed bytes available, reading further messages from the pipe. */
        private void fill(int size) throws IOException {
            while (end - start < size) {
                int count = pipe.read(chunk);
                if (count <= 0) throw new IOException("Unexpected end of the RPC reply");
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("RPC reply is too large");
                if (end + count > data.length) {
                    int live = end - start;
                    byte[] grown = new byte[Math.max(data.length * 2, live + count)];
                    System.arraycopy(data, start, grown, 0, live);
                    data = grown;
                    start = 0;
                    end = live;
                }
                System.arraycopy(chunk, 0, data, end, count);
                end += count;
            }
        }
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }
}
