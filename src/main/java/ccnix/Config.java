package ccnix;

import ccnix.gateway.core.NatConfig;
import ccnix.gateway.core.Settings;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * The server config, config/ccnix-server.toml in the server's folder. Edits take effect while the server runs.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("The gateway on the CC network: the address CC routers route at, and the network it's on.").push("gateway");
    }

    public static final ModConfigSpec.ConfigValue<String> MODE = BUILDER
        .comment("How the gateway reaches the real network (the server admin's choice: not changed in game):",
            "  \"nat\": in the server process, with no privileges or setup. CC computers reach the outside as the",
            "         server itself (TCP and UDP; ping and traceroute stop at the gateway), and the outside reaches",
            "         them only through [nat] forwards.",
            "  \"tun\": through a TUN device on the server (Linux, set up as root: helper/ccnix-tun-setup), which",
            "         routes: real addresses, ping, inbound traffic as the host's routing and firewall allow.")
        .define("mode", "nat", o -> "nat".equals(o) || "tun".equals(o));

    public static final ModConfigSpec.ConfigValue<List<? extends String>> NETWORKS = BUILDER
        .comment("The networks behind CC routers that the gateway carries, a.b.c.d/len. Every route (ops' too) must be",
            "inside one. In TUN mode give helper/ccnix-tun-setup the same list.")
        .defineListAllowEmpty("networks", List.of("10.1.0.0/16"), () -> "10.1.0.0/16", o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<String> ADDRESS = BUILDER
        .comment("The gateway's address and its network, a.b.c.d/len. Routers on the gateway's cable take addresses",
            "on this network (ip addr add back 10.0.0.2/24) and route at this one (ip route add default via",
            "10.0.0.1). Give the server's TUN device the same address (helper/ccnix-tun-setup), so packets",
            "for it reach the server.")
        .define("address", Settings.DEFAULT_ADDRESS);

    public static final ModConfigSpec.IntValue CHANNEL = BUILDER
        .comment("The modem channel: 4040 is ccnix's network untagged, 4040 + n is VLAN n.")
        .defineInRange("channel", Settings.DEFAULT_CHANNEL, 0, 65535);

    public static final ModConfigSpec.ConfigValue<String> MAC = BUILDER
        .comment("The gateway's MAC on the CC network (CC's are \"<computer id>:<interface>\").")
        .define("mac", Settings.DEFAULT_MAC);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ROUTES = BUILDER
        .comment("Networks behind CC routers on the gateway's network, and the router for each:",
            "[\"10.1.0.0/16 via 10.0.0.2\", \"10.2.0.0/16 via 10.0.0.3\"]. The gateway takes the longest match.",
            "The server routes networks into the TUN device too (helper/ccnix-tun-setup): each route here needs to be",
            "inside one of those for anything from outside to reach it.")
        .defineListAllowEmpty("routes", List.of(), () -> "10.1.0.0/16 via 10.0.0.2", o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue OP_ROUTES = BUILDER
        .comment("Ops may change the routes in game: /ccnix route add <net/len> via <router>, /ccnix route del <net/len>",
            "(permission level 3). Only inside the networks the server routes into the TUN device, so nothing an op",
            "adds reaches the server's own routing. Always allowed in singleplayer, whatever this says.")
        .define("opRoutes", false);

    public static final ModConfigSpec.IntValue OP_MAX_SUBNET = BUILDER
        .comment("The largest network an op may route, as a prefix length: 16 lets an op route a /16 or anything smaller.")
        .defineInRange("opMaxSubnet", 16, 1, 32);

    public static final ModConfigSpec.ConfigValue<String> VLANS = BUILDER
        .comment("Every gateway block joins one exchange: the untagged channel above (the gateway's network, where CC",
            "routers peer) and these VLANs (lib/net's \"back.20\": channel + 20), carried between the blocks.",
            "\"1-4094\", \"10,20,100-199\", or \"\" for the untagged exchange alone.")
        .define("vlans", "1-4094");

    public static final ModConfigSpec.IntValue MAX_PER_PLAYER = BUILDER
        .comment("How many gateway blocks one player may have placed (0: any number).")
        .defineInRange("maxPerPlayer", 5, 0, 1024);

    static {
        BUILDER.pop().comment("NAT mode. The limits and lists here apply only to CC computers' connections through a",
            "Network Gateway, never to the server's own connections or its players'.").push("nat");
    }

    public static final ModConfigSpec.ConfigValue<List<? extends String>> DENY = BUILDER
        .comment("Where CC computers may not connect: a.b.c.d/len, a.b.c.d/len:port or a.b.c.d/len:lo-hi. By default",
            "private, loopback, link-local (the cloud metadata service), carrier-grade NAT, multicast and reserved",
            "addresses, and port 25 (mail) anywhere.")
        .defineListAllowEmpty("deny", NatConfig.DEFAULT_DENY, () -> "192.168.0.0/16", o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOW = BUILDER
        .comment("Exceptions to deny, in the same form: an allow rule wins (\"192.168.1.10/32:80\" lets CC computers",
            "reach that one web server on the LAN).")
        .defineListAllowEmpty("allow", List.of(), () -> "192.168.1.10/32:80", o -> o instanceof String);

    public static final ModConfigSpec.IntValue MAX_PER_COMPUTER = BUILDER
        .comment("Open TCP connections and UDP flows one CC computer may have through the gateway.")
        .defineInRange("maxPerComputer", 16, 1, 4096);

    public static final ModConfigSpec.IntValue MAX_TOTAL = BUILDER
        .comment("Open TCP connections and UDP flows through the gateway in all.")
        .defineInRange("maxTotal", 256, 1, 65536);

    public static final ModConfigSpec.IntValue TCP_IDLE = BUILDER
        .comment("Seconds a TCP connection may carry nothing before it's closed.")
        .defineInRange("tcpIdle", 600, 10, 86400);

    public static final ModConfigSpec.IntValue UDP_IDLE = BUILDER
        .comment("Seconds a UDP flow may carry nothing before it's forgotten.")
        .defineInRange("udpIdle", 60, 5, 86400);

    public static final ModConfigSpec.ConfigValue<String> DNS_RELAY = BUILDER
        .comment("Port 53 at the gateway's address (ip dns 10.0.0.1 on a CC computer):",
            "  \"system\": relayed, unread, to the server's resolver (the first nameserver in /etc/resolv.conf); on",
            "            Windows, which has none to relay to, answered from the Windows resolver (A and PTR)",
            "  \"a.b.c.d\": relayed to that resolver",
            "  \"\": off. DNS to any other address is never touched.")
        .define("dnsRelay", "system");

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FORWARDS = BUILDER
        .comment("Ports on the server forwarded to CC computers: [\"25570/tcp -> 10.1.1.10:23\"]. The CC computer sees",
            "the client's real address. Only in NAT mode: in TUN mode, forward with the host's firewall.")
        .defineListAllowEmpty("forwards", List.of(), () -> "25570/tcp -> 10.1.1.10:23", o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue OP_FORWARDS = BUILDER
        .comment("Ops may change the forwards in game (/ccnix forward add|del, permission level 3), on opForwardPorts only.",
            "Always allowed in singleplayer, whatever this says, there on 25600-26623 unless opForwardPorts names others.")
        .define("opForwards", false);

    public static final ModConfigSpec.ConfigValue<String> OP_FORWARD_PORTS = BUILDER
        .comment("The ports ops may forward, \"lo-hi\" or one port: the ones the host gave this server for it",
            "(\"25570-25579\"). Empty: none.")
        .define("opForwardPorts", "");

    static {
        BUILDER.pop().comment("TUN mode: the helper that holds the TUN device (helper/ccnix-tun.c).").push("tun");
    }

    public static final ModConfigSpec.ConfigValue<String> HELPER = BUILDER
        .comment("The helper program, with any arguments before the device's name. It needs no privileges if",
            "the device is set up for the server's user (helper/ccnix-tun-setup), or CAP_NET_ADMIN if not.")
        .define("helper", "ccnix-tun");

    public static final ModConfigSpec.ConfigValue<String> DEVICE = BUILDER
        .comment("The TUN device's name.")
        .define("device", "ccnix0");

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
