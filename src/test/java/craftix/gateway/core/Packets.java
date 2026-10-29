package craftix.gateway.core;

import java.nio.ByteBuffer;

/** Real packets for the tests, built here rather than by Ipv4, and read back. */
final class Packets {
    static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;

    /** Linux's options on a SYN: MSS 1460, SACK permitted, timestamps, window scale 7. */
    static final byte[] LINUX_SYN_OPTIONS = {
        2, 4, 0x05, (byte) 0xb4, 4, 2, 8, 10, 0x12, 0x34, 0x56, 0x78, 0, 0, 0, 0, 1, 3, 3, 7,
    };

    private Packets() {
    }

    static byte[] ip(String src, String dst, int proto, int ttl, byte[] payload) {
        var b = ByteBuffer.allocate(20 + payload.length);
        b.put((byte) 0x45).put((byte) 0).putShort((short) (20 + payload.length)).putShort((short) 0x1234)
            .putShort((short) 0x4000).put((byte) ttl).put((byte) proto).putShort((short) 0)
            .putInt((int) Ipv4.parseIp(src)).putInt((int) Ipv4.parseIp(dst)).put(payload);
        var p = b.array();
        int sum = sum(p, 0, 20, 0);
        p[10] = (byte) (sum >> 8);
        p[11] = (byte) sum;
        return p;
    }

    static byte[] tcp(String src, int sport, String dst, int dport, long seq, long ack, int flags, int wnd,
                      byte[] options, byte[] data) {
        int len = 20 + options.length + data.length;
        var b = ByteBuffer.allocate(len);
        b.putShort((short) sport).putShort((short) dport).putInt((int) seq).putInt((int) ack)
            .put((byte) (((20 + options.length) / 4) << 4)).put((byte) flags).putShort((short) wnd)
            .putShort((short) 0).putShort((short) 0).put(options).put(data);
        var s = b.array();
        int sum = sum(s, 0, len, pseudo(src, dst, 6, len));
        s[16] = (byte) (sum >> 8);
        s[17] = (byte) sum;
        return ip(src, dst, 6, 64, s);
    }

    static byte[] udp(String src, int sport, String dst, int dport, byte[] data) {
        int len = 8 + data.length;
        var b = ByteBuffer.allocate(len);
        b.putShort((short) sport).putShort((short) dport).putShort((short) len).putShort((short) 0).put(data);
        var s = b.array();
        int sum = sum(s, 0, len, pseudo(src, dst, 17, len));
        s[6] = (byte) (sum >> 8);
        s[7] = (byte) sum;
        return ip(src, dst, 17, 64, s);
    }

    /** ICMP: type, code, the 4 bytes after the checksum, and the rest. */
    static byte[] icmp(String src, String dst, int type, int code, int rest, byte[] body) {
        var b = ByteBuffer.allocate(8 + body.length);
        b.put((byte) type).put((byte) code).putShort((short) 0).putInt(rest).put(body);
        var s = b.array();
        int sum = sum(s, 0, s.length, 0);
        s[2] = (byte) (sum >> 8);
        s[3] = (byte) sum;
        return ip(src, dst, 1, 64, s);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------------------------

    static int u8(byte[] p, int i) {
        return p[i] & 255;
    }

    static int u16(byte[] p, int i) {
        return (u8(p, i) << 8) | u8(p, i + 1);
    }

    static long u32(byte[] p, int i) {
        return ((long) u16(p, i) << 16) | u16(p, i + 2);
    }

    /** Both checksums right (the IP header's, and TCP's, UDP's or ICMP's)? */
    static boolean checksumsOk(byte[] p) {
        int ihl = (p[0] & 15) * 4, total = u16(p, 2), proto = u8(p, 9);
        if (sum(p, 0, ihl, 0) != 0) return false;
        long pseudo = proto == 1 ? 0 : (u32(p, 12) >> 16) + (u32(p, 12) & 0xFFFF) + (u32(p, 16) >> 16)
            + (u32(p, 16) & 0xFFFF) + proto + (total - ihl);
        return sum(p, ihl, total - ihl, pseudo) == 0;
    }

    /** The TCP payload of an IPv4 packet. */
    static byte[] tcpData(byte[] p) {
        int ihl = (p[0] & 15) * 4, off = (u8(p, ihl + 12) >> 4) * 4, total = u16(p, 2);
        var out = new byte[total - ihl - off];
        System.arraycopy(p, ihl + off, out, 0, out.length);
        return out;
    }

    private static long pseudo(String src, String dst, int proto, int len) {
        long s = Ipv4.parseIp(src), d = Ipv4.parseIp(dst);
        return (s >> 16) + (s & 0xFFFF) + (d >> 16) + (d & 0xFFFF) + proto + len;
    }

    /** The ones' complement sum, complemented (0 over a packet with its checksum in it, if that's right). */
    static int sum(byte[] b, int off, int len, long start) {
        long s = start;
        for (int i = 0; i < len; i++) s += (i % 2 == 0 ? (b[off + i] & 255) << 8 : b[off + i] & 255);
        while (s > 0xFFFF) s = (s & 0xFFFF) + (s >>> 16);
        return (int) (~s & 0xFFFF);
    }
}
