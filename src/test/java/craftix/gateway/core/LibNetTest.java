package craftix.gateway.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static craftix.gateway.core.Packets.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * cc-turtles' own stack (lib/net.lua, run in Cobalt) through the gateway:
 * <pre>
 *   A 10.0.0.5 --lan-- R 10.0.0.2 | 10.1.0.1 --far-- B 10.1.0.6
 *                  |
 *               gateway 10.0.0.1 -- TUN -- kernel (10.0.0.1; 10/8 back into the TUN; the rest "outside")
 * </pre>
 * A routes everything at the gateway, R everything but its networks; the gateway routes 10.1.0.0/16 via R.
 * So A and B talk through the server, as two CC networks joined only by the gateway would, and a packet from
 * outside reaches B the way a real client's would.
 */
class LibNetTest {
    CcWorld w;

    @BeforeEach
    void setUp() throws Exception {
        var problems = new ArrayList<String>();
        w = new CcWorld(Settings.parse("10.0.0.1/24", 4040, "gateway", List.of("10.1.0.0/16 via 10.0.0.2"), problems));
        w.wire("lan", 1, "back").wire("lan", 3, "back").wire("far", 3, "left").wire("far", 2, "back");
        w.gatewayOn("lan");
        w.lua("""
            A = H.stack(1, { back = "10.0.0.5/24" }, { { "default", "10.0.0.1" } })
            R = H.stack(3, { back = "10.0.0.2/24", left = "10.1.0.1/24" }, { { "default", "10.0.0.1" } }, true)
            B = H.stack(2, { back = "10.1.0.6/24" }, { { "default", "10.1.0.1" } })
            """);
    }

    @Test
    void pingTheServerAndAcross() {
        w.lua("A.echo('10.0.0.1', 7, 1); A.echo('10.1.0.6', 7, 2)");
        w.pump(1);
        assertEquals(2.0, w.lua("return #H.find('net_icmp', 1)").toDouble());
        assertEquals("10.0.0.1", w.luaString("return H.find('net_icmp', 1)[1][3]"));
        assertEquals("reply", w.luaString("return H.find('net_icmp', 1)[2][2]"));
        assertEquals("10.1.0.6", w.luaString("return H.find('net_icmp', 1)[2][3]"));
        assertEquals(2.0, w.lua("return H.find('net_icmp', 1)[2][5]").toDouble(), "seq");
        // both echoes went into the TUN device; B's reply came straight back through R (A is on R's network)
        assertEquals(2, w.gateway.toTun);
    }

    @Test
    void tcpAcrossTheServer() {
        w.lua("""
            assert(B.tcpListen(23))
            c = assert(A.tcpConnect("10.1.0.6", 23))
            """);
        w.pump(1);
        assertEquals("ESTABLISHED", w.luaString("return c.state"));
        w.lua("""
            s = B.tcpAccept(23)
            assert(s and s.state == "ESTABLISHED")
            sent = H.bytes(3000)
            assert(A.tcpSend(c, sent))
            """);
        w.pump(3);
        assertEquals("true", w.luaString("got = B.tcpRead(s); return tostring(got == sent)"),
            "B got " + w.luaString("return #got") + " bytes");
        w.lua("assert(B.tcpSend(s, sent:reverse())); B.tcpClose(s)");
        w.pump(3);
        assertEquals("true", w.luaString("local back, over = A.tcpRead(c); return tostring(back == sent:reverse() and over)"));
        w.lua("A.tcpClose(c)");
        w.pump(6);
        assertEquals("CLOSED", w.luaString("return c.state"));
        assertEquals("CLOSED", w.luaString("return s.state"));
        assertNull(w.luaString("return c.err"));
        for (var p : w.intoTun) assertTrue(checksumsOk(p));
    }

    @Test
    void udpAcrossTheServer() {
        w.lua("""
            assert(B.udpOpen(7))
            port = A.udpOpen()
            A.udpSend(port, "10.1.0.6", 7, H.bytes(300))
            A.udpSend(port, "10.1.0.6", 7, { a = "table" })
            """);
        w.pump(1);
        assertEquals("true", w.luaString("local d = B.udpTake(7); return tostring(d.data == H.bytes(300) and d.src == '10.0.0.5')"));
        assertNull(w.luaString("return B.udpTake(7)"), "a table stays on its CC network");
    }

    @Test
    void aRealClientConnects() {
        w.lua("assert(B.tcpListen(23))");
        String me = "192.168.1.50", b = "10.1.0.6";
        long iss = 0xfffffffaL;                         // (the data wraps past 2^32)
        w.fromOutside(tcp(me, 40000, b, 23, iss, 0, SYN, 64240, LINUX_SYN_OPTIONS, new byte[0]));
        w.pump(0.2);
        assertEquals(1, w.outside.size());
        var synAck = w.outside.remove(0);
        assertTrue(checksumsOk(synAck));
        assertEquals(Ipv4.parseIp(b), u32(synAck, 12));
        assertEquals(Ipv4.parseIp(me), u32(synAck, 16));
        assertEquals(23, u16(synAck, 20));
        assertEquals(40000, u16(synAck, 22));
        assertEquals(SYN | ACK, u8(synAck, 33));
        assertEquals(iss + 1, u32(synAck, 28));
        assertEquals(5 << 4, u8(synAck, 32), "no options: the client keeps to 536-byte segments, no scaling");
        assertTrue(u16(synAck, 34) > 0);
        assertEquals(63, u8(synAck, 8), "R's hop: TTL 64 less one");
        long theirs = add(u32(synAck, 24), 1);

        long mine = add(iss, 1);
        var hello = "hello\r\néÿ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        w.fromOutside(tcp(me, 40000, b, 23, mine, theirs, ACK, 64240, new byte[0], new byte[0]));
        w.fromOutside(tcp(me, 40000, b, 23, mine, theirs, ACK | PSH, 64240, new byte[0], hello));
        w.pump(0.2);
        w.lua("s = assert(B.tcpAccept(23))");
        assertArrayEquals(hello, w.luaBytes("return (B.tcpRead(s))"));
        mine = add(mine, hello.length);

        w.lua("assert(B.tcpSend(s, 'world'))");
        w.pump(0.2);
        var acks = new ArrayList<byte[]>(w.outside);
        w.outside.clear();
        var data = acks.stream().filter(p -> tcpData(p).length > 0).toList();
        assertEquals(1, data.size(), acks.size() + " segments");
        var world = data.get(0);
        assertTrue(checksumsOk(world));
        assertArrayEquals("world".getBytes(), tcpData(world));
        assertEquals(theirs, u32(world, 24));
        assertEquals(mine, u32(world, 28), "it acks what came");
        assertEquals(ACK | PSH, u8(world, 33));
        theirs = add(theirs, 5);

        w.fromOutside(tcp(me, 40000, b, 23, mine, theirs, ACK | FIN, 64240, new byte[0], new byte[0]));
        w.pump(0.2);
        assertEquals("CLOSE_WAIT", w.luaString("return s.state"));
        var finAck = w.outside.remove(w.outside.size() - 1);
        assertEquals(add(mine, 1), u32(finAck, 28), "its FIN is acked");
        w.lua("B.tcpClose(s)");
        w.pump(0.2);
        var fin = w.outside.remove(w.outside.size() - 1);
        assertEquals(ACK | FIN, u8(fin, 33));
        assertEquals(theirs, u32(fin, 24));
        w.fromOutside(tcp(me, 40000, b, 23, add(mine, 1), add(theirs, 1), ACK, 64240, new byte[0], new byte[0]));
        w.pump(0.2);
        assertEquals("CLOSED", w.luaString("return s.state"));
    }

    static long add(long seq, long n) {
        return (seq + n) & 0xFFFFFFFFL;
    }

    @Test
    void aRealClientIsRefused() {
        w.fromOutside(tcp("192.168.1.50", 40001, "10.1.0.6", 80, 1000, 0, SYN, 64240, LINUX_SYN_OPTIONS, new byte[0]));
        w.pump(0.2);
        var rst = w.outside.get(0);
        assertEquals(RST | ACK, u8(rst, 33));
        assertEquals(1001, u32(rst, 28));
        assertTrue(checksumsOk(rst));
    }

    @Test
    void aRealPingAndAnUnreachablePort() {
        w.fromOutside(icmp("192.168.1.50", "10.1.0.6", 8, 0, 0x4d2_0001, "0123456789abcdef".getBytes()));
        w.fromOutside(udp("192.168.1.50", 33434, "10.1.0.6", 33435, new byte[8]));
        w.pump(0.2);
        assertEquals(2, w.outside.size());
        var reply = w.outside.get(0);
        assertTrue(checksumsOk(reply));
        assertEquals(0, u8(reply, 20));
        assertEquals(0x4d2, u16(reply, 24));
        assertEquals(1, u16(reply, 26));
        var tail = new byte[16];
        System.arraycopy(reply, 28, tail, 0, 16);
        assertArrayEquals("0123456789abcdef".getBytes(), tail, "the data comes back, as ping checks");
        var unreachable = w.outside.get(1);
        assertTrue(checksumsOk(unreachable));
        assertEquals(3, u8(unreachable, 20));
        assertEquals(3, u8(unreachable, 21), "port");
        assertEquals(Ipv4.parseIp("192.168.1.50"), u32(unreachable, 28 + 12), "the original's source");
        assertEquals(33434, u16(unreachable, 48), "and its ports, as traceroute matches them");
        assertEquals(33435, u16(unreachable, 50));
    }
}
