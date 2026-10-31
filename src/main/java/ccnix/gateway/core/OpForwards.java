package ccnix.gateway.core;

import java.util.List;

/**
 * The port forwards ops may add and remove in game (/ccnix forward add|del), checked with no game in it: NAT mode only,
 * only if the admin allows it (nat.opForwards), only on the ports the admin set aside (nat.opForwardPorts), only to CC
 * computers on the networks the gateway carries.
 */
public final class OpForwards {
    /** A forward an op asked for, or why not. */
    public record Check(NatConfig.Forward forward, String problem) {
    }

    private OpForwards() {
    }

    /** nat.opForwardPorts ("lo-hi", "port", "") as { lo, hi }, or null for none. */
    public static int[] range(String s) {
        return s == null || s.isBlank() ? null : NatConfig.ports(s);
    }

    /** The range as ops are told it. */
    public static String says(int[] range) {
        if (range == null) return "no ports";
        return range[0] == range[1] ? "port " + range[0] : "ports " + range[0] + "-" + range[1];
    }

    /** Why ops can't change forwards at all, or null if they can. */
    public static String closed(boolean nat, boolean opsMay, int[] range) {
        if (!nat) return "Port forwards are NAT mode's: this gateway is in TUN mode, where the server's admin forwards"
            + " ports with the host's firewall";
        if (!opsMay) return "Ops can't forward ports on this server (nat.opForwards)";
        if (range == null) return "No ports are set aside for ops' forwards on this server (nat.opForwardPorts)";
        return null;
    }

    /** line ("25570[/tcp|/udp] a.b.c.d:port") as a forward an op may add to forwards (the config's, parsed). */
    public static Check add(String line, boolean nat, boolean opsMay, int[] range, List<NatConfig.Net> networks,
                            List<NatConfig.Forward> forwards) {
        var why = closed(nat, opsMay, range);
        if (why != null) return no(why);
        var words = line.trim().split("\\s+");
        if (words.length != 2) return no("Give it as <port>[/tcp|/udp] <a.b.c.d>:<port> (" + says(range) + ")");
        var parsed = NatConfig.forward(words[0] + " -> " + words[1]);
        if (!(parsed instanceof NatConfig.Forward f)) return no(parsed + " (" + says(range) + ")");
        if (f.port() < range[0] || f.port() > range[1]) {
            return no("Port " + f.port() + " isn't one ops may forward here: " + says(range));
        }
        if (networks.stream().noneMatch(n -> n.holds(f.target()))) {
            return no(Ipv4.ipString(f.target()) + " isn't on a CC network this gateway carries ("
                + String.join(", ", networks.stream().map(NatConfig.Net::toString).toList()) + ")");
        }
        for (var x : forwards) {
            if (x.key().equals(f.key())) return no(f.key() + " is forwarded already (" + x + "): del it first");
        }
        return new Check(f, null);
    }

    /** The forward line ("25570[/tcp|/udp]") names, among forwards, if an op may remove it; else why not. */
    public static Check del(String line, boolean nat, boolean opsMay, int[] range, List<NatConfig.Forward> forwards) {
        var why = closed(nat, opsMay, range);
        if (why != null) return no(why);
        var key = line.trim().toLowerCase();
        if (!key.endsWith("/tcp") && !key.endsWith("/udp")) key += "/tcp";
        for (var f : forwards) {
            if (!f.key().equals(key)) continue;
            if (f.port() < range[0] || f.port() > range[1]) {
                return no(f.key() + " is the server admin's, not ops' (ops may forward " + says(range) + ")");
            }
            return new Check(f, null);
        }
        return no("Nothing is forwarded on " + key + " (/ccnix forward lists them)");
    }

    private static Check no(String problem) {
        return new Check(null, problem);
    }
}
