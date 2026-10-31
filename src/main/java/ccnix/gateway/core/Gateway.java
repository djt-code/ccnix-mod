package ccnix.gateway.core;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * The gateway between a CC network (lib/net's frames on one modem channel) and the server's TUN device, and the
 * exchange its blocks make.
 * <p>
 * The exchange: every gateway block is a port on one switch, as a switch's trunk ports are. Inside it every frame is
 * on a VLAN, 1-4094; VLAN 1 is the "routing exchange" (10.0.0.0/24, say, where routers peer, and where the gateway
 * itself is). On a block's cable a VLAN is a modem channel: its native VLAN (1 unless set; or none) untagged, on the
 * gateway's channel, and every other VLAN tagged, on the channel + its number (lib/net's "back.20"), as a trunk port
 * carries them. A tagged frame for the native VLAN is dropped, so each VLAN has one way on and off a cable. Each block
 * passes only the VLANs in its own list, the server's list over all. A frame heard on one block goes to the block a MAC was
 * last heard on, or, for a broadcast or a MAC not yet heard, to every other block. One block per CC network: blocks
 * that hear each other's beacons ({@link Port#hello()}) share one, and all but the first (the lowest
 * {@link Port#rank()}) stand by, carrying nothing, until it goes (its chunk unloads, the cable is cut): a spare, not
 * a loop. The gateway itself is a host on the exchange.
 * <p>
 * It is the link layer for the server, which is the router: CC hosts and routers send packets for the outside to
 * the gateway's address (it answers ARP for it), and each goes into the TUN device as IPv4; the server delivers or
 * forwards it. A packet the server sends into the TUN device goes to its destination when that is on the gateway's
 * network, or to the CC router a route names, asking ARP for its MAC first (packets wait meanwhile, and are
 * dropped if no answer comes). The server's own address on the TUN device should be the gateway's address, so
 * packets for it reach the server itself.
 * <p>
 * Not thread-safe: the mod calls it from one thread.
 */
public final class Gateway {
    public static final String BROADCAST = "*";
    public static final int VLAN_MAX = 4094;
    static final long ARP_TTL = 300_000, ARP_RETRY = 1_000, MAC_TTL = 300_000, HELLO_EVERY = 2_000, PEER_TTL = 7_000;
    static final int ARP_TRIES = 3, QUEUE_MAX = 32, RECENT = 64;

    /** A block: sends frames into its CC network. */
    public interface Port {
        void send(int channel, Map<String, Object> frame);

        /** A beacon the blocks on the same CC network hear ({@link #heard}); none by default. */
        default void hello() {
        }

        /** Of the blocks on one CC network, the lowest works and the rest stand by. */
        default long rank() {
            return 0;
        }

        /** It stands by now (for active, on its network), or works again (active null). */
        default void standby(Port active) {
        }

        /** Its own list: whether it passes VLAN vlan (1-4094; 1 is the exchange) either way; all by default. */
        default boolean carries(int vlan) {
            return true;
        }

        /** The VLAN its cable's untagged frames are on (1 unless set; 0: none, untagged frames are dropped). */
        default int nativeVlan() {
            return 1;
        }
    }

    /** The TUN device. */
    public interface Tun {
        void write(byte[] packet);
    }

    private record Neighbour(String mac, long expires) {
    }

    private record Learned(Port port, long expires) {
    }

    /** One block's counts. */
    private static final class Stats {
        long in, out, relayed, filtered;
        final BitSet vlans = new BitSet();
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
    private BitSet vlans = vlans("1-" + VLAN_MAX, new ArrayList<>());
    private final Map<Port, Stats> ports = new LinkedHashMap<>();
    private final Map<Port, Map<Port, Long>> peers = new IdentityHashMap<>();
    private final Map<Port, Port> standby = new IdentityHashMap<>();
    private final Map<Long, Neighbour> arp = new HashMap<>();
    private final Map<Long, Waiting> waiting = new HashMap<>();
    private final Map<String, Learned> macs = new HashMap<>();
    private final Object[] recent = new Object[RECENT];
    private int recentNext;
    private long nextHello;

    /** Packets from CC into the TUN device, from it into CC, dropped (unreadable, no route, no ARP answer). */
    public long toTun, toCc, dropped, arpAsked;
    /** Frames passed between blocks on the exchange and its VLANs. */
    public long relayed;

    /** clock: milliseconds. */
    public Gateway(Settings settings, Tun tun, LongSupplier clock) {
        this.settings = settings;
        this.tun = tun;
        this.clock = clock;
    }

    /** New settings; what was learned under another address, MAC or channel is forgotten. */
    public void configure(Settings s) {
        var old = settings;
        settings = s;
        if (old.ip() != s.ip() || old.prefix() != s.prefix() || !old.mac().equals(s.mac()) || old.channel() != s.channel()) {
            arp.clear();
            waiting.clear();
            macs.clear();
        }
    }

    /** The VLANs any block may carry (VLAN 1 is the exchange): the server's list ({@link #vlans(String, List)}). */
    public void configureVlans(BitSet carried) {
        vlans = (BitSet) carried.clone();
    }

    /** "1-4094", "10,20,100-199" or "" (none) as a set of VLANs; what can't be used is said in problems. */
    public static BitSet vlans(String spec, List<String> problems) {
        var set = new BitSet();
        if (spec == null || spec.isBlank()) return set;
        for (var part : spec.split(",")) {
            var p = part.trim();
            int dash = p.indexOf('-');
            try {
                int lo = Integer.parseInt((dash < 0 ? p : p.substring(0, dash)).trim());
                int hi = dash < 0 ? lo : Integer.parseInt(p.substring(dash + 1).trim());
                if (lo < 1 || hi > VLAN_MAX || lo > hi) throw new NumberFormatException();
                set.set(lo, hi + 1);
            } catch (NumberFormatException e) {
                problems.add("VLANs \"" + p + "\" isn't n or lo-hi within 1-" + VLAN_MAX + ": left out");
            }
        }
        return set;
    }

    public void addPort(Port p) {
        ports.putIfAbsent(p, new Stats());
    }

    public void removePort(Port p) {
        ports.remove(p);
        peers.remove(p);
        standby.remove(p);
        for (var m : peers.values()) m.remove(p);
        macs.values().removeIf(l -> l.port() == p);
        recheck();
    }

    /** Block a heard block b's beacon: they're on the same CC network. */
    public void heard(Port a, Port b) {
        if (a == b || !ports.containsKey(a) || !ports.containsKey(b)) return;
        var m = peers.computeIfAbsent(a, k -> new IdentityHashMap<>());
        boolean fresh = m.getOrDefault(b, 0L) <= clock.getAsLong();
        m.put(b, clock.getAsLong() + PEER_TTL);
        if (fresh) recheck();
    }

    /** Which blocks stand by: those sharing a network with one that ranks lower (or as low, and came first). */
    private void recheck() {
        var order = new ArrayList<>(ports.keySet());
        for (int i = 0; i < order.size(); i++) {
            var p = order.get(i);
            Port first = null;
            for (int j = 0; j < order.size(); j++) {
                var q = order.get(j);
                if (q == p || !together(p, q)) continue;
                if (q.rank() < p.rank() || q.rank() == p.rank() && j < i) {
                    if (first == null || q.rank() < first.rank()) first = q;
                }
            }
            var was = standby.get(p);
            if (first == was) continue;
            if (first == null) standby.remove(p);
            else standby.put(p, first);
            if (first != null) macs.values().removeIf(l -> l.port() == p);
            p.standby(first);
        }
    }

    /** The block p stands by for, or null if it works. */
    public Port standingBy(Port p) {
        return standby.get(p);
    }

    private boolean together(Port a, Port b) {
        long now = clock.getAsLong();
        var x = peers.get(a);
        var y = peers.get(b);
        return a == b || x != null && x.getOrDefault(b, 0L) > now || y != null && y.getOrDefault(a, 0L) > now;
    }

    /** One block on each CC network, but none on from's. */
    private List<Port> onePerNetwork(Port from) {
        var out = new ArrayList<Port>();
        for (var p : ports.keySet()) {
            if (standby.containsKey(p) || from != null && together(p, from)) continue;
            boolean covered = false;
            for (var q : out) covered |= together(p, q);
            if (!covered) out.add(p);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From CC
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A modem message heard by a block, on the gateway's channel or a VLAN's. The same one heard by two blocks (one
     * network) counts once.
     */
    public void fromCc(Port port, int channel, Object payload) {
        var s = settings;
        int tag = channel - s.channel();
        if (tag < 0 || tag > VLAN_MAX) return;
        if (!(payload instanceof Map<?, ?> f) || seen(payload)) return;
        if (!(f.get("ccnet") instanceof Number n) || n.doubleValue() != 1) return;
        if (!(f.get("src") instanceof String src) || !(f.get("dst") instanceof String dst)) return;
        var stats = ports.get(port);
        if (stats == null || standby.containsKey(port) || src.equals(s.mac())) return;
        int vlan = tag == 0 ? port.nativeVlan() : tag == port.nativeVlan() ? 0 : tag;
        if (vlan == 0 || !vlans.get(vlan) || !port.carries(vlan)) {
            stats.filtered++;
            return;
        }
        stats.in++;
        stats.vlans.set(vlan);
        macs.put(vlan + " " + src, new Learned(port, clock.getAsLong() + MAC_TTL));
        boolean mine = vlan == 1 && dst.equals(s.mac());
        if (!mine) relay(port, vlan, f, dst);
        if (vlan != 1 || !mine && !dst.equals(BROADCAST)) return;
        Object type = f.get("type");
        if ("arp".equals(type)) arpIn(f.get("data"));
        else if ("ip".equals(type)) ipIn(mine, f.get("data"));
    }

    /** A frame from one block's network on to the others: where its MAC was heard, else to every other network. */
    private void relay(Port from, int vlan, Map<?, ?> frame, String dst) {
        @SuppressWarnings("unchecked") var f = (Map<String, Object>) frame;
        var to = dst.equals(BROADCAST) ? null : learned(vlan, dst);
        if (to != null) {
            if (!together(to, from)) out(to, vlan, f, true);
            return;
        }
        for (var p : onePerNetwork(from)) out(p, vlan, f, true);
    }

    private Port learned(int vlan, String mac) {
        var l = macs.get(vlan + " " + mac);
        return l == null || l.expires() <= clock.getAsLong() || !ports.containsKey(l.port()) || standby.containsKey(l.port())
            ? null : l.port();
    }

    /** A frame on VLAN vlan out of block p: untagged if it's p's native VLAN, else tagged. */
    private void out(Port p, int vlan, Map<String, Object> f, boolean relay) {
        var stats = ports.get(p);
        if (stats == null) return;
        if (!vlans.get(vlan) || !p.carries(vlan)) {
            stats.filtered++;
            return;
        }
        stats.out++;
        if (relay) {
            stats.relayed++;
            relayed++;
        }
        p.send(settings.channel() + (vlan == p.nativeVlan() ? 0 : vlan), f);
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

    /** ARP retries and expiry, and the blocks' beacons; call it a few times a second. */
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
        macs.values().removeIf(l -> l.expires() <= now);
        boolean lost = false;
        for (var m : peers.values()) lost |= m.values().removeIf(t -> t <= now);
        if (lost) recheck();
        if (now >= nextHello) {
            nextHello = now + HELLO_EVERY;
            for (var p : ports.keySet()) p.hello();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void send(String dstMac, String type, Object data) {
        var s = settings;
        var f = table("ccnet", 1, "dst", dstMac, "src", s.mac(), "type", type, "data", data);
        var port = dstMac.equals(BROADCAST) ? null : learned(1, dstMac);   // (the gateway is on VLAN 1, the exchange)
        if (port != null) {
            out(port, 1, f, false);
        } else {
            for (var p : onePerNetwork(null)) out(p, 1, f, false);
        }
    }

    private static Map<String, Object> table(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** "1-4094", "10, 20, 100-199", "none": a set of VLANs as people read it. */
    public static String says(BitSet vlans) {
        if (vlans.isEmpty()) return "none";
        var parts = new ArrayList<String>();
        for (int lo = vlans.nextSetBit(0); lo >= 0; ) {
            int hi = vlans.nextClearBit(lo) - 1;
            parts.add(lo == hi ? String.valueOf(lo) : lo + "-" + hi);
            lo = vlans.nextSetBit(hi + 1);
        }
        return String.join(", ", parts);
    }

    /** How many CC networks the blocks are on. */
    public int networks() {
        return onePerNetwork(null).size();
    }

    /** What /ccnix shows. */
    public List<String> status() {
        var s = settings;
        long now = clock.getAsLong();
        var out = new ArrayList<String>();
        out.add("address " + s.address() + ", MAC \"" + s.mac() + "\", channel " + s.channel() + ", " + ports.size()
            + (ports.size() == 1 ? " block" : " blocks") + " (" + standby.size() + " standing by)"
            + "; VLANs carried: " + says(vlans));
        for (var r : s.routes()) out.add("route " + r);
        arp.forEach((ip, n) -> out.add("neighbour " + Ipv4.ipString(ip) + " is " + n.mac() + " ("
            + Math.max(0, (n.expires() - now) / 1000) + " s)"));
        waiting.forEach((ip, w) -> out.add("asking ARP for " + Ipv4.ipString(ip) + ": " + w.packets.size() + " waiting"));
        out.add("packets: " + toTun + " from CC, " + toCc + " into CC, " + dropped + " dropped; " + arpAsked
            + " ARP requests; " + relayed + " frames passed between blocks");
        return out;
    }

    /** One block's part, for its right-click. */
    public List<String> portStatus(Port p) {
        var stats = ports.get(p);
        if (stats == null) return List.of("this block isn't on the gateway yet");
        long now = clock.getAsLong();
        var behind = new TreeSet<String>();
        macs.forEach((key, l) -> {
            if (l.port() != p || l.expires() <= now) return;
            int sp = key.indexOf(' ');
            int vlan = Integer.parseInt(key.substring(0, sp));
            behind.add(key.substring(sp + 1) + (vlan != 1 ? " (VLAN " + vlan + ")" : ""));
        });
        int sharing = 0;
        for (var q : ports.keySet()) if (q != p && together(p, q)) sharing++;
        var out = new ArrayList<String>();
        if (standby.containsKey(p)) {
            out.add("standing by: another gateway block is on this cable network, and only one works on each (this one"
                + " takes over if that one goes)");
            return out;
        }
        out.add("frames: " + stats.in + " in, " + stats.out + " out (" + stats.relayed + " from other blocks)"
            + (stats.filtered > 0 ? ", " + stats.filtered + " filtered" : ""));
        out.add(behind.size() + (behind.size() == 1 ? " MAC" : " MACs") + " heard here"
            + (behind.isEmpty() ? "" : ": " + String.join(", ", behind.stream().limit(8).toList())
            + (behind.size() > 8 ? ", ..." : "")));
        out.add("VLANs seen here: " + says(stats.vlans));
        out.add(sharing == 0 ? "the only block on its network" : sharing
            + (sharing == 1 ? " other block stands by" : " other blocks stand by") + " on its network");
        return out;
    }
}
