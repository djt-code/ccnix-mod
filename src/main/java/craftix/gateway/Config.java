package craftix.gateway;

import craftix.gateway.core.Settings;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * The server config, serverconfig/craftix_gateway-server.toml in the world (defaultconfigs/ gives new worlds theirs).
 * Edits take effect while the server runs.
 */
final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("The gateway on the CC network: the address CC routers route at, and the network it's on.").push("gateway");
    }

    static final ModConfigSpec.ConfigValue<String> ADDRESS = BUILDER
        .comment("The gateway's address and its network, a.b.c.d/len. Routers on the gateway's cable take addresses",
            "on this network (ip addr add back 10.0.0.2/24) and route at this one (ip route add default via",
            "10.0.0.1). Give the server's TUN device the same address (helper/craftix-tun-setup), so packets",
            "for it reach the server.")
        .define("address", Settings.DEFAULT_ADDRESS);

    static final ModConfigSpec.IntValue CHANNEL = BUILDER
        .comment("The modem channel: 4040 is cc-turtles' network untagged, 4040 + n is VLAN n.")
        .defineInRange("channel", Settings.DEFAULT_CHANNEL, 0, 65535);

    static final ModConfigSpec.ConfigValue<String> MAC = BUILDER
        .comment("The gateway's MAC on the CC network (CC's are \"<computer id>:<interface>\").")
        .define("mac", Settings.DEFAULT_MAC);

    static final ModConfigSpec.ConfigValue<List<? extends String>> ROUTES = BUILDER
        .comment("Networks behind CC routers on the gateway's network, and the router for each:",
            "[\"10.1.0.0/16 via 10.0.0.2\", \"10.2.0.0/16 via 10.0.0.3\"]. The gateway takes the longest match.",
            "The server needs a route for each into the TUN device too (or one for all of them).")
        .defineListAllowEmpty("routes", List.of(), () -> "10.1.0.0/16 via 10.0.0.2", o -> o instanceof String);

    static {
        BUILDER.pop().comment("The helper that holds the TUN device (helper/craftix-tun.c).").push("tun");
    }

    static final ModConfigSpec.ConfigValue<String> HELPER = BUILDER
        .comment("The helper program, with any arguments before the device's name. It needs no privileges if",
            "the device is set up for the server's user (helper/craftix-tun-setup), or CAP_NET_ADMIN if not.")
        .define("helper", "craftix-tun");

    static final ModConfigSpec.ConfigValue<String> DEVICE = BUILDER
        .comment("The TUN device's name.")
        .define("device", "craftix0");

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
