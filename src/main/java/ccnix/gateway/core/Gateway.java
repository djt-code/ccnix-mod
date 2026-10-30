package ccnix.gateway.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The gateway between a CC network (cc-turtles' frames on one modem channel) and the server's TUN device.
 * <p>
 * It is the link layer for the server, which is the router: CC hosts and routers send packets for the outside to
 * the gateway's address (it answers ARP for it), and each goes into the TUN device as IPv4; the server delivers or
 * forwards it. A packet the server sends into the TUN device goes to its destination when that is on the gateway's
 * network, or to the CC router a route names, asking ARP for its MAC first (packets wait meanwhile, and are
 * dropped if no answer comes). The server's own address on the TUN device should be the gateway's address, so
 * packets for it reach the server itself.
 * <p>
 * Ports are the gateway's blocks: one CC network each (several can share one). A frame for a MAC goes out of the
 * port it was last heard on; a broadcast goes out of every port.
 * <p>
 * Not thread-safe: the mod calls it from one thread.
 */
public final class Gateway {
    public static final String BROADCAST = "*";
    static final long ARP_TTL = 300_000, ARP_RETRY = 1_000;
    static final int ARP_TRIES = 3, QUEUE_MAX = 32, RECENT = 32;

    /** A block: sends frames into its CC network. */
    public interface Port {
        void send(int channel, Map<String, Object> frame);
    }

    /** The TUN device. */
    public interface Tun {
        void write(byte[] packet);
    }

    private record Neighbour(String mac, long expires) {
    }

    private static final class Waiting {
        final long ip;
        final List<Map<String, Object>> packets = new ArrayList<>();
        int tries;
        long next;

        Waiting(long ip) {
            this.ip = ip;
        }
    }

    private final Tun tun;
    private final LongSupplier clock;
    private Settings settings;
    private final Set<Port> ports = new LinkedHashSet<>();
    private final Map<Long, Neighbour> arp = new HashMap<>();
    private final Map<Long, Waiting> waiting = new HashMap<>();
    private final Map<String, Port> heardOn = new HashMap<>();
    private final Object[] recent = new Object[RECENT];
    private int recentNext;

    /** Packets from CC into the TUN device, from it into CC, dropped (unreadable, no route, no ARP answer). */
    public long toTun, toCc, dropped, arpAsked;

    /** clock: milliseconds. */
    public Gateway(Settings settings, Tun tun, LongSupplier clock) {
        this.settings = settings;
        this.tun = tun;
        this.clock = clock;
    }

    public Settings settings() {
        return settings;
    }

    /** New settings; what was learned under another address, MAC or channel is forgotten. */
    public void configure(Settings s) {
        var old = settings;
        settings = s;
        if (old.ip() != s.ip() || old.prefix() != s.prefix() || !old.mac().equals(s.mac()) || old.channel() != s.channel()) {
            arp.clear();
            waiting.clear();
            heardOn.clear();
        }
    }

    public void addPort(Port p) {
        ports.add(p);
    }

    public void removePort(Port p) {
        ports.remove(p);
        heardOn.values().removeIf(q -> q == p);
    }

    public int portCount() {
        return ports.size();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From CC
    // ---------------------------------------------------------------------------------------------------------------

    /** A modem message heard by a port. The same one heard by two ports (one network) counts once. */
    public void fromCc(Port port, int channel, Object payload) {
        var s = settings;
        if (channel != s.channel() || !(payload instanceof Map<?, ?> f) || seen(payload)) return;
        if (!(f.get("ccnet") instanceof Number n) || n.doubleValue() != 1) return;
        if (!(f.get("src") instanceof String src) || !(f.get("dst") instanceof String dst)) return;
        if (src.equals(s.mac())) return;
        boolean mine = dst.equals(s.mac());
        if (!mine && !dst.equals(BROADCAST)) return;
        if (ports.contains(port)) heardOn.put(src, port);
        Object type = f.get("type");
        if ("arp".equals(type)) arpIn(f.get("data"));
        else if ("ip".equals(type)) ipIn(mine, f.get("data"));
    }

    private boolean seen(Object payload) {
        for (var o : recent) if (o == payload) return true;
        recent[recentNext] = payload;
        recentNext = (recentNext + 1) % RECENT;
        return false;
    }

    private void ipIn(boolean mine, Object data) {
        var p = Ipv4.fromCc(data);
        if (p == null) {
            dropped++;
            return;
        }
        if (!mine) {                                    // (a broadcast frame: only broadcast packets)
            long dst = Ipv4.dst(p);
            if (dst != Ipv4.ALL && dst != settings.broadcast()) return;
        }
        toTun++;
        tun.write(p);
    }

    private void arpIn(Object data) {
        if (!(data instanceof Map<?, ?> a) || !(a.get("sha") instanceof String sha)) return;
        Long spa = Ipv4.u32(a.get("spa")), tpa = Ipv4.u32(a.get("tpa"));
        if (spa == null) return;
        var s = settings;
        if (tpa != null && tpa == s.ip()) {
            learn(spa, sha);
            if ("request".equals(a.get("op"))) {
                send(sha, "arp", table("op", "reply", "sha", s.mac(), "spa", s.ip(), "tha", sha, "tpa", spa));
            }
        } else if (arp.containsKey(spa)) {
            learn(spa, sha);                            // (it moved: keep up)
        }
    }

    private void learn(long ip, String mac) {
        arp.put(ip, new Neighbour(mac, clock.getAsLong() + ARP_TTL));
        var w = waiting.remove(ip);
        if (w == null) return;
        for (var pkt : w.packets) {
            toCc++;
            send(mac, "ip", pkt);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From the TUN device
    // ---------------------------------------------------------------------------------------------------------------

    public void fromTun(byte[] p) {
        var s = settings;
        var pkt = Ipv4.toCc(p);
        if (pkt == null) {
            dropped++;
            return;
        }
        long dst = Ipv4.dst(p);
        if (dst == Ipv4.ALL || dst == s.broadcast()) {
            toCc++;
            send(BROADCAST, "ip", pkt);
            return;
        }
        long hop = s.nextHop(dst);
        if (hop < 0 || hop == s.ip()) {
            dropped++;
            return;
        }
        var n = arp.get(hop);
        if (n != null && n.expires() > clock.getAsLong()) {
            toCc++;
            send(n.mac(), "ip", pkt);
            return;
        }
        var w = waiting.computeIfAbsent(hop, Waiting::new);
        if (w.packets.size() < QUEUE_MAX) w.packets.add(pkt);
        else dropped++;
        if (w.tries == 0) ask(w);
    }

    private void ask(Waiting w) {
        w.tries++;
        w.next = clock.getAsLong() + ARP_RETRY;
        arpAsked++;
        var s = settings;
        send(BROADCAST, "arp", table("op", "request", "sha", s.mac(), "spa", s.ip(), "tpa", w.ip));
    }

    /** ARP retries and expiry; call it a few times a second. */
    public void tick() {
        long now = clock.getAsLong();
        for (Iterator<Waiting> it = waiting.values().iterator(); it.hasNext(); ) {
            var w = it.next();
            if (now < w.next) continue;
            if (w.tries >= ARP_TRIES) {
                dropped += w.packets.size();
                it.remove();
            } else {
                ask(w);
            }
        }
        arp.values().removeIf(n -> n.expires() <= now);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void send(String dstMac, String type, Object data) {
        var s = settings;
        var f = table("ccnet", 1, "dst", dstMac, "src", s.mac(), "type", type, "data", data);
        var port = dstMac.equals(BROADCAST) ? null : heardOn.get(dstMac);
        if (port != null) {
            port.send(s.channel(), f);
        } else {
            for (var p : ports) p.send(s.channel(), f);
        }
    }

    private static Map<String, Object> table(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** What /gateway shows. */
    public List<String> status() {
        var s = settings;
        long now = clock.getAsLong();
        var out = new ArrayList<String>();
        out.add("address " + s.address() + ", MAC \"" + s.mac() + "\", channel " + s.channel() + ", " + ports.size()
            + (ports.size() == 1 ? " block" : " blocks"));
        for (var r : s.routes()) out.add("route " + r);
        arp.forEach((ip, n) -> out.add("neighbour " + Ipv4.ipString(ip) + " is " + n.mac() + " ("
            + Math.max(0, (n.expires() - now) / 1000) + " s)"));
        waiting.forEach((ip, w) -> out.add("asking ARP for " + Ipv4.ipString(ip) + ": " + w.packets.size() + " waiting"));
        out.add("packets: " + toTun + " from CC, " + toCc + " into CC, " + dropped + " dropped; " + arpAsked + " ARP requests");
        return out;
    }
}
