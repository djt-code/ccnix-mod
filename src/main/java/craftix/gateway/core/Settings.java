package craftix.gateway.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The gateway's configuration: its address on the CC network and that network's prefix, the modem channel, its MAC,
 * and routes to the networks behind CC routers there.
 */
public record Settings(long ip, int prefix, int channel, String mac, List<Route> routes) {
    public static final String DEFAULT_ADDRESS = "10.0.0.1/24";
    public static final int DEFAULT_CHANNEL = 4040;
    public static final String DEFAULT_MAC = "gateway";

    /** net/prefix via gw. */
    public record Route(long net, int prefix, long gw) {
        public boolean holds(long ip) {
            return (ip & mask(prefix)) == net;
        }

        @Override
        public String toString() {
            return (prefix == 0 ? "default" : Ipv4.ipString(net) + "/" + prefix) + " via " + Ipv4.ipString(gw);
        }
    }

    public static long mask(int prefix) {
        return prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    public long network() {
        return ip & mask(prefix);
    }

    public long broadcast() {
        return network() | (~mask(prefix) & 0xFFFFFFFFL);
    }

    public boolean onNetwork(long addr) {
        return (addr & mask(prefix)) == network();
    }

    /** Where a packet for dst goes on the CC network: dst itself, a router, or -1 (no route). */
    public long nextHop(long dst) {
        if (onNetwork(dst)) return dst;
        Route best = null;
        for (var r : routes) {
            if (r.holds(dst) && (best == null || r.prefix > best.prefix)) best = r;
        }
        return best == null ? -1 : best.gw;
    }

    public String address() {
        return Ipv4.ipString(ip) + "/" + prefix;
    }

    /**
     * Settings from the config's strings. What can't be used is said in problems and left out (a bad address or MAC:
     * the default).
     */
    public static Settings parse(String address, int channel, String mac, List<? extends String> routes, List<String> problems) {
        var a = cidr(address);
        if (a == null || a[1] < 1 || a[1] > 30) {
            problems.add("address \"" + address + "\" isn't a.b.c.d/len (len 1-30): using " + DEFAULT_ADDRESS);
            a = cidr(DEFAULT_ADDRESS);
        }
        if (mac == null || mac.isBlank() || mac.equals(Gateway.BROADCAST)) {
            problems.add("mac \"" + mac + "\" can't be used: using " + DEFAULT_MAC);
            mac = DEFAULT_MAC;
        }
        var s = new Settings(a[0], (int) a[1], channel, mac.trim(), List.of());
        var kept = new ArrayList<Route>();
        for (var line : routes) {
            var words = line.trim().split("\\s+");
            long[] net = words.length == 3 && words[1].equals("via") ? cidr(words[0]) : null;
            long gw = net == null ? -1 : Ipv4.parseIp(words[2]);
            if (net == null || gw < 0) {
                problems.add("route \"" + line + "\" isn't \"a.b.c.d/len via a.b.c.d\": left out");
            } else if (!s.onNetwork(gw) || gw == s.ip) {
                problems.add("route \"" + line + "\": " + Ipv4.ipString(gw) + " isn't a router on " + s.address() + ": left out");
            } else {
                int len = (int) net[1];
                kept.add(new Route(net[0] & mask(len), len, gw));
            }
        }
        return new Settings(s.ip, s.prefix, s.channel, s.mac, List.copyOf(kept));
    }

    /** { ip, len } from "a.b.c.d/len" ("default" is 0.0.0.0/0), or null. */
    private static long[] cidr(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.equals("default")) return new long[]{ 0, 0 };
        int slash = s.indexOf('/');
        if (slash < 0) return null;
        long ip = Ipv4.parseIp(s.substring(0, slash));
        int len;
        try {
            len = Integer.parseInt(s.substring(slash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (ip < 0 || len < 0 || len > 32) return null;
        return new long[]{ ip, len };
    }
}
