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
        final List<Integer> channels = new ArrayList<>();
        int hellos;
        long rank;
        final List<Gateway.Port> standbys = new ArrayList<>();
        java.util.function.IntPredicate filter = v -> true;
        int nativeVlan = 1;

        @Override
        public void send(int channel, Map<String, Object> frame) {
            channels.add(channel);
            sent.add(frame);
        }

        @Override
        public void hello() {
            hellos++;
        }

        @Override
        public long rank() {
            return rank;
        }

        @Override
        public void standby(Gateway.Port active) {
            standbys.add(active);
        }

        @Override
        public boolean carries(int vlan) {
            return filter.test(vlan);
        }

        @Override
        public int nativeVlan() {
            return nativeVlan;
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
        var question = arpRequest("3:back", "10.0.0.2", "10.0.0.1");
        g.fromCc(lan, CH, question);
        assertEquals(1, lan.sent.size());
        assertEquals(List.of(question), other.sent, "the question, a broadcast, crosses the exchange; the answer goes"
            + " only where the question came from");
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
        g.fromCc(lan, CH + 2, arpRequest("3:back.2", "10.0.0.2", "10.0.0.1"));
        g.fromCc(lan, CH, frame("3:back", "4:back", "ip", t()));
        assertEquals(0, lan.sent.size() + tun.size(), "not the gateway's: nothing answered, nothing into the TUN");
        assertEquals(List.of(CH, CH + 2, CH), other.channels, "but carried across the exchange (and VLAN 2)");
        g.fromCc(lan, CH + 1, arpRequest("3:back.1", "10.0.0.2", "10.0.0.1"));
        assertEquals(0, lan.sent.size(), "VLAN 1 tagged on a block whose native VLAN is 1: dropped");
        g.fromCc(lan, CH, frame("gateway", "*", "arp", t("op", "request", "sha", "gateway", "spa", ip("10.0.0.1"), "tpa", ip("10.0.0.1"))));
        g.fromCc(lan, CH, t("hello", "world"));
        g.fromCc(lan, CH, "rednet, say");
        g.fromCc(lan, CH - 1, arpRequest("3:back", "10.0.0.2", "10.0.0.1"));
        g.fromCc(lan, CH + Gateway.VLAN_MAX + 1, arpRequest("3:back", "10.0.0.2", "10.0.0.1"));
        assertEquals(3, other.sent.size(), "its own MAC, not lib/net's frames, other channels: not even carried");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The exchange
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theExchangeLearnsWhereMacsAre() {
        var third = new Port();
        g.addPort(third);
        var toB = frame("3:back", "4:back", "ip", t());
        g.fromCc(lan, CH, toB);
        assertEquals(List.of(toB), other.sent, "4:back not heard yet: to every other block");
        assertEquals(List.of(toB), third.sent);
        assertEquals(0, lan.sent.size(), "never back where it came from");
        var back = frame("4:back", "3:back", "ip", t());
        g.fromCc(third, CH, back);
        assertEquals(List.of(back), lan.sent, "3:back was heard on lan: only there");
        assertEquals(1, other.sent.size());
        var again = frame("3:back", "4:back", "ip", t());
        g.fromCc(lan, CH, again);
        assertEquals(List.of(toB, again), third.sent, "and 4:back on third");
        assertEquals(1, other.sent.size());
        assertEquals(4, g.relayed);
        assertEquals(0, tun.size());
    }

    @Test
    void vlansCarriedAreTheConfigs() {
        var problems = new ArrayList<String>();
        g.configureVlans(Gateway.vlans("20, 100-102", problems));
        assertEquals(List.of(), problems);
        g.fromCc(lan, CH + 20, frame("3:back.20", "*", "arp", t()));
        g.fromCc(lan, CH + 101, frame("3:back.101", "*", "arp", t()));
        g.fromCc(lan, CH + 30, frame("3:back.30", "*", "arp", t()));
        g.fromCc(lan, CH, frame("3:back", "*", "arp", t()));
        assertEquals(List.of(CH + 20, CH + 101), other.channels, "VLAN 30 isn't carried, nor 1 (the exchange) here");
        g.fromTun(udpTo("10.0.0.255"));
        assertEquals(2, other.sent.size(), "the gateway is on VLAN 1: the server's list leaves it out");
        assertEquals("20, 100-102", Gateway.says(Gateway.vlans("20, 100-102", problems)));
        assertEquals("1-4094", Gateway.says(Gateway.vlans("1-4094", problems)));
        assertEquals("none", Gateway.says(Gateway.vlans("", problems)));
        assertEquals(List.of(), problems);
        Gateway.vlans("0, 5-3, 4095, x, 7", problems);
        assertEquals(4, problems.size(), problems.toString());
    }

    @Test
    void aSecondBlockOnOneNetworkStandsBy() {
        var third = new Port();                         // (on lan's network: they hear each other's beacons)
        third.rank = 7;
        lan.rank = 3;
        g.addPort(third);
        for (now = 0; now < 3000; now += 250) g.tick();
        assertTrue(lan.hellos >= 2 && third.hellos >= 2 && other.hellos >= 2, "beacons every 2 s");
        g.heard(third, lan);
        assertSame(lan, g.standingBy(third), "the higher rank stands by");
        assertNull(g.standingBy(lan));
        assertEquals(java.util.Arrays.asList(lan), third.standbys, "and is told so, once");
        g.heard(third, lan);
        assertEquals(1, third.standbys.size());

        g.fromCc(other, CH, arpRequest("4:back", "10.0.0.4", "10.0.0.9"));
        assertEquals(1, lan.sent.size(), "into lan's network once, through lan");
        assertEquals(0, third.sent.size());
        g.fromCc(third, CH, frame("3:back", "*", "arp", t()));
        assertEquals(0, other.sent.size(), "what the spare hears isn't carried (lan heard it too)");
        g.fromTun(udpTo("10.0.0.255"));
        assertEquals(0, third.sent.size(), "nor the gateway's own");
        assertEquals(2, g.networks());
        assertEquals(List.of("standing by: another gateway block is on this cable network, and only one works on each"
            + " (this one takes over if that one goes)"), g.portStatus(third));
        assertTrue(g.portStatus(lan).contains("1 other block stands by on its network"), g.portStatus(lan).toString());

        g.removePort(lan);                              // (lan's chunk unloads)
        assertNull(g.standingBy(third), "the spare takes over at once");
        assertEquals(java.util.Arrays.asList(lan, null), third.standbys);
        g.fromCc(other, CH, arpRequest("4:back", "10.0.0.4", "10.0.0.8"));
        assertEquals(1, third.sent.size());

        g.addPort(lan);
        g.heard(lan, third);
        assertSame(lan, g.standingBy(third), "lan back: it ranks lower, so it works again and the spare stands by");
    }

    @Test
    void apartAgainWhenTheBeaconsStop() {
        var third = new Port();
        g.addPort(third);
        g.heard(lan, third);
        assertSame(lan, g.standingBy(third), "the same rank: the first one works");
        now += Gateway.PEER_TTL + 1;                    // (the cable cut: no more beacons heard)
        g.tick();
        assertNull(g.standingBy(third));
        assertEquals(3, g.networks());
        g.fromCc(other, CH, arpRequest("4:back", "10.0.0.4", "10.0.0.8"));
        assertEquals(1, lan.sent.size());
        assertEquals(1, third.sent.size(), "apart again: each gets one");
    }

    @Test
    void eachBlocksOwnFilter() {
        var third = new Port();
        g.addPort(third);
        other.filter = v -> v >= 20 && v <= 29;          // (a trunk: no exchange, VLANs 20-29)
        g.fromCc(lan, CH, frame("3:back", "*", "arp", t()));
        g.fromCc(lan, CH + 25, frame("3:back.25", "*", "arp", t()));
        g.fromCc(lan, CH + 30, frame("3:back.30", "*", "arp", t()));
        assertEquals(List.of(CH + 25), other.channels, "out of other: VLAN 25 only");
        assertEquals(List.of(CH, CH + 25, CH + 30), third.channels, "third is open");
        g.fromCc(other, CH, arpRequest("4:back", "10.0.0.4", "10.0.0.1"));
        g.fromCc(other, CH + 30, frame("4:back.30", "*", "arp", t()));
        g.fromCc(other, CH + 21, frame("4:back.21", "*", "arp", t()));
        assertEquals(1, lan.sent.stream().filter(f -> "4:back.21".equals(f.get("src"))).count(), "in from other: 21 only");
        assertEquals(1, lan.sent.size(), "not its untagged ARP: the gateway didn't hear it, so no answer either");
        g.fromTun(udpTo("10.0.0.255"));
        assertEquals(List.of(CH + 25), other.channels, "nor the gateway's own frames (they're untagged)");
        assertTrue(g.portStatus(other).get(0).endsWith(", 5 filtered"), g.portStatus(other).get(0));
    }

    @Test
    void nativeVlansAreUntaggedOnTheirCable() {
        lan.nativeVlan = 10;                            // (its cable's plain computers are on VLAN 10)
        g.fromCc(lan, CH, frame("3:back", "*", "arp", t()));
        assertEquals(List.of(CH + 10), other.channels, "untagged on lan is VLAN 10: tagged on other");
        g.fromCc(other, CH + 10, frame("4:back.10", "3:back", "ip", t()));
        assertEquals(List.of(CH), lan.channels, "and back to lan untagged");
        g.fromCc(lan, CH + 10, frame("5:back.10", "*", "arp", t()));
        assertEquals(1, other.sent.size(), "VLAN 10 tagged on lan, its native: dropped");
        g.fromCc(other, CH, arpRequest("4:back", "10.0.0.4", "10.0.0.9"));
        assertEquals(List.of(CH, CH + 1), lan.channels, "the exchange (VLAN 1) reaches lan tagged: back.1");
        g.fromCc(lan, CH + 1, arpRequest("3:back.1", "10.0.0.3", "10.0.0.1"));
        assertEquals("3:back.1", lan.sent.get(2).get("dst"), "and the gateway answers back.1 there");
        assertEquals(CH + 1, lan.channels.get(2));

        lan.nativeVlan = 0;                             // (none: untagged frames are dropped)
        g.fromCc(lan, CH, frame("3:back", "*", "arp", t()));
        assertEquals(2, other.sent.size());
        g.fromTun(udpTo("10.0.0.255"));
        assertEquals(CH + 1, lan.channels.get(lan.channels.size() - 1), "VLAN 1 out of a block with none: tagged");
    }

    @Test
    void eachBlocksStatus() {
        g.fromCc(lan, CH, frame("3:back", "*", "arp", t()));
        g.fromCc(lan, CH + 20, frame("3:back.20", "*", "arp", t()));
        var lines = g.portStatus(lan);
        assertEquals("frames: 2 in, 0 out (0 from other blocks)", lines.get(0));
        assertEquals("2 MACs heard here: 3:back, 3:back.20 (VLAN 20)", lines.get(1));
        assertEquals("VLANs seen here: 1, 20", lines.get(2));
        assertEquals("the only block on its network", lines.get(3));
        assertEquals("frames: 0 in, 2 out (2 from other blocks)", g.portStatus(other).get(0));
        assertEquals(List.of("this block isn't on the gateway yet"), g.portStatus(new Port()));
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
        assertEquals(1, lan.sent.size(), "a reply to the gateway stays with the gateway");
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
