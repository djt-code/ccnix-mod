package ccnix.gateway.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ccnix.gateway.core.Ipv4Test.t;
import static org.junit.jupiter.api.Assertions.*;

class GatewayTest {
    static final int CH = 4040;

    static final class Port implements Gateway.Port {
        final List<Map<String, Object>> sent = new ArrayList<>();

        @Override
        public void send(int channel, Map<String, Object> frame) {
            assertEquals(CH, channel);
            sent.add(frame);
        }
    }

    long now;
    final List<byte[]> tun = new ArrayList<>();
    Gateway g;
    Port lan, other;

    static Settings settings(String... routes) {
        var problems = new ArrayList<String>();
        var s = Settings.parse("10.0.0.1/24", CH, "gateway", List.of(routes), problems);
        assertEquals(List.of(), problems);
        return s;
    }

    @BeforeEach
    void setUp() {
        g = new Gateway(settings("10.1.0.0/16 via 10.0.0.2"), tun::add, () -> now);
        lan = new Port();
        other = new Port();
        g.addPort(lan);
        g.addPort(other);
    }

    static double ip(String s) {
        return Ipv4.parseIp(s);
    }

    static Map<String, Object> frame(String src, String dst, String type, Object data) {
        return t("ccnet", 1, "src", src, "dst", dst, "type", type, "data", data);
    }

    static Map<String, Object> arpRequest(String mac, String from, String want) {
        return frame(mac, "*", "arp", t("op", "request", "sha", mac, "spa", ip(from), "tpa", ip(want)));
    }

    static Map<String, Object> arpReply(String mac, String from) {
        return frame(mac, "gateway", "arp", t("op", "reply", "sha", mac, "spa", ip(from), "tha", "gateway", "tpa", ip("10.0.0.1")));
    }

    static Object data(Map<String, Object> f, String key) {
        return ((Map<?, ?>) f.get("data")).get(key);
    }

    @Test
    void answersArpForItsAddress() {
        g.fromCc(lan, CH, arpRequest("3:back", "10.0.0.2", "10.0.0.1"));
        assertEquals(1, lan.sent.size());
        assertEquals(0, other.sent.size(), "the answer goes where the question came from");
        var f = lan.sent.get(0);
        assertEquals(1, f.get("ccnet"));
        assertEquals("3:back", f.get("dst"));
        assertEquals("gateway", f.get("src"));
        assertEquals("arp", f.get("type"));
        assertEquals("reply", data(f, "op"));
        assertEquals("gateway", data(f, "sha"));
        assertEquals((long) ip("10.0.0.1"), data(f, "spa"));
        assertEquals("3:back", data(f, "tha"));
        assertEquals((long) ip("10.0.0.2"), data(f, "tpa"));
    }

    @Test
    void ignoresWhatIsntForIt() {
        g.fromCc(lan, CH, arpRequest("3:back", "10.0.0.2", "10.0.0.9"));
        g.fromCc(lan, CH + 1, arpRequest("3:back", "10.0.0.2", "10.0.0.1"));
        g.fromCc(lan, CH, frame("gateway", "*", "arp", t("op", "request", "sha", "gateway", "spa", ip("10.0.0.1"), "tpa", ip("10.0.0.1"))));
        g.fromCc(lan, CH, frame("3:back", "4:back", "ip", t()));
        g.fromCc(lan, CH, t("hello", "world"));
        g.fromCc(lan, CH, "rednet, say");
        assertEquals(0, lan.sent.size() + other.sent.size() + tun.size());
    }

    static Map<String, Object> ping(String from, String to) {
        return t("src", ip(from), "dst", ip(to), "ttl", 64, "id", 1, "proto", "icmp", "data", t("type", "echo", "id", 1, "seq", 1));
    }

    @Test
    void packetsForItGoIntoTheTun() {
        g.fromCc(lan, CH, frame("5:back", "gateway", "ip", ping("10.0.0.5", "8.8.8.8")));
        assertEquals(1, tun.size());
        assertEquals((long) ip("8.8.8.8"), Ipv4.dst(tun.get(0)));
        assertTrue(Packets.checksumsOk(tun.get(0)));
        assertEquals(1, g.toTun);
    }

    @Test
    void oneMessageHeardTwiceCountsOnce() {
        var f = frame("5:back", "gateway", "ip", ping("10.0.0.5", "8.8.8.8"));
        g.fromCc(lan, CH, f);
        g.fromCc(other, CH, f);
        assertEquals(1, tun.size());
    }

    @Test
    void broadcastFramesOnlyCarryBroadcasts() {
        g.fromCc(lan, CH, frame("5:back", "*", "ip", ping("10.0.0.5", "8.8.8.8")));
        assertEquals(0, tun.size());
        g.fromCc(lan, CH, frame("5:back", "*", "ip", ping("10.0.0.5", "10.0.0.255")));
        assertEquals(1, tun.size());
    }

    static byte[] udpTo(String dst) {
        return Packets.udp("192.168.1.50", 5000, dst, 7, "hi".getBytes());
    }

    @Test
    void asksArpThenDelivers() {
        g.fromTun(udpTo("10.0.0.5"));
        g.fromTun(udpTo("10.0.0.5"));
        assertEquals(1, lan.sent.size(), "one question for both");
        assertEquals(1, other.sent.size(), "asked out of every port");
        var q = lan.sent.get(0);
        assertEquals("*", q.get("dst"));
        assertEquals("request", data(q, "op"));
        assertEquals((long) ip("10.0.0.5"), data(q, "tpa"));
        assertEquals((long) ip("10.0.0.1"), data(q, "spa"));

        g.fromCc(other, CH, arpReply("5:back", "10.0.0.5"));
        assertEquals(1, lan.sent.size());
        assertEquals(3, other.sent.size(), "both packets, out of the port the answer came in on");
        var f = other.sent.get(1);
        assertEquals("5:back", f.get("dst"));
        assertEquals("ip", f.get("type"));
        assertEquals("udp", data(f, "proto"));

        g.fromTun(udpTo("10.0.0.5"));
        assertEquals(4, other.sent.size(), "known now: no question");
        assertEquals(3, g.toCc);
    }

    @Test
    void routesViaRouters() {
        g.fromTun(udpTo("10.1.7.7"));
        assertEquals((long) ip("10.0.0.2"), data(lan.sent.get(0), "tpa"), "the router's MAC is asked for");
        g.fromCc(lan, CH, arpReply("3:back", "10.0.0.2"));
        var f = lan.sent.get(1);
        assertEquals("3:back", f.get("dst"));
        assertEquals((long) ip("10.1.7.7"), data(f, "dst"));
    }

    @Test
    void noRouteIsDropped() {
        g.fromTun(udpTo("10.9.9.9"));
        assertEquals(0, lan.sent.size());
        assertEquals(1, g.dropped);
    }

    @Test
    void noAnswerIsDroppedAfterThreeTries() {
        g.fromTun(udpTo("10.0.0.9"));
        for (now = 0; now <= 3500; now += 250) g.tick();
        assertEquals(3, lan.sent.size());
        assertEquals(1, g.dropped);
        assertEquals(3, g.arpAsked);
        g.fromTun(udpTo("10.0.0.9"));
        assertEquals(4, lan.sent.size(), "asked again afresh");
    }

    @Test
    void broadcastsFromTheTun() {
        g.fromTun(udpTo("10.0.0.255"));
        assertEquals("*", lan.sent.get(0).get("dst"));
        assertEquals("ip", lan.sent.get(0).get("type"));
        assertEquals(1, other.sent.size());
    }

    @Test
    void learnsFromQuestionsAndForgets() {
        g.fromCc(lan, CH, arpRequest("5:back", "10.0.0.5", "10.0.0.1"));
        g.fromTun(udpTo("10.0.0.5"));
        assertEquals("5:back", lan.sent.get(1).get("dst"), "learned from its question");
        now += Gateway.ARP_TTL + 1;
        g.tick();
        g.fromTun(udpTo("10.0.0.5"));
        assertEquals("request", data(lan.sent.get(2), "op"), "forgotten after ARP_TTL");
    }

    @Test
    void newSettingsForgetNeighbours() {
        g.fromCc(lan, CH, arpRequest("5:back", "10.0.0.5", "10.0.0.1"));
        var problems = new ArrayList<String>();
        g.configure(Settings.parse("10.0.0.1/24", CH, "gw2", List.of(), problems));
        g.fromTun(udpTo("10.0.0.5"));
        assertEquals("request", data(lan.sent.get(1), "op"));
        assertEquals("gw2", lan.sent.get(1).get("src"));
    }

    @Test
    void removedPortsGetNothing() {
        g.removePort(other);
        g.fromTun(udpTo("10.0.0.5"));
        assertEquals(1, lan.sent.size());
        assertEquals(0, other.sent.size());
    }

    @Test
    void settings() {
        var problems = new ArrayList<String>();
        var s = Settings.parse("10.0.0.1/24", CH, "gateway",
            List.of("10.1.0.0/16 via 10.0.0.2", "10.1.5.0/24 via 10.0.0.3", "default via 10.0.0.4",
                "10.2.0.0/16 via 192.168.1.1", "nonsense"), problems);
        assertEquals(2, problems.size(), problems.toString());
        assertEquals((long) ip("10.0.0.2"), s.nextHop(Ipv4.parseIp("10.1.9.9")));
        assertEquals((long) ip("10.0.0.3"), s.nextHop(Ipv4.parseIp("10.1.5.9")), "the longest match");
        assertEquals((long) ip("10.0.0.4"), s.nextHop(Ipv4.parseIp("172.16.0.1")));
        assertEquals((long) ip("10.0.0.77"), s.nextHop(Ipv4.parseIp("10.0.0.77")), "on its own network: straight there");
        assertEquals((long) ip("10.0.0.255"), s.broadcast());

        problems.clear();
        var bad = Settings.parse("10.0.0.1", CH, "*", List.of(), problems);
        assertEquals(2, problems.size());
        assertEquals(Settings.DEFAULT_MAC, bad.mac());
        assertEquals("10.0.0.1/24", bad.address());
    }
}
