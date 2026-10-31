package ccnix.gateway.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * NAT mode end to end: ccnix's lib/net in Cobalt, through the gateway and {@link Nat}, to real sockets on this
 * machine's loopback.
 * <pre>
 *   A 10.0.0.5 --lan-- R 10.0.0.2 | 10.1.0.1 --far-- B 10.1.0.6
 *                  |
 *               gateway 10.0.0.1 -- Nat -- the server's sockets
 * </pre>
 * 203.0.113.0/24 is "the outside" as CC sees it (lib/net takes 127/8 as its own, so CC can't name loopback): the
 * test's {@link Outside} sends it to 127.0.0.1. A forward's client on loopback can be shown as 198.51.100.20.
 */
class NatTest {
    static final String FAR = "203.0.113.7", CLIENT = "198.51.100.20";
    static final long FAR_NET = Ipv4.parseIp("203.0.113.0"), LOOPBACK = Ipv4.parseIp("127.0.0.1");
    CcWorld w;
    final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        if (w != null) w.nat.stop();
        for (var c : open) c.close();
    }

    static long far(long ip) {
        return (ip & Settings.mask(24)) == FAR_NET ? LOOPBACK : ip;
    }

    /** The real sockets, with 203.0.113.x as loopback and (if client) loopback clients shown as 198.51.100.20. */
    static Outside loopback(Outside real, boolean client) {
        long shownClient = client ? Ipv4.parseIp(CLIENT) : LOOPBACK;
        return new Outside() {
            @Override
            public Tcp connect(long ip, int port, TcpEvents events) {
                return real.connect(far(ip), port, events);
            }

            @Override
            public Udp udp(long ip, int port, UdpIn in) throws IOException {
                return real.udp(far(ip), port, (from, p, b) -> in.datagram(ip, p, b));
            }

            @Override
            public Closer listenTcp(int port, Accept accept) throws IOException {
                return real.listenTcp(port, (ip, p, conn) -> accept.accepted(ip == LOOPBACK ? shownClient : ip, p, conn));
            }

            @Override
            public Udp listenUdp(int port, UdpIn in) throws IOException {
                var u = real.listenUdp(port, (ip, p, b) -> in.datagram(ip == LOOPBACK ? shownClient : ip, p, b));
                return new Udp() {
                    @Override
                    public void send(long ip, int p, byte[] b) {
                        u.send(ip == shownClient ? LOOPBACK : ip, p, b);
                    }

                    @Override
                    public void close() {
                        u.close();
                    }
                };
            }

            @Override
            public <T> void blocking(Supplier<T> work, Consumer<T> then) {
                real.blocking(work, then);
            }
        };
    }

    static NatConfig cfg(int perComputer, int tcpIdle, NatConfig.Dns dns, List<String> forwards) {
        var problems = new ArrayList<String>();
        var c = NatConfig.parse(List.of("10.1.0.0/16"), NatConfig.DEFAULT_DENY, List.of(), perComputer, 256, tcpIdle, 60,
            dns, forwards, problems);
        assertEquals(List.of(), problems);
        return c;
    }

    void world(NatConfig cfg, boolean client) throws Exception {
        var s = Settings.parse("10.0.0.1/24", 4040, "gateway", List.of("10.1.0.0/16 via 10.0.0.2"), new ArrayList<>());
        w = new CcWorld(s, cfg, real -> loopback(real, client));
        w.wire("lan", 1, "back").wire("lan", 3, "back").wire("far", 3, "left").wire("far", 2, "back");
        w.gatewayOn("lan");
        w.lua("""
            A = H.stack(1, { back = "10.0.0.5/24" }, { { "default", "10.0.0.1" } })
            R = H.stack(3, { back = "10.0.0.2/24", left = "10.1.0.1/24" }, { { "default", "10.0.0.1" } }, true)
            B = H.stack(2, { back = "10.1.0.6/24" }, { { "default", "10.1.0.1" } })
            """);
    }

    void world() throws Exception {
        world(cfg(16, 600, NatConfig.Dns.OFF, List.of()), false);
    }

    boolean lua(String condition) {
        return "true".equals(w.luaString("return tostring(" + condition + ")"));
    }

    static byte[] hBytes(int n) {
        var b = new byte[n];
        for (int i = 1; i <= n; i++) b[i - 1] = (byte) ((i * 7 + 3) % 256);
        return b;
    }

    static int freePort() throws IOException {
        try (var s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void pingTheGatewayAndAcrossButNotOut() throws Exception {
        world();
        w.lua("A.echo('10.0.0.1', 7, 1); A.echo('10.1.0.6', 7, 2); B.echo('8.8.8.8', 9, 3)");
        w.pump(1);
        assertEquals("reply 10.0.0.1", w.luaString("local e = H.find('net_icmp', 1)[1]; return e[2] .. ' ' .. e[3]"));
        assertEquals("reply 10.1.0.6", w.luaString("local e = H.find('net_icmp', 1)[2]; return e[2] .. ' ' .. e[3]"),
            "A to B: through the gateway (Nat passes it back), then R");
        assertEquals("unreachable 10.0.0.1 net 3", w.luaString(
            "local e = H.find('net_icmp', 2)[1]; return e[2] .. ' ' .. e[3] .. ' ' .. e[4] .. ' ' .. e[5].seq"),
            "no ICMP to the outside: the gateway says so");
    }

    @Test
    void tcpOutAndBack() throws Exception {
        world();
        var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        open.add(server);
        var got = new ByteArrayOutputStream();
        var t = Thread.ofVirtual().start(() -> {
            try (var s = server.accept()) {
                got.writeBytes(s.getInputStream().readAllBytes());     // (until B's FIN)
                var back = got.toByteArray();
                for (int i = 0; i < back.length / 2; i++) {
                    var x = back[i];
                    back[i] = back[back.length - 1 - i];
                    back[back.length - 1 - i] = x;
                }
                s.getOutputStream().write(back);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        w.lua("c = assert(B.tcpConnect('" + FAR + "', " + server.getLocalPort() + "))");
        assertTrue(w.until(() -> lua("c.state == 'ESTABLISHED'"), 5), w.luaString("return c.state"));
        w.lua("assert(B.tcpSend(c, H.bytes(3000))); B.tcpClose(c); got = ''");
        assertTrue(w.until(() -> lua("(function() local s, over = B.tcpRead(c); got = got .. s; return over end)()"), 10),
            "B got " + w.luaString("return #got") + " bytes back");
        t.join(2000);
        assertArrayEquals(hBytes(3000), got.toByteArray(), "the outside got B's bytes, in order");
        assertEquals("true", w.luaString("return tostring(got == H.bytes(3000):reverse())"), "and B got its answer");
        assertTrue(w.until(() -> lua("c.state == 'CLOSED' or c.state == 'TIME_WAIT'"), 5), w.luaString("return c.state"));
        w.pump(5);
        assertTrue(w.until(() -> w.nat.status().get(0).startsWith("NAT: 0 TCP"), 5), w.nat.status().get(0));
    }

    @Test
    void refusedOutsideIsRefusedInside() throws Exception {
        world();
        int port = freePort();
        w.lua("c = assert(B.tcpConnect('" + FAR + "', " + port + "))");
        assertTrue(w.until(() -> lua("c.state == 'CLOSED'"), 5));
        assertEquals("connection refused", w.luaString("return c.err"));
    }

    @Test
    void udpOutAndBack() throws Exception {
        world();
        var sock = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        open.add(sock);
        Thread.ofVirtual().start(() -> {
            try {
                var p = new DatagramPacket(new byte[512], 512);
                sock.receive(p);
                var s = new String(p.getData(), 0, p.getLength(), StandardCharsets.ISO_8859_1).toUpperCase();
                sock.send(new DatagramPacket(s.getBytes(StandardCharsets.ISO_8859_1), s.length(), p.getSocketAddress()));
            } catch (IOException ignored) {
            }
        });
        w.lua("p = B.udpOpen(); assert(B.udpSend(p, '" + FAR + "', " + sock.getLocalPort() + ", 'hello'))");
        assertTrue(w.until(() -> lua("(function() d = d or B.udpTake(p); return d ~= nil end)()"), 5));
        assertEquals("HELLO " + FAR + " " + sock.getLocalPort(), w.luaString("return d.data .. ' ' .. d.src .. ' ' .. d.sport"));
    }

    @Test
    void thePolicyAndTheLimits() throws Exception {
        world(cfg(2, 600, NatConfig.Dns.OFF, List.of()), false);
        w.lua("c = assert(B.tcpConnect('192.168.1.1', 80)); c25 = assert(B.tcpConnect('" + FAR + "', 25))");
        w.pump(0.2);
        assertEquals("CLOSED connection refused CLOSED", w.luaString("return c.state .. ' ' .. c.err .. ' ' .. c25.state"),
            "a private address, and port 25 anywhere: refused at once");
        w.lua("p = B.udpOpen(); B.udpSend(p, '169.254.169.254', 80, 'x')");
        w.pump(0.2);
        assertEquals("unreachable port 169.254.169.254", w.luaString(
            "local e = H.find('net_icmp', 2)[1]; return e[2] .. ' ' .. e[4] .. ' ' .. N.ipString(e[5].dst)"),
            "the cloud metadata service: port unreachable");
        long before = w.nat.refused;
        w.lua("for port = 1001, 1003 do B.udpSend(p, '" + FAR + "', port, 'x') end");
        w.pump(0.2);
        assertEquals(before + 1, w.nat.refused, "two flows for B, then no more");
        assertTrue(w.nat.status().get(0).startsWith("NAT: 0 TCP connections, 2 UDP flows"), w.nat.status().get(0));
    }

    @Test
    void idleConnectionsClose() throws Exception {
        world(cfg(16, 10, NatConfig.Dns.OFF, List.of()), false);
        var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        open.add(server);
        Thread.ofVirtual().start(() -> {
            try {
                open.add(server.accept());              // (and says nothing)
            } catch (IOException ignored) {
            }
        });
        w.lua("c = assert(B.tcpConnect('" + FAR + "', " + server.getLocalPort() + "))");
        assertTrue(w.until(() -> lua("c.state == 'ESTABLISHED'"), 5));
        w.pump(11);
        assertEquals("CLOSED connection reset", w.luaString("return c.state .. ' ' .. tostring(c.err)"));
    }

    @Test
    void aForwardShowsTheClientsAddress() throws Exception {
        int port = freePort();
        world(cfg(16, 600, NatConfig.Dns.OFF, List.of(port + "/tcp -> 10.1.0.6:23")), true);
        w.lua("assert(B.tcpListen(23))");
        var client = new Socket(InetAddress.getLoopbackAddress(), port);
        open.add(client);
        client.getOutputStream().write("hello\n".getBytes());
        assertTrue(w.until(() -> lua("(function() s = s or B.tcpAccept(23); return s ~= nil end)()"), 5));
        assertEquals(CLIENT, w.luaString("return N.ipString(s.rip)"), "the client's real address");
        assertTrue(w.until(() -> lua("(function() got = (got or '') .. B.tcpRead(s); return got == 'hello\\n' end)()"), 5));
        w.lua("assert(B.tcpSend(s, 'world')); B.tcpClose(s)");
        client.setSoTimeout(5000);
        var in = new ByteArrayOutputStream();
        assertTrue(w.until(() -> {
            try {
                if (client.getInputStream().available() > 0) in.write(client.getInputStream().read());
            } catch (IOException e) {
                return false;
            }
            return in.toString().equals("world");
        }, 5), in.toString());
    }

    @Test
    void aForwardFromTheServerItselfShowsTheGateway() throws Exception {
        int port = freePort();
        world(cfg(16, 600, NatConfig.Dns.OFF, List.of(port + "/tcp -> 10.1.0.6:23")), false);
        w.lua("assert(B.tcpListen(23))");
        open.add(new Socket(InetAddress.getLoopbackAddress(), port));
        assertTrue(w.until(() -> lua("(function() s = s or B.tcpAccept(23); return s ~= nil end)()"), 5));
        assertEquals("10.0.0.1", w.luaString("return N.ipString(s.rip)"), "loopback a CC host would answer itself");
    }

    @Test
    void aUdpForward() throws Exception {
        int port = freePort();
        world(cfg(16, 600, NatConfig.Dns.OFF, List.of(port + "/udp -> 10.1.0.6:7")), true);
        w.lua("assert(B.udpOpen(7))");
        var sock = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        open.add(sock);
        sock.send(new DatagramPacket("ping".getBytes(), 4, new InetSocketAddress(InetAddress.getLoopbackAddress(), port)));
        assertTrue(w.until(() -> lua("(function() d = d or B.udpTake(7); return d ~= nil end)()"), 5));
        assertEquals("ping " + CLIENT, w.luaString("return d.data .. ' ' .. d.src"));
        w.lua("B.udpSend(7, d.src, d.sport, 'pong')");
        w.pump(0.1);
        sock.setSoTimeout(5000);
        var p = new DatagramPacket(new byte[16], 16);
        sock.receive(p);
        assertEquals("pong", new String(p.getData(), 0, p.getLength()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // DNS at the gateway's address
    // ---------------------------------------------------------------------------------------------------------------

    static final DnsAnswer.Resolver STUB = new DnsAnswer.Resolver() {
        @Override
        public List<Long> addresses(String name) {
            return name.equals("lab.example") ? List.of(Ipv4.parseIp("203.0.113.9")) : List.of();
        }

        @Override
        public String name(long ip) {
            return ip == Ipv4.parseIp("203.0.113.9") ? "lab.example" : null;
        }
    };

    @Test
    void onWindowsTheGatewayAnswers() throws Exception {
        world(cfg(16, 600, new NatConfig.Dns(NatConfig.Dns.Kind.ANSWER, 0, STUB), List.of()), false);
        w.library("netdb");
        w.lua("""
            p = B.udpOpen()
            B.udpSend(p, '10.0.0.1', 53, netdb.dnsQuery(0x4242, 'lab.example', 'A'))
            B.udpSend(p, '10.0.0.1', 53, netdb.dnsQuery(0x4243, '9.113.0.203.in-addr.arpa', 'PTR'))
            B.udpSend(p, '10.0.0.1', 53, netdb.dnsQuery(0x4244, 'nowhere.invalid', 'A'))
            got = {}
            """);
        assertTrue(w.until(() -> lua("(function() local d = B.udpTake(p); if d then got[#got + 1] = d end; return #got == 3 end)()"), 5));
        w.lua("""
            table.sort(got, function(a, b) return netdb.dnsParse(a.data).id < netdb.dnsParse(b.data).id end)
            a, ptr, nx = netdb.dnsParse(got[1].data), netdb.dnsParse(got[2].data), netdb.dnsParse(got[3].data)
            """);
        assertEquals("10.0.0.1 53 203.0.113.9", w.luaString("return got[1].src .. ' ' .. got[1].sport .. ' ' .. a.answers[1].data"));
        assertEquals("lab.example", w.luaString("return ptr.answers[1].data"));
        assertEquals("3 0", w.luaString("return nx.rcode .. ' ' .. #nx.answers"));
    }

    @Test
    void theRelayPassesThroughAndOtherResolversArentTouched() throws Exception {
        DatagramSocket stub;
        try {
            stub = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 53));
        } catch (IOException e) {
            assumeTrue(false, "can't bind 127.0.0.1:53 here: " + e);
            return;
        }
        open.add(stub);
        Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 2; i++) {
                    var p = new DatagramPacket(new byte[512], 512);
                    stub.receive(p);
                    var b = ("answer to " + new String(p.getData(), 0, p.getLength())).getBytes();
                    stub.send(new DatagramPacket(b, b.length, p.getSocketAddress()));
                }
            } catch (IOException ignored) {
            }
        });
        world(cfg(16, 600, new NatConfig.Dns(NatConfig.Dns.Kind.RELAY, LOOPBACK, null), List.of()), false);
        w.lua("p = B.udpOpen(); B.udpSend(p, '10.0.0.1', 53, 'q1'); B.udpSend(p, '" + FAR + "', 53, 'q2'); got = {}");
        assertTrue(w.until(() -> lua("(function() local d = B.udpTake(p); if d then got[d.data] = d.src end; return got['answer to q2'] ~= nil and got['answer to q1'] ~= nil end)()"), 5));
        assertEquals("10.0.0.1", w.luaString("return got['answer to q1']"), "relayed, unread, from the gateway's address");
        assertEquals(FAR, w.luaString("return got['answer to q2']"), "a query to another resolver went there, untouched");
    }

    @Test
    void noRelayNoPort53() throws Exception {
        world();
        w.lua("p = B.udpOpen(); B.udpSend(p, '10.0.0.1', 53, 'q')");
        w.pump(0.2);
        assertEquals("unreachable 10.0.0.1 port", w.luaString("local e = H.find('net_icmp', 2)[1]; return e[2] .. ' ' .. e[3] .. ' ' .. e[4]"));
    }
}
