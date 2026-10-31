package ccnix.gateway.core;

import java.util.ArrayList;
import java.util.List;

/**
 * NAT mode's settings: the CC networks it carries, the outbound policy and limits (which apply only to CC computers'
 * connections through the gateway), the DNS relay at the gateway's address, and the port forwards.
 */
public record NatConfig(List<Net> networks, List<Rule> deny, List<Rule> allow, int maxPerComputer, int maxTotal,
                        long tcpIdleMs, long udpIdleMs, Dns dns, List<Forward> forwards) {
    public static final List<String> DEFAULT_DENY = List.of("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4", "0.0.0.0/0:25");

    /** net/len. */
    public record Net(long ip, int len) {
        public boolean holds(long a) {
            return (a & Settings.mask(len)) == ip;
        }

        @Override
        public String toString() {
            return Ipv4.ipString(ip) + "/" + len;
        }
    }

    /** net/len, and ports lo-hi (0-65535: any). */
    public record Rule(Net net, int lo, int hi) {
        public boolean matches(long ip, int port) {
            return net.holds(ip) && port >= lo && port <= hi;
        }
    }

    /** What answers at the gateway's address on port 53: nothing, a relay to a resolver, or (Windows) an answerer. */
    public record Dns(Kind kind, long resolver, DnsAnswer.Resolver answerer) {
        public enum Kind { OFF, RELAY, ANSWER }

        public static final Dns OFF = new Dns(Kind.OFF, 0, null);

        @Override
        public String toString() {
            return switch (kind) {
                case OFF -> "off";
                case RELAY -> "relayed to " + Ipv4.ipString(resolver);
                case ANSWER -> "answered from the Windows resolver";
            };
        }
    }

    /** A port on the server forwarded to a CC host's port. */
    public record Forward(int port, boolean udp, long target, int targetPort) {
        public String key() {
            return port + (udp ? "/udp" : "/tcp");
        }

        @Override
        public String toString() {
            return key() + " -> " + Ipv4.ipString(target) + ":" + targetPort;
        }
    }

    /** May a CC computer reach ip:port? An allow rule wins over a deny rule. */
    public boolean allowed(long ip, int port) {
        for (var r : allow) if (r.matches(ip, port)) return true;
        for (var r : deny) if (r.matches(ip, port)) return false;
        return true;
    }

    /** Is ip one of the CC networks? */
    public boolean cc(long ip) {
        for (var n : networks) if (n.holds(ip)) return true;
        return false;
    }

    /** "a.b.c.d/len" (no /len: a /32), or null. */
    public static Net net(String s) {
        s = s.trim();
        int slash = s.indexOf('/');
        long ip = Ipv4.parseIp(slash < 0 ? s : s.substring(0, slash));
        int len;
        try {
            len = slash < 0 ? 32 : Integer.parseInt(s.substring(slash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (ip < 0 || len < 0 || len > 32) return null;
        return new Net(ip & Settings.mask(len), len);
    }

    /** "a.b.c.d/len", "a.b.c.d/len:port" or "a.b.c.d/len:lo-hi", or null. */
    public static Rule rule(String s) {
        s = s.trim();
        int colon = s.indexOf(':');
        var n = net(colon < 0 ? s : s.substring(0, colon));
        if (n == null) return null;
        if (colon < 0) return new Rule(n, 0, 65535);
        var range = ports(s.substring(colon + 1));
        return range == null ? null : new Rule(n, range[0], range[1]);
    }

    /** "port" or "lo-hi" as { lo, hi }, or null. */
    public static int[] ports(String s) {
        s = s.trim();
        int dash = s.indexOf('-');
        try {
            int lo = Integer.parseInt(dash < 0 ? s : s.substring(0, dash).trim());
            int hi = dash < 0 ? lo : Integer.parseInt(s.substring(dash + 1).trim());
            if (lo < 0 || hi > 65535 || lo > hi) return null;
            return new int[]{ lo, hi };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "25570/tcp -> 10.1.1.10:23" (no /proto: tcp), or why not. */
    public static Object forward(String s) {
        var parts = s.split("->");
        if (parts.length != 2) return "give it as port[/tcp|/udp] -> a.b.c.d:port";
        var from = parts[0].trim().toLowerCase();
        boolean udp = from.endsWith("/udp");
        if (udp || from.endsWith("/tcp")) from = from.substring(0, from.length() - 4);
        var to = parts[1].trim();
        int colon = to.lastIndexOf(':');
        long target = colon < 0 ? -1 : Ipv4.parseIp(to.substring(0, colon));
        int port, tport;
        try {
            port = Integer.parseInt(from);
            tport = colon < 0 ? -1 : Integer.parseInt(to.substring(colon + 1));
        } catch (NumberFormatException e) {
            return "give it as port[/tcp|/udp] -> a.b.c.d:port";
        }
        if (target < 0) return to + " isn't a.b.c.d:port";
        if (port < 1 || port > 65535 || tport < 1 || tport > 65535) return "ports are 1-65535";
        return new Forward(port, udp, target, tport);
    }

    /** Parse what can be; what can't is said in problems and left out. */
    public static NatConfig parse(List<? extends String> networks, List<? extends String> deny, List<? extends String> allow,
                                  int maxPerComputer, int maxTotal, int tcpIdle, int udpIdle, Dns dns,
                                  List<? extends String> forwards, List<String> problems) {
        var nets = new ArrayList<Net>();
        for (var s : networks) {
            var n = net(s);
            if (n == null) problems.add("network \"" + s + "\" isn't a.b.c.d/len: left out");
            else nets.add(n);
        }
        var denied = rules(deny, "nat.deny", problems);
        var allowed = rules(allow, "nat.allow", problems);
        var fw = new ArrayList<Forward>();
        for (var s : forwards) {
            var f = forward(s);
            if (f instanceof Forward ok) {
                if (nets.stream().noneMatch(n -> n.holds(ok.target()))) {
                    problems.add("forward \"" + s + "\": " + Ipv4.ipString(ok.target()) + " isn't on a CC network: left out");
                } else if (fw.stream().anyMatch(x -> x.key().equals(ok.key()))) {
                    problems.add("forward \"" + s + "\": " + ok.key() + " is forwarded twice: the first kept");
                } else {
                    fw.add(ok);
                }
            } else {
                problems.add("forward \"" + s + "\": " + f + ": left out");
            }
        }
        return new NatConfig(List.copyOf(nets), denied, allowed, maxPerComputer, maxTotal, tcpIdle * 1000L,
            udpIdle * 1000L, dns, List.copyOf(fw));
    }

    private static List<Rule> rules(List<? extends String> list, String what, List<String> problems) {
        var out = new ArrayList<Rule>();
        for (var s : list) {
            var r = rule(s);
            if (r == null) problems.add(what + " \"" + s + "\" isn't a.b.c.d/len[:port[-port]]: left out");
            else out.add(r);
        }
        return List.copyOf(out);
    }
}
