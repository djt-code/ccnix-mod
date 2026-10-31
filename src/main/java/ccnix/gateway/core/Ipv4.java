package ccnix.gateway.core;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Packets between ccnix's network stack and real IPv4, field for field as lib/net.lua's "Bridging to a real network"
 * lays out.
 * <p>
 * CC hands a packet table to Java as a Map with String keys, numbers as Double, strings as String with one char per
 * byte (Cobalt decodes Latin-1 style), booleans as Boolean. Going the other way, a Map becomes a table, any Number a
 * number and a byte[] a string, byte for byte.
 * <p>
 * What can't cross is dropped: fragments (TUN hands over whole packets unless something upstream fragments), IPv6,
 * UDP payloads that aren't strings, and ICMP code 4 (fragmentation needed: CC's packets are small). TCP options
 * coming in are skipped, and none go out, so a real peer keeps to the default MSS (536) and no window scaling.
 */
public final class Ipv4 {
    public static final long ALL = 0xFFFFFFFFL;
    public static final int TCP = 6, UDP = 17, ICMP = 1;

    private Ipv4() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CC -> real
    // ---------------------------------------------------------------------------------------------------------------

    /** The packet a CC packet table stands for, or null if it can't be one. */
    public static byte[] fromCc(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        Long src = u32(m.get("src")), dst = u32(m.get("dst")), ttl = u32(m.get("ttl"));
        if (src == null || dst == null || ttl == null) return null;
        Long id = u32(m.get("id"));
        Object proto = m.get("proto"), data = m.get("data");
        byte[] body;
        int number;
        if ("tcp".equals(proto)) {
            body = tcpFromCc(data, src, dst);
            number = TCP;
        } else if ("udp".equals(proto)) {
            body = udpFromCc(data, src, dst);
            number = UDP;
        } else if ("icmp".equals(proto)) {
            body = icmpFromCc(data);
            number = ICMP;
        } else {
            return null;
        }
        if (body == null || body.length > 65535 - 20) return null;

        var p = new byte[20 + body.length];
        p[0] = 0x45;
        put16(p, 2, p.length);
        put16(p, 4, id == null ? 0 : (int) (id & 0xFFFF));
        p[8] = (byte) Math.min(ttl, 255);
        p[9] = (byte) number;
        put32(p, 12, src);
        put32(p, 16, dst);
        put16(p, 10, checksum(p, 0, 20, 0));
        System.arraycopy(body, 0, p, 20, body.length);
        return p;
    }

    private static byte[] tcpFromCc(Object o, long src, long dst) {
        if (!(o instanceof Map<?, ?> d)) return null;
        Long sport = u32(d.get("sport")), dport = u32(d.get("dport")), seq = u32(d.get("seq"));
        if (sport == null || dport == null || seq == null) return null;
        Object ackValue = d.get("ack");
        Long ack = ackValue == null ? null : u32(ackValue);
        if (ackValue != null && ack == null) return null;
        var data = bytes(d.get("data"));
        if (data == null) return null;
        Long wnd = u32(d.get("wnd"));

        int flags = (yes(d.get("fin")) ? 1 : 0) | (yes(d.get("syn")) ? 2 : 0) | (yes(d.get("rst")) ? 4 : 0)
            | (data.length > 0 ? 8 : 0) | (ack != null ? 16 : 0);
        var s = new byte[20 + data.length];
        put16(s, 0, (int) (sport & 0xFFFF));
        put16(s, 2, (int) (dport & 0xFFFF));
        put32(s, 4, seq);
        put32(s, 8, ack == null ? 0 : ack);
        s[12] = 5 << 4;
        s[13] = (byte) flags;
        put16(s, 14, wnd == null ? 0 : (int) Math.min(wnd, 65535));
        System.arraycopy(data, 0, s, 20, data.length);
        put16(s, 16, checksum(s, 0, s.length, pseudo(src, dst, TCP, s.length)));
        return s;
    }

    private static byte[] udpFromCc(Object o, long src, long dst) {
        if (!(o instanceof Map<?, ?> d)) return null;
        Long sport = u32(d.get("sport")), dport = u32(d.get("dport"));
        var data = bytes(d.get("data"));
        if (sport == null || dport == null || data == null || data.length > 65535 - 28) return null;
        var s = new byte[8 + data.length];
        put16(s, 0, (int) (sport & 0xFFFF));
        put16(s, 2, (int) (dport & 0xFFFF));
        put16(s, 4, s.length);
        System.arraycopy(data, 0, s, 8, data.length);
        int sum = checksum(s, 0, s.length, pseudo(src, dst, UDP, s.length));
        put16(s, 6, sum == 0 ? 0xFFFF : sum);
        return s;
    }

    private static byte[] icmpFromCc(Object o) {
        if (!(o instanceof Map<?, ?> d)) return null;
        Object type = d.get("type");
        byte[] s;
        if ("echo".equals(type) || "reply".equals(type)) {
            var data = bytes(d.get("data"));
            if (data == null) data = new byte[0];      // (a ping from CC may carry a table, or nothing)
            s = new byte[8 + data.length];
            s[0] = (byte) ("echo".equals(type) ? 8 : 0);
            Long id = u32(d.get("id")), seq = u32(d.get("seq"));
            put16(s, 4, id == null ? 0 : (int) (id & 0xFFFF));
            put16(s, 6, seq == null ? 0 : (int) (seq & 0xFFFF));
            System.arraycopy(data, 0, s, 8, data.length);
        } else if ("unreachable".equals(type) || "timeexceeded".equals(type)) {
            var orig = origFromCc(d.get("orig"));
            if (orig == null) return null;
            s = new byte[8 + orig.length];
            if ("unreachable".equals(type)) {
                s[0] = 3;
                Object code = d.get("code");
                s[1] = (byte) ("net".equals(code) ? 0 : "port".equals(code) ? 3 : "prohibited".equals(code) ? 13 : 1);
            } else {
                s[0] = 11;
            }
            System.arraycopy(orig, 0, s, 8, orig.length);
        } else {
            return null;
        }
        put16(s, 2, checksum(s, 0, s.length, 0));
        return s;
    }

    /** The original packet's header and first 8 bytes, as far as orig tells them. */
    private static byte[] origFromCc(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        Long src = u32(m.get("src")), dst = u32(m.get("dst"));
        if (src == null || dst == null) return null;
        int proto = protoNumber(m.get("proto"));
        var b = new byte[28];
        b[0] = 0x45;
        put16(b, 2, 28);
        b[8] = 1;
        b[9] = (byte) Math.max(proto, 0);
        put32(b, 12, src);
        put32(b, 16, dst);
        put16(b, 10, checksum(b, 0, 20, 0));
        Long sport = u32(m.get("sport")), dport = u32(m.get("dport")), id = u32(m.get("id")), seq = u32(m.get("seq"));
        if (proto == TCP || proto == UDP) {
            put16(b, 20, sport == null ? 0 : (int) (sport & 0xFFFF));
            put16(b, 22, dport == null ? 0 : (int) (dport & 0xFFFF));
            if (proto == TCP) put32(b, 24, seq == null ? 0 : seq);
            else put16(b, 24, 8);
        } else if (proto == ICMP) {
            b[20] = 8;                                  // (CC sends errors only about echoes)
            put16(b, 24, id == null ? 0 : (int) (id & 0xFFFF));
            put16(b, 26, seq == null ? 0 : (int) (seq & 0xFFFF));
        }
        return b;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // real -> CC
    // ---------------------------------------------------------------------------------------------------------------

    /** The CC packet table for a real packet, or null if CC has no way to say it. */
    public static Map<String, Object> toCc(byte[] p) {
        if (p.length < 20 || (p[0] & 0xF0) != 0x40) return null;
        int ihl = (p[0] & 15) * 4, total = u16(p, 2);
        if (ihl < 20 || total < ihl || total > p.length) return null;
        if ((u16(p, 6) & 0x3FFF) != 0) return null;    // (a fragment: more to come, or not the first)
        int proto = p[9] & 255;
        Map<String, Object> d = switch (proto) {
            case TCP -> tcpToCc(p, ihl, total);
            case UDP -> udpToCc(p, ihl, total);
            case ICMP -> icmpToCc(p, ihl, total);
            default -> null;
        };
        if (d == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("src", u32(p, 12));
        m.put("dst", u32(p, 16));
        m.put("ttl", p[8] & 255);
        m.put("id", u16(p, 4));
        m.put("proto", protoName(proto));
        m.put("data", d);
        return m;
    }

    private static Map<String, Object> tcpToCc(byte[] p, int start, int end) {
        if (end - start < 20) return null;
        int off = ((p[start + 12] & 0xF0) >> 4) * 4, flags = p[start + 13] & 255;
        if (off < 20 || start + off > end) return null;
        Map<String, Object> d = new HashMap<>();
        d.put("sport", u16(p, start));
        d.put("dport", u16(p, start + 2));
        d.put("seq", u32(p, start + 4));
        if ((flags & 16) != 0) d.put("ack", u32(p, start + 8));
        if ((flags & 1) != 0) d.put("fin", true);
        if ((flags & 2) != 0) d.put("syn", true);
        if ((flags & 4) != 0) d.put("rst", true);
        d.put("wnd", u16(p, start + 14));
        d.put("data", slice(p, start + off, end));
        return d;
    }

    private static Map<String, Object> udpToCc(byte[] p, int start, int end) {
        if (end - start < 8) return null;
        int len = u16(p, start + 4);
        if (len < 8 || start + len > end) return null;
        Map<String, Object> d = new HashMap<>();
        d.put("sport", u16(p, start));
        d.put("dport", u16(p, start + 2));
        d.put("data", slice(p, start + 8, start + len));
        return d;
    }

    private static Map<String, Object> icmpToCc(byte[] p, int start, int end) {
        if (end - start < 8) return null;
        int type = p[start] & 255, code = p[start + 1] & 255;
        Map<String, Object> d = new HashMap<>();
        switch (type) {
            case 8, 0 -> {
                d.put("type", type == 8 ? "echo" : "reply");
                d.put("id", u16(p, start + 4));
                d.put("seq", u16(p, start + 6));
                d.put("data", slice(p, start + 8, end));
            }
            case 3, 11 -> {
                String name;
                if (type == 11) name = "ttl";
                else if (code == 9 || code == 10 || code == 13) name = "prohibited";   // (administratively: a filter)
                else if (code == 0 || code == 5 || code == 6 || code == 11) name = "net";
                else if (code == 2 || code == 3) name = "port";
                else if (code == 4) return null;        // (fragmentation needed: CC's packets are small)
                else name = "host";
                var orig = origToCc(p, start + 8, end);
                if (orig == null) return null;
                d.put("type", type == 3 ? "unreachable" : "timeexceeded");
                d.put("code", name);
                d.put("orig", orig);
            }
            default -> {
                return null;
            }
        }
        return d;
    }

    private static Map<String, Object> origToCc(byte[] p, int start, int end) {
        if (end - start < 20 || (p[start] & 0xF0) != 0x40) return null;
        int ihl = (p[start] & 15) * 4, proto = p[start + 9] & 255;
        if (ihl < 20 || start + ihl > end) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("src", u32(p, start + 12));
        m.put("dst", u32(p, start + 16));
        var name = protoName(proto);
        if (name != null) m.put("proto", name);
        int t = start + ihl;
        if ((proto == TCP || proto == UDP) && end - t >= 4) {
            m.put("sport", u16(p, t));
            m.put("dport", u16(p, t + 2));
            if (proto == TCP && end - t >= 8) m.put("seq", u32(p, t + 4));
        } else if (proto == ICMP && end - t >= 8) {
            m.put("id", u16(p, t + 4));
            m.put("seq", u16(p, t + 6));
        }
        return m;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** The destination address of a packet at least 20 bytes long. */
    public static long dst(byte[] p) {
        return u32(p, 16);
    }

    /** The Internet checksum of b[off, off + len), starting from sum. */
    public static int checksum(byte[] b, int off, int len, long sum) {
        for (int i = 0; i + 1 < len; i += 2) sum += ((b[off + i] & 255) << 8) | (b[off + i + 1] & 255);
        if ((len & 1) != 0) sum += (b[off + len - 1] & 255) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    /** The TCP/UDP pseudo-header's sum. */
    public static long pseudo(long src, long dst, int proto, int len) {
        return (src >> 16) + (src & 0xFFFF) + (dst >> 16) + (dst & 0xFFFF) + proto + len;
    }

    /** A Lua number as an unsigned 32-bit value (mod 2^32), or null if it isn't a number. */
    public static Long u32(Object o) {
        if (!(o instanceof Number n)) return null;
        double v = n.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return ((long) Math.floor(v)) & 0xFFFFFFFFL;
    }

    /** A Lua string's bytes (a String of chars 0-255, or a byte[]), "" for nil, or null for anything else. */
    static byte[] bytes(Object o) {
        if (o == null) return new byte[0];
        if (o instanceof byte[] b) return b;
        if (o instanceof String s) return s.getBytes(StandardCharsets.ISO_8859_1);
        return null;
    }

    private static boolean yes(Object o) {
        return Boolean.TRUE.equals(o);
    }

    private static int protoNumber(Object name) {
        if ("tcp".equals(name)) return TCP;
        if ("udp".equals(name)) return UDP;
        if ("icmp".equals(name)) return ICMP;
        return -1;
    }

    private static String protoName(int proto) {
        return switch (proto) {
            case TCP -> "tcp";
            case UDP -> "udp";
            case ICMP -> "icmp";
            default -> null;
        };
    }

    static int u16(byte[] b, int i) {
        return ((b[i] & 255) << 8) | (b[i + 1] & 255);
    }

    static long u32(byte[] b, int i) {
        return ((long) u16(b, i) << 16) | u16(b, i + 2);
    }

    static void put16(byte[] b, int i, int v) {
        b[i] = (byte) (v >> 8);
        b[i + 1] = (byte) v;
    }

    static void put32(byte[] b, int i, long v) {
        put16(b, i, (int) (v >> 16) & 0xFFFF);
        put16(b, i + 2, (int) v & 0xFFFF);
    }

    private static byte[] slice(byte[] b, int from, int to) {
        var out = new byte[to - from];
        System.arraycopy(b, from, out, 0, out.length);
        return out;
    }

    /** "a.b.c.d" for an address. */
    public static String ipString(long ip) {
        return (ip >> 24 & 255) + "." + (ip >> 16 & 255) + "." + (ip >> 8 & 255) + "." + (ip & 255);
    }

    /** An address from "a.b.c.d", or -1. */
    public static long parseIp(String s) {
        var parts = s.trim().split("\\.", -1);
        if (parts.length != 4) return -1;
        long ip = 0;
        for (var part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) return -1;
            int v = Integer.parseInt(part);
            if (v > 255) return -1;
            ip = ip << 8 | v;
        }
        return ip;
    }
}
