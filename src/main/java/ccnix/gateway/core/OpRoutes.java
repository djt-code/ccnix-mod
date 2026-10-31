package ccnix.gateway.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The routes ops may add to the gateway in game (/ccnix route add), checked with no game in it.
 * <p>
 * A gateway route only says which CC router a network is behind; it never reaches the server's own routing. It has
 * to sit inside the CC networks the admin set (gateway.networks), in either mode; in TUN mode also inside a network
 * the server routes into the TUN device (helper/ccnix-tun-setup), in a table of their own that the host's own routes
 * come before. So nothing an op does can take the host's LAN, its gateway or its way to the internet from it.
 */
public final class OpRoutes {
    /** A route an op asked for, or why it can't be added. */
    public record Check(Settings.Route route, String problem) {
    }

    /** A route the server has into the TUN device: net/prefix, in table (null: main). */
    public record HostRoute(long net, int prefix, String table) {
        boolean holds(long ip, int len) {
            return len >= prefix && (ip & Settings.mask(prefix)) == net;
        }

        @Override
        public String toString() {
            return (prefix == 0 ? "default" : Ipv4.ipString(net) + "/" + prefix) + (table == null ? "" : " (table " + table + ")");
        }
    }

    private static final Set<String> NOT_UNICAST = Set.of("local", "broadcast", "anycast", "multicast", "unreachable",
        "blackhole", "prohibit", "throw", "nat");

    private OpRoutes() {
    }

    /**
     * line ("a.b.c.d/len via a.b.c.d") as a route an op may add to settings: no larger than /largest, behind a router
     * on the gateway's network, not the gateway's own network, inside one of the CC networks, in TUN mode (tun not
     * null) inside a network the server routes into the TUN device, and not routed already.
     */
    public static Check add(String line, Settings s, int largest, List<NatConfig.Net> networks, List<HostRoute> tun) {
        var words = line.trim().split("\\s+");
        if (words.length != 3 || !words[1].equals("via")) return no("give it as a.b.c.d/len via a.b.c.d");
        var net = network(words[0]);
        if (net.problem != null) return no(net.problem);
        long ip = net.ip;
        int len = net.len;
        long gw = Ipv4.parseIp(words[2]);
        if (gw < 0) return no(words[2] + " isn't an address");
        if (len < largest) {
            return no("a /" + len + " is larger than ops may route here: /" + largest + " at most (gateway.opMaxSubnet)");
        }
        if (!s.onNetwork(gw) || gw == s.ip() || gw == s.network() || gw == s.broadcast()) {
            return no(Ipv4.ipString(gw) + " isn't a router's address on the gateway's network, " + s.address());
        }
        if (overlaps(ip, len, s.network(), s.prefix())) {
            return no(words[0] + " overlaps the gateway's own network, " + Ipv4.ipString(s.network()) + "/" + s.prefix());
        }
        for (var r : s.routes()) {
            if (r.net() == ip && r.prefix() == len) return no(words[0] + " is routed already (" + r + "): del it first");
        }
        if (networks.stream().noneMatch(n -> len >= n.len() && n.holds(ip))) {
            return no(words[0] + " isn't inside the CC networks this gateway carries ("
                + (networks.isEmpty() ? "none" : String.join(", ", networks.stream().map(NatConfig.Net::toString).toList()))
                + "): the server's admin sets those (gateway.networks)");
        }
        if (tun != null && tun.stream().noneMatch(t -> t.holds(ip, len))) {
            return no("the server doesn't route " + words[0] + " into the TUN device, so nothing from outside would reach it"
                + (tun.isEmpty() ? " (it routes nothing there)" : " (it routes " + String.join(", ", tun.stream().map(HostRoute::toString).toList()) + ")")
                + ": the server's admin sets those");
        }
        return new Check(new Settings.Route(ip, len, gw), null);
    }

    /** { ip, len } of "a.b.c.d/len", which has to be a network's own address; or why not. */
    public static Net network(String s) {
        int slash = s.indexOf('/');
        long ip = slash < 0 ? -1 : Ipv4.parseIp(s.substring(0, slash));
        int len = -1;
        try {
            if (slash >= 0) len = Integer.parseInt(s.substring(slash + 1));
        } catch (NumberFormatException ignored) {
        }
        if (ip < 0 || len < 0 || len > 32) return new Net(0, 0, s + " isn't a.b.c.d/len");
        if (len == 0) return new Net(0, 0, "not a default route: the server's own routes say where everything else goes");
        long net = ip & Settings.mask(len);
        if (net != ip) return new Net(0, 0, s + " isn't a network's own address (" + Ipv4.ipString(net) + "/" + len + "?)");
        return new Net(net, len, null);
    }

    public record Net(long ip, int len, String problem) {
    }

    static boolean overlaps(long a, int alen, long b, int blen) {
        int len = Math.min(alen, blen);
        return (a & Settings.mask(len)) == (b & Settings.mask(len));
    }

    /**
     * The unicast routes in {@code ip -4 route show table all dev <device>}'s output: "10.0.0.0/8 table 4040 proto
     * boot scope link", "10.0.0.0/24 proto kernel scope link src 10.0.0.1" (a bare address is a /32).
     */
    public static List<HostRoute> parseIpRoutes(String output) {
        var out = new ArrayList<HostRoute>();
        for (var line : output.split("\n")) {
            var w = line.trim().split("\\s+");
            if (w.length == 0 || w[0].isEmpty() || NOT_UNICAST.contains(w[0])) continue;
            String table = null;
            for (int i = 1; i + 1 < w.length; i++) if (w[i].equals("table")) table = w[i + 1];
            if ("main".equals(table)) table = null;
            if (w[0].equals("default")) {
                out.add(new HostRoute(0, 0, table));
                continue;
            }
            int slash = w[0].indexOf('/');
            long ip = Ipv4.parseIp(slash < 0 ? w[0] : w[0].substring(0, slash));
            int len;
            try {
                len = slash < 0 ? 32 : Integer.parseInt(w[0].substring(slash + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (ip < 0 || len < 0 || len > 32) continue;
            out.add(new HostRoute(ip & Settings.mask(len), len, table));
        }
        return out;
    }

    private static Check no(String problem) {
        return new Check(null, problem);
    }
}
