package craftix.gateway.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static craftix.gateway.core.Packets.*;
import static org.junit.jupiter.api.Assertions.*;

class Ipv4Test {
    static final double A = Ipv4.parseIp("10.0.0.5"), B = Ipv4.parseIp("10.1.2.3");

    /** A Lua table as CC hands it over: numbers as Double. */
    static Map<String, Object> t(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            var v = kv[i + 1] instanceof Integer n ? (Object) (double) n : kv[i + 1];
            m.put((String) kv[i], v);
        }
        return m;
    }

    static Map<String, Object> ip(String proto, Map<String, Object> data) {
        return t("src", A, "dst", B, "ttl", 64, "id", 7, "proto", proto, "data", data);
    }

    @Test
    void checksumOfAKnownHeader() {
        // RFC 1071's way, on the header Wikipedia's "IPv4 header checksum" works through: b861
        var h = new byte[]{ 0x45, 0, 0, 0x73, 0, 0, 0x40, 0, 0x40, 0x11, 0, 0, (byte) 0xc0, (byte) 0xa8, 0, 1,
            (byte) 0xc0, (byte) 0xa8, 0, (byte) 0xc7 };
        assertEquals(0xb861, Ipv4.checksum(h, 0, 20, 0));
        assertEquals(0xb861, Packets.sum(h, 0, 20, 0));
    }

    @Test
    void tcpFromCc() {
        var p = Ipv4.fromCc(ip("tcp", t("sport", 49153, "dport", 23, "seq", 4294967295.0, "syn", true, "wnd", 4096,
            "data", "")));
        assertNotNull(p);
        assertEquals(40, p.length);
        assertEquals(0x45, u8(p, 0));
        assertEquals(40, u16(p, 2));
        assertEquals(7, u16(p, 4));
        assertEquals(64, u8(p, 8));
        assertEquals(6, u8(p, 9));
        assertEquals((long) A, u32(p, 12));
        assertEquals((long) B, u32(p, 16));
        assertEquals(49153, u16(p, 20));
        assertEquals(23, u16(p, 22));
        assertEquals(4294967295L, u32(p, 24));
        assertEquals(0, u32(p, 28), "no ack field without the ACK flag");
        assertEquals(5 << 4, u8(p, 32), "no options");
        assertEquals(SYN, u8(p, 33));
        assertEquals(4096, u16(p, 34));
        assertTrue(checksumsOk(p));
    }

    @Test
    void tcpFlagsAndData() {
        var data = new StringBuilder();
        for (int i = 0; i < 256; i++) data.append((char) i);   // (Cobalt: one char per byte)
        var p = Ipv4.fromCc(ip("tcp", t("sport", 23, "dport", 40000, "seq", 1000, "ack", 2000, "fin", true,
            "wnd", 100000, "data", data.toString())));
        assertEquals(ACK | PSH | FIN, u8(p, 33));
        assertEquals(2000, u32(p, 28));
        assertEquals(65535, u16(p, 34), "a window past 16 bits is capped");
        var back = tcpData(p);
        assertEquals(256, back.length);
        for (int i = 0; i < 256; i++) assertEquals(i, back[i] & 255);
        assertTrue(checksumsOk(p));
    }

    @Test
    void linuxSynToCc() {
        var p = tcp("192.168.1.50", 40000, "10.1.2.3", 23, 0xdeadbeefL, 0, SYN, 64240, LINUX_SYN_OPTIONS, new byte[0]);
        var m = Ipv4.toCc(p);
        assertNotNull(m);
        assertEquals(Ipv4.parseIp("192.168.1.50"), m.get("src"));
        assertEquals("tcp", m.get("proto"));
        assertEquals(64, m.get("ttl"));
        @SuppressWarnings("unchecked") var d = (Map<String, Object>) m.get("data");
        assertEquals(40000, d.get("sport"));
        assertEquals(23, d.get("dport"));
        assertEquals(0xdeadbeefL, d.get("seq"));
        assertEquals(true, d.get("syn"));
        assertFalse(d.containsKey("ack"), "no ack without the ACK flag");
        assertFalse(d.containsKey("fin"));
        assertEquals(64240, d.get("wnd"));
        assertArrayEquals(new byte[0], (byte[]) d.get("data"), "options aren't data");
    }

    @Test
    void tcpRoundTrip() {
        var bytes = new byte[536];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 7 + 3);
        var p = tcp("192.168.1.50", 40000, "10.1.2.3", 23, 5, 6, ACK | PSH, 1234, new byte[0], bytes);
        var again = Ipv4.fromCc(viaLua(Ipv4.toCc(p)));
        // TTL, id and the DF flag aside, the same packet
        assertEquals(p.length, again.length);
        for (int i = 20; i < p.length; i++) assertEquals(p[i], again[i], "byte " + i);
    }

    @Test
    void udp() {
        var p = Ipv4.fromCc(ip("udp", t("sport", 50000, "dport", 7, "data", "hiÿ\u0000")));
        assertEquals(17, u8(p, 9));
        assertEquals(12, u16(p, 24));
        assertTrue(checksumsOk(p));
        @SuppressWarnings("unchecked") var d = (Map<String, Object>) Ipv4.toCc(p).get("data");
        assertArrayEquals(new byte[]{ 'h', 'i', (byte) 0xff, 0 }, (byte[]) d.get("data"));
        assertNull(Ipv4.fromCc(ip("udp", t("sport", 1, "dport", 7, "data", t("x", 1)))), "a table can't cross");
    }

    @Test
    void echo() {
        var p = Ipv4.fromCc(ip("icmp", t("type", "echo", "id", 3, "seq", 9)));
        assertEquals(28, p.length);
        assertEquals(8, u8(p, 20));
        assertEquals(3, u16(p, 24));
        assertEquals(9, u16(p, 26));
        assertTrue(checksumsOk(p));
        var linux = icmp("10.0.0.1", "10.0.0.5", 0, 0, 0x00110002, "abcdefgh".getBytes());
        @SuppressWarnings("unchecked") var d = (Map<String, Object>) Ipv4.toCc(linux).get("data");
        assertEquals("reply", d.get("type"));
        assertEquals(0x11, d.get("id"));
        assertEquals(2, d.get("seq"));
        assertArrayEquals("abcdefgh".getBytes(), (byte[]) d.get("data"));
    }

    @Test
    void unreachableFromCc() {
        var orig = t("src", B, "dst", Ipv4.parseIp("192.168.1.50") * 1.0, "proto", "tcp", "sport", 23, "dport", 40000,
            "seq", 77);
        var p = Ipv4.fromCc(ip("icmp", t("type", "unreachable", "code", "port", "orig", orig)));
        assertEquals(20 + 8 + 28, p.length);
        assertEquals(3, u8(p, 20));
        assertEquals(3, u8(p, 21));
        assertTrue(checksumsOk(p));
        assertEquals(6, u8(p, 28 + 9), "the original's protocol");
        assertEquals((long) B, u32(p, 28 + 12));
        assertEquals(23, u16(p, 48));
        assertEquals(40000, u16(p, 50));
        assertEquals(77, u32(p, 52));
        @SuppressWarnings("unchecked") var d = (Map<String, Object>) Ipv4.toCc(p).get("data");
        assertEquals("unreachable", d.get("type"));
        assertEquals("port", d.get("code"));
        @SuppressWarnings("unchecked") var o = (Map<String, Object>) d.get("orig");
        assertEquals("tcp", o.get("proto"));
        assertEquals(23, o.get("sport"));
        assertEquals(40000, o.get("dport"));
        assertEquals(77L, o.get("seq"));
    }

    @Test
    void timeExceededFromARealRouter() {
        var probe = Packets.udp("10.0.0.5", 33434, "8.8.8.8", 33435, new byte[12]);
        var inner = new byte[28];
        System.arraycopy(probe, 0, inner, 0, 28);
        var p = icmp("192.168.1.1", "10.0.0.5", 11, 0, 0, inner);
        @SuppressWarnings("unchecked") var d = (Map<String, Object>) Ipv4.toCc(p).get("data");
        assertEquals("timeexceeded", d.get("type"));
        assertEquals("ttl", d.get("code"));
        @SuppressWarnings("unchecked") var o = (Map<String, Object>) d.get("orig");
        assertEquals(Ipv4.parseIp("8.8.8.8"), o.get("dst"));
        assertEquals("udp", o.get("proto"));
        assertEquals(33434, o.get("sport"));
        assertEquals(33435, o.get("dport"));
    }

    @Test
    void whatCantCross() {
        var p = tcp("192.168.1.50", 1, "10.1.2.3", 23, 0, 0, SYN, 1, new byte[0], new byte[0]);
        p[6] = 0x20;                                    // more fragments
        assertNull(Ipv4.toCc(p));
        p[6] = 0;
        p[7] = 1;                                       // a later fragment
        assertNull(Ipv4.toCc(p));
        assertNull(Ipv4.toCc(new byte[]{ 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }), "IPv6");
        assertNull(Ipv4.toCc(Packets.ip("10.0.0.5", "10.1.2.3", 47, 64, new byte[8])), "GRE");
        assertNull(Ipv4.fromCc(t("src", A, "dst", B, "ttl", 64, "proto", "tcp")), "no TCP fields");
        assertNull(Ipv4.fromCc("hello"));
    }

    @Test
    void ipOptionsAreSkipped() {
        var plain = Packets.udp("10.0.0.5", 1, "10.1.2.3", 2, "x".getBytes());
        var p = new byte[plain.length + 4];
        p[0] = 0x46;
        System.arraycopy(plain, 1, p, 1, 19);
        Ipv4.put16(p, 2, p.length);
        p[20] = 1;
        p[21] = 1;
        p[22] = 1;
        p[23] = 0;                                      // NOP NOP NOP EOL
        System.arraycopy(plain, 20, p, 24, plain.length - 20);
        @SuppressWarnings("unchecked") var d = (Map<String, Object>) Ipv4.toCc(p).get("data");
        assertEquals(2, d.get("dport"));
        assertArrayEquals("x".getBytes(), (byte[]) d.get("data"));
    }

    /** Into a Lua value and back, as a packet makes its way into CC and out again. */
    static Object viaLua(Object o) {
        return CcWorld.toObject(CcWorld.toValue(o));
    }
}
