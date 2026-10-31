package ccnix.gateway.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ops' forwards, NAT mode's config, and DNS answered at the gateway (Windows). */
class OpForwardsTest {
    static final List<NatConfig.Net> NETS = List.of(NatConfig.net("10.1.0.0/16"));
    static final int[] RANGE = OpForwards.range("25570-25579");
    static final List<NatConfig.Forward> EXISTING = List.of((NatConfig.Forward) NatConfig.forward("25570/tcp -> 10.1.1.10:23"),
        (NatConfig.Forward) NatConfig.forward("8080 -> 10.1.1.11:80"));

    static String problem(String line) {
        return OpForwards.add(line, true, true, RANGE, NETS, EXISTING).problem();
    }

    @Test
    void aForwardAnOpMayAdd() {
        var c = OpForwards.add("25571/udp 10.1.1.20:7", true, true, RANGE, NETS, EXISTING);
        assertNull(c.problem());
        assertEquals("25571/udp -> 10.1.1.20:7", c.forward().toString());
        assertNull(problem("25571 10.1.1.20:23"), "tcp unless it says");
        assertNull(problem("25570/udp 10.1.1.20:7"), "the same port, the other protocol");
    }

    @Test
    void onlyInTheAdminsRangeAndTheyreToldIt() {
        assertEquals("Port 25565 isn't one ops may forward here: ports 25570-25579", problem("25565 10.1.1.20:23"));
        assertTrue(problem("25571").contains("(ports 25570-25579)"), "the usage says the range too");
        assertEquals("No ports are set aside for ops' forwards on this server (nat.opForwardPorts)",
            OpForwards.add("25571 10.1.1.20:23", true, true, OpForwards.range(""), NETS, EXISTING).problem());
        assertEquals("port 25570", OpForwards.says(OpForwards.range("25570")));
    }

    @Test
    void closedUnlessTheAdminOpensIt() {
        assertTrue(OpForwards.add("25571 10.1.1.20:23", true, false, RANGE, NETS, EXISTING).problem().contains("nat.opForwards"));
        assertTrue(OpForwards.add("25571 10.1.1.20:23", false, true, RANGE, NETS, EXISTING).problem()
            .contains("TUN mode, where the server's admin forwards ports with the host's firewall"));
    }

    @Test
    void toCcNetworksOnceEach() {
        assertTrue(problem("25571 192.168.1.5:22").contains("isn't on a CC network this gateway carries (10.1.0.0/16)"));
        assertTrue(problem("25570 10.1.1.20:23").contains("is forwarded already (25570/tcp -> 10.1.1.10:23)"));
        assertTrue(problem("25571 10.1.1.20").contains("isn't a.b.c.d:port"));
    }

    @Test
    void delOnlyOpsOwn() {
        assertEquals("25570/tcp -> 10.1.1.10:23", OpForwards.del("25570", true, true, RANGE, EXISTING).forward().toString());
        assertTrue(OpForwards.del("8080", true, true, RANGE, EXISTING).problem().contains("is the server admin's, not ops'"));
        assertTrue(OpForwards.del("25575/udp", true, true, RANGE, EXISTING).problem().contains("Nothing is forwarded on 25575/udp"));
    }

    @Test
    void theConfig() {
        var problems = new ArrayList<String>();
        var c = NatConfig.parse(List.of("10.1.0.0/16", "nonsense"), NatConfig.DEFAULT_DENY, List.of("192.168.1.10/32:80"),
            16, 256, 600, 60, NatConfig.Dns.OFF, List.of("25570 -> 10.1.1.10:23", "25571 -> 192.168.1.5:22", "x"), problems);
        assertEquals(3, problems.size(), String.join("\n", problems));
        assertEquals(List.of("25570/tcp -> 10.1.1.10:23"), c.forwards().stream().map(Object::toString).toList());
        assertFalse(c.allowed(Ipv4.parseIp("169.254.169.254"), 80), "the cloud metadata service");
        assertFalse(c.allowed(Ipv4.parseIp("10.9.9.9"), 443));
        assertFalse(c.allowed(Ipv4.parseIp("1.1.1.1"), 25), "mail, anywhere");
        assertTrue(c.allowed(Ipv4.parseIp("1.1.1.1"), 53));
        assertTrue(c.allowed(Ipv4.parseIp("192.168.1.10"), 80), "an allow rule wins");
        assertFalse(c.allowed(Ipv4.parseIp("192.168.1.10"), 81));
        assertTrue(c.cc(Ipv4.parseIp("10.1.200.3")));
        assertNull(NatConfig.rule("10.0.0.0/8:90-80"), "a backwards range");
    }

    @Test
    void dnsAnsweredOnWindows() {
        // the question netdb sends (sim/net_dns.lua checks it byte for byte against a real one)
        var q = Packets.hex("123401000001000000000000037777770667697468756203636f6d0000010001");
        var question = DnsAnswer.question(q);
        assertEquals("www.github.com", question.name());
        assertEquals(1, question.type());
        var reply = DnsAnswer.answer(question, new DnsAnswer.Resolver() {
            @Override
            public List<Long> addresses(String name) {
                return List.of(Ipv4.parseIp("140.82.116.3"), Ipv4.parseIp("140.82.116.4"));
            }

            @Override
            public String name(long ip) {
                return null;
            }
        });
        assertEquals(0x1234, Ipv4.u16(reply, 0));
        assertEquals(0x8180, Ipv4.u16(reply, 2), "a reply, recursion desired and available, NOERROR");
        assertEquals(2, Ipv4.u16(reply, 6), "two answers");
        int at = 12 + question.section().length;
        assertEquals(0xC00C, Ipv4.u16(reply, at), "the question's name, by pointer");
        assertEquals(140, reply[at + 12] & 255);
        assertNull(DnsAnswer.question(reply), "a reply isn't a question");
        assertEquals(Ipv4.parseIp("1.2.3.4"), DnsAnswer.reverse("4.3.2.1.in-addr.arpa"));
    }
}
