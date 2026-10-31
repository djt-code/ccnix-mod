package ccnix.gateway.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpRoutesTest {
    // what `ip -4 route show table all dev ccnix0` says on a server set up by ccnix-tun-setup
    static final String IP_OUTPUT = """
        10.0.0.0/8 table 4040 proto boot scope link\s
        10.0.0.0/24 proto kernel scope link src 10.0.0.1\s
        local 10.0.0.1 table local proto kernel scope host src 10.0.0.1\s
        broadcast 10.0.0.255 table local proto kernel scope link src 10.0.0.1\s
        """;
    static final List<OpRoutes.HostRoute> TUN = OpRoutes.parseIpRoutes(IP_OUTPUT);
    static final List<NatConfig.Net> NETS = List.of(NatConfig.net("0.0.0.0/0"));   // (any: these are about the kernel)

    static Settings settings(String... routes) {
        return Settings.parse("10.0.0.1/24", 4040, "gateway", List.of(routes), new ArrayList<>());
    }

    static String problem(String line) {
        return OpRoutes.add(line, settings("10.1.0.0/16 via 10.0.0.2"), 16, NETS, TUN).problem();
    }

    @Test
    void theServersRoutesIntoTheDevice() {
        assertEquals(2, TUN.size(), "local and broadcast aren't routes into it");
        assertEquals("10.0.0.0/8 (table 4040)", TUN.get(0).toString());
        assertEquals("10.0.0.0/24", TUN.get(1).toString());
        var host = OpRoutes.parseIpRoutes("10.9.9.9 proto boot scope link\ndefault table 7 scope link\n");
        assertEquals(32, host.get(0).prefix(), "a bare address is a /32");
        assertEquals(0, host.get(1).prefix());
        assertEquals("7", host.get(1).table());
    }

    @Test
    void aRouteAnOpMayAdd() {
        var c = OpRoutes.add("10.2.0.0/16 via 10.0.0.3", settings("10.1.0.0/16 via 10.0.0.2"), 16, NETS, TUN);
        assertNull(c.problem());
        assertEquals("10.2.0.0/16 via 10.0.0.3", c.route().toString());
        assertNull(problem("10.2.5.0/24 via 10.0.0.3"), "smaller is fine");
        assertNull(problem("10.1.5.0/24 via 10.0.0.9"), "and inside another route: the longest match wins");
    }

    @Test
    void tooLarge() {
        assertTrue(problem("10.0.0.0/8 via 10.0.0.3").contains("/16 at most"));
        assertTrue(problem("10.16.0.0/12 via 10.0.0.3").contains("larger than ops may route"));
        assertNull(OpRoutes.add("10.16.0.0/12 via 10.0.0.3", settings(), 12, NETS, TUN).problem(), "as the config allows");
    }

    @Test
    void neverOutsideWhatTheServerRoutesIntoTheDevice() {
        // a public network, or the host's own LAN: the server doesn't route them into the device, so no op can
        assertTrue(problem("8.8.0.0/16 via 10.0.0.3").contains("doesn't route 8.8.0.0/16 into the TUN device"));
        assertTrue(problem("192.168.1.0/24 via 10.0.0.3").contains("it routes 10.0.0.0/8 (table 4040), 10.0.0.0/24"));
        assertTrue(OpRoutes.add("10.2.0.0/16 via 10.0.0.3", settings(), 16, NETS, List.of()).problem().contains("routes nothing there"));
    }

    @Test
    void notADefaultRouteOrAHost() {
        assertTrue(problem("default via 10.0.0.3").contains("isn't a.b.c.d/len"));
        assertTrue(problem("0.0.0.0/0 via 10.0.0.3").contains("not a default route"));
        assertTrue(problem("10.2.3.4/16 via 10.0.0.3").contains("10.2.0.0/16?"));
    }

    @Test
    void theRouterIsOnTheGatewaysNetwork() {
        assertTrue(problem("10.2.0.0/16 via 10.5.0.3").contains("isn't a router's address on the gateway's network"));
        assertTrue(problem("10.2.0.0/16 via 10.0.0.1").contains("isn't a router's"), "the gateway itself");
        assertTrue(problem("10.2.0.0/16 via 10.0.0.255").contains("isn't a router's"), "the broadcast address");
        assertTrue(problem("10.2.0.0/16 via nowhere").contains("isn't an address"));
    }

    @Test
    void notTheGatewaysOwnNetworkNorTwice() {
        assertTrue(problem("10.0.0.0/16 via 10.0.0.3").contains("overlaps the gateway's own network"));
        assertTrue(problem("10.1.0.0/16 via 10.0.0.3").contains("routed already (10.1.0.0/16 via 10.0.0.2)"));
        assertTrue(problem("10.1.0.0/16").contains("a.b.c.d/len via a.b.c.d"));
    }

    @Test
    void insideTheCcNetworksInEitherMode() {
        var nets = List.of(NatConfig.net("10.1.0.0/16"));
        assertNull(OpRoutes.add("10.1.5.0/24 via 10.0.0.3", settings(), 16, nets, null).problem(), "NAT mode: no kernel to ask");
        assertTrue(OpRoutes.add("10.2.0.0/16 via 10.0.0.3", settings(), 16, nets, null).problem()
            .contains("isn't inside the CC networks this gateway carries (10.1.0.0/16)"));
        assertTrue(OpRoutes.add("10.2.0.0/15 via 10.0.0.3", settings(), 8, List.of(NatConfig.net("10.2.0.0/16")), TUN)
            .problem().contains("isn't inside"), "larger than the network it's in");
    }
}
