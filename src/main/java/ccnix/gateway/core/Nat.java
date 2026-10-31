package ccnix.gateway.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * NAT mode (ADR-0002): the gateway's other side as a userspace kernel, in place of a TUN device, needing no
 * privileges. It's transport only: CC's TCP connections and UDP flows to the outside are carried as the server's own
 * sockets ({@link Outside}), their bytes passed through unread. Besides that it answers ping at the gateway's address,
 * passes packets between CC networks back to the gateway (as the server's kernel does in TUN mode), says
 * "time exceeded" for a packet whose TTL runs out there (it's a hop, as a router is), "administratively
 * prohibited" for an echo that would go on to the outside (it can't send ICMP there) and for a datagram the policy
 * refuses, relays port 53 at the gateway's address to the
 * server's resolver (or on Windows answers it: the one exception), and carries port forwards in from outside.
 * <p>
 * The outbound policy and limits ({@link NatConfig}) apply to CC computers' connections through it, nothing else.
 * <p>
 * Not thread-safe: the gateway's thread only (Outside calls back there).
 */
public final class Nat implements Gateway.Tun {
    static final int TTL = 64;

    private final Outside outside;
    private final LongSupplier clock;
    private final Random random = new Random();
    private Consumer<byte[]> toCc = p -> { };
    private Settings gw;
    private NatConfig cfg;
    private final Map<String, NatTcp> tcbs = new LinkedHashMap<>();
    private final Map<String, Flow> flows = new LinkedHashMap<>();
    private final Map<String, Outside.Closer> tcpForwards = new HashMap<>();
    private final Map<String, Outside.Udp> udpForwards = new HashMap<>();
    private final Map<String, String> forwardProblems = new LinkedHashMap<>();
    private int nextId;

    /** Carried out (connections and flows opened), refused by the policy or limits, dropped (unreadable, not CC's). */
    public long opened, refused, dropped;

    /** A UDP flow: a CC host's port and the address it sees (myIp:myPort), and the socket that carries it. */
    private static final class Flow {
        final long ccIp, myIp;
        final int ccPort, myPort;
        final Outside.Udp sock;
        final boolean shared;                           // (a forward's socket: not ours to close)
        long lastUsed;
        long realIp = -1;                               // (a forward's: the peer's real address, if shown otherwise)

        /** To the outside: a forward's socket sends to its peer, an outbound flow's is connected. */
        void out(byte[] b) {
            sock.send(realIp >= 0 ? realIp : myIp, myPort, b);
        }

        Flow(long ccIp, int ccPort, long myIp, int myPort, Outside.Udp sock, boolean shared, long now) {
            this.ccIp = ccIp;
            this.ccPort = ccPort;
            this.myIp = myIp;
            this.myPort = myPort;
            this.sock = sock;
            this.shared = shared;
            this.lastUsed = now;
        }
    }

    /** clock: milliseconds. */
    public Nat(Settings gw, NatConfig cfg, Outside outside, LongSupplier clock) {
        this.gw = gw;
        this.cfg = cfg;
        this.outside = outside;
        this.clock = clock;
    }

    /** Where packets for CC go: the gateway's fromTun. */
    public void attach(Consumer<byte[]> toCc) {
        this.toCc = toCc;
        configure(gw, cfg);
    }

    /** New settings: forwards opened and closed to match; open connections carry on. */
    public void configure(Settings gw, NatConfig cfg) {
        this.gw = gw;
        this.cfg = cfg;
        var want = new HashMap<String, NatConfig.Forward>();
        for (var f : cfg.forwards()) want.put(f.key() + " " + f, f);
        tcpForwards.entrySet().removeIf(e -> {
            if (want.containsKey(e.getKey())) return false;
            e.getValue().close();
            return true;
        });
        udpForwards.entrySet().removeIf(e -> {
            if (want.containsKey(e.getKey())) return false;
            e.getValue().close();
            return true;
        });
        forwardProblems.clear();
        for (var e : want.entrySet()) {
            var f = e.getValue();
            if (tcpForwards.containsKey(e.getKey()) || udpForwards.containsKey(e.getKey())) continue;
            try {
                if (f.udp()) udpForwards.put(e.getKey(), outside.listenUdp(f.port(), (ip, port, b) -> forwardDatagram(f, ip, port, b)));
                else tcpForwards.put(e.getKey(), outside.listenTcp(f.port(), (ip, port, conn) -> forwardAccept(f, ip, port, conn)));
            } catch (IOException ex) {
                forwardProblems.put(f.key(), ex.getMessage());
            }
        }
    }

    /** Close everything: the mode changed, or the server is stopping. */
    public void stop() {
        for (var c : List.copyOf(tcbs.values())) c.abort("stopping");
        for (var f : List.copyOf(flows.values())) closeFlow(f);
        tcpForwards.values().forEach(Outside.Closer::close);
        udpForwards.values().forEach(Outside.Udp::close);
        tcpForwards.clear();
        udpForwards.clear();
    }

    long now() {
        return clock.getAsLong();
    }

    long random32() {
        return random.nextLong() & 0xFFFFFFFFL;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From CC (the gateway's side)
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public void write(byte[] p) {
        var m = Ipv4.toCc(p);
        if (m == null || !(m.get("data") instanceof Map<?, ?> raw)) {
            dropped++;
            return;
        }
        var d = (Map<String, Object>) raw;
        long src = (Long) m.get("src"), dst = (Long) m.get("dst");
        int ttl = (Integer) m.get("ttl");
        var proto = (String) m.get("proto");
        if (dst == Ipv4.ALL || dst == gw.broadcast()) return;
        if (existing(src, dst, proto, d)) return;
        if (dst == gw.ip()) {
            toGateway(src, proto, d);
        } else if (gw.onNetwork(dst) || cfg.cc(dst)) {
            hairpin(p, m, ttl);
        } else if (!gw.onNetwork(src) && !cfg.cc(src)) {
            dropped++;
        } else if (ttl <= 1) {
            icmpError(m, "timeexceeded", "ttl");         // (the gateway is a hop, as a router is: out is past it)
        } else if ("tcp".equals(proto)) {
            tcpOut(src, dst, d);
        } else if ("udp".equals(proto)) {
            udpOut(src, dst, d);
        } else if ("echo".equals(d.get("type"))) {
            icmpError(m, "unreachable", "prohibited");  // (no ICMP to the outside: say where it stops, and why)
        }
    }

    /** A segment or datagram for a connection or flow there is already: carried. */
    private boolean existing(long src, long dst, String proto, Map<String, Object> d) {
        if (!(d.get("sport") instanceof Number sp) || !(d.get("dport") instanceof Number dp)) return false;
        var k = key(src, sp.intValue(), dst, dp.intValue());
        if ("tcp".equals(proto)) {
            var c = tcbs.get(k);
            if (c == null) return false;
            c.in(d);
            return true;
        }
        if ("udp".equals(proto)) {
            var f = flows.get(k);
            var data = Ipv4.bytes(d.get("data"));
            if (f == null || data == null) return false;
            f.lastUsed = now();
            f.out(data);
            return true;
        }
        return false;
    }

    /** Between CC networks: back to the gateway, one less TTL, as a router does. */
    private void hairpin(byte[] p, Map<String, Object> m, int ttl) {
        if (ttl <= 1) {
            icmpError(m, "timeexceeded", "ttl");
            return;
        }
        var copy = Arrays.copyOf(p, p.length);
        copy[8]--;
        Ipv4.put16(copy, 10, 0);
        Ipv4.put16(copy, 10, Ipv4.checksum(copy, 0, (copy[0] & 15) * 4, 0));
        toCc.accept(copy);
    }

    /** For the gateway's own address: ping, the DNS relay; anything else isn't there. */
    private void toGateway(long src, String proto, Map<String, Object> d) {
        int dport = d.get("dport") instanceof Number n ? n.intValue() : -1;
        if ("icmp".equals(proto)) {
            if ("echo".equals(d.get("type"))) {
                Map<String, Object> r = new HashMap<>();
                r.put("type", "reply");
                r.put("id", d.get("id"));
                r.put("seq", d.get("seq"));
                r.put("data", d.get("data"));
                send(gw.ip(), src, "icmp", r);
            }
        } else if ("udp".equals(proto)) {
            if (dport == 53 && cfg.dns().kind() == NatConfig.Dns.Kind.RELAY) udpOut(src, gw.ip(), d);
            else if (dport == 53 && cfg.dns().kind() == NatConfig.Dns.Kind.ANSWER) dnsAnswer(src, d);
            else portUnreachable(src, gw.ip(), d);
        } else if ("tcp".equals(proto)) {
            if (dport == 53 && cfg.dns().kind() == NatConfig.Dns.Kind.RELAY) tcpOut(src, gw.ip(), d);
            else if (!Boolean.TRUE.equals(d.get("rst"))) resetFor(src, gw.ip(), d);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // TCP
    // ---------------------------------------------------------------------------------------------------------------

    private static String key(long ccIp, int ccPort, long myIp, int myPort) {
        return ccIp + ":" + ccPort + ">" + myIp + ":" + myPort;
    }

    private void tcpOut(long src, long dst, Map<String, Object> d) {
        int sport = ((Number) d.get("sport")).intValue(), dport = ((Number) d.get("dport")).intValue();
        var c = tcbs.get(key(src, sport, dst, dport));
        if (c != null) {
            c.in(d);
            return;
        }
        boolean syn = Boolean.TRUE.equals(d.get("syn")), rst = Boolean.TRUE.equals(d.get("rst"));
        if (!syn || d.containsKey("ack") || rst) {
            if (!rst) resetFor(src, dst, d);
            return;
        }
        boolean relay = dst == gw.ip();                 // (the DNS relay: to the resolver, which the admin chose)
        long to = relay ? cfg.dns().resolver() : dst;
        if (!admit(src, relay, to, dport)) {
            resetFor(src, dst, d);
            return;
        }
        var tcb = new NatTcp(this, src, sport, dst, dport, NatTcp.State.CONNECTING);
        tcb.rcvNxt = NatTcp.add(Ipv4.u32(d.get("seq")), 1);
        if (d.get("wnd") instanceof Number n) tcb.sndWnd = n.intValue();
        tcbs.put(key(src, sport, dst, dport), tcb);
        opened++;
        tcb.conn = outside.connect(to, dport, events(tcb));
    }

    private Outside.TcpEvents events(NatTcp c) {
        return new Outside.TcpEvents() {
            @Override
            public void connected() {
                c.connected();
            }

            @Override
            public void failed(boolean refused) {
                c.failed(refused);
            }

            @Override
            public void data(byte[] b) {
                c.data(b);
            }

            @Override
            public void eof() {
                c.close();
            }

            @Override
            public void broken() {
                c.abort("broken");
            }
        };
    }

    void closed(NatTcp c) {
        tcbs.remove(key(c.ccIp, c.ccPort, c.myIp, c.myPort));
    }

    /** A reset for a segment with no connection (RFC 9293 3.10.7.1), from where it was going. */
    void resetFor(long ccIp, long myIp, Map<String, Object> d) {
        if (Boolean.TRUE.equals(d.get("rst"))) return;
        Map<String, Object> seg = new HashMap<>();
        seg.put("sport", d.get("dport"));
        seg.put("dport", d.get("sport"));
        seg.put("rst", true);
        seg.put("wnd", 0);
        seg.put("data", new byte[0]);
        if (d.containsKey("ack")) {
            seg.put("seq", d.get("ack"));
        } else {
            var data = Ipv4.bytes(d.get("data"));
            seg.put("seq", 0L);
            seg.put("ack", NatTcp.add(Ipv4.u32(d.get("seq")), (data == null ? 0 : data.length)
                + (Boolean.TRUE.equals(d.get("syn")) ? 1 : 0) + (Boolean.TRUE.equals(d.get("fin")) ? 1 : 0)));
        }
        send(myIp, ccIp, "tcp", seg);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // UDP
    // ---------------------------------------------------------------------------------------------------------------

    private void udpOut(long src, long dst, Map<String, Object> d) {
        int sport = ((Number) d.get("sport")).intValue(), dport = ((Number) d.get("dport")).intValue();
        var data = Ipv4.bytes(d.get("data"));
        if (data == null) return;
        var k = key(src, sport, dst, dport);
        var f = flows.get(k);
        if (f != null) {
            f.lastUsed = now();
            f.out(data);
            return;
        }
        {
            boolean relay = dst == gw.ip();
            long to = relay ? cfg.dns().resolver() : dst;
            if (!admit(src, relay, to, dport)) {
                udpError(src, dst, d, "prohibited");
                return;
            }
            Outside.Udp sock;
            try {
                var holder = new Flow[1];
                sock = outside.udp(to, dport, (ip, port, b) -> {
                    if (holder[0] != null) fromFlow(holder[0], b);
                });
                f = new Flow(src, sport, dst, dport, sock, false, now());
                holder[0] = f;
            } catch (IOException e) {
                portUnreachable(src, dst, d);
                return;
            }
            flows.put(k, f);
            opened++;
        }
        f.out(data);
    }

    private void fromFlow(Flow f, byte[] b) {
        if (flows.get(key(f.ccIp, f.ccPort, f.myIp, f.myPort)) != f) return;
        f.lastUsed = now();
        sendUdp(f.myIp, f.myPort, f.ccIp, f.ccPort, b);
    }

    private void closeFlow(Flow f) {
        flows.remove(key(f.ccIp, f.ccPort, f.myIp, f.myPort));
        if (!f.shared) f.sock.close();
    }

    private void sendUdp(long srcIp, int sport, long dstIp, int dport, byte[] b) {
        Map<String, Object> u = new HashMap<>();
        u.put("sport", sport);
        u.put("dport", dport);
        u.put("data", b);
        send(srcIp, dstIp, "udp", u);
    }

    private void portUnreachable(long ccIp, long myIp, Map<String, Object> d) {
        udpError(ccIp, myIp, d, "port");
    }

    /** "Destination unreachable", code ("port", "prohibited"), about a datagram from ccIp to myIp. */
    private void udpError(long ccIp, long myIp, Map<String, Object> d, String code) {
        Map<String, Object> m = new HashMap<>();
        m.put("src", ccIp);
        m.put("dst", myIp);
        m.put("proto", "udp");
        m.put("data", d);
        icmpError(m, "unreachable", code);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // DNS on Windows: answered (the one exception to transport-only)
    // ---------------------------------------------------------------------------------------------------------------

    private void dnsAnswer(long src, Map<String, Object> d) {
        int sport = ((Number) d.get("sport")).intValue();
        var data = Ipv4.bytes(d.get("data"));
        var q = data == null ? null : DnsAnswer.question(data);
        if (q == null) return;
        var resolver = cfg.dns().answerer();
        outside.blocking(() -> DnsAnswer.answer(q, resolver), reply -> sendUdp(gw.ip(), 53, src, sport, reply));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Port forwards: in from outside
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Who a forwarded connection is from, as the CC host sees it: the client's real address, except one on the
     * server's own loopback (an admin trying it from there), which a CC host would answer itself: the gateway's.
     */
    private long shown(long ip) {
        return (ip >>> 24) == 127 ? gw.ip() : ip;
    }

    private Outside.TcpEvents forwardAccept(NatConfig.Forward f, long ip, int port, Outside.Tcp conn) {
        if (tcbs.size() + flows.size() >= cfg.maxTotal()) {
            refused++;
            conn.close();
            return null;
        }
        var c = new NatTcp(this, f.target(), f.targetPort(), shown(ip), port, NatTcp.State.SYN_SENT);
        c.conn = conn;
        tcbs.put(key(c.ccIp, c.ccPort, c.myIp, c.myPort), c);
        opened++;
        c.open();
        return events(c);
    }

    private void forwardDatagram(NatConfig.Forward f, long realIp, int port, byte[] b) {
        long ip = shown(realIp);
        var k = key(f.target(), f.targetPort(), ip, port);
        var flow = flows.get(k);
        if (flow == null) {
            if (tcbs.size() + flows.size() >= cfg.maxTotal()) {
                refused++;
                return;
            }
            flow = new Flow(f.target(), f.targetPort(), ip, port, udpForwards.get(f.key() + " " + f), true, now());
            flow.realIp = realIp;
            flows.put(k, flow);
            opened++;
        }
        flow.lastUsed = now();
        sendUdp(ip, port, f.target(), f.targetPort(), b);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Policy, limits, time
    // ---------------------------------------------------------------------------------------------------------------

    /** May src open a connection or flow to to:port? The relay's resolver is the admin's: not checked. */
    private boolean admit(long src, boolean relay, long to, int port) {
        if (!relay && !cfg.allowed(to, port)) {
            refused++;
            return false;
        }
        int mine = 0;
        for (var c : tcbs.values()) if (c.ccIp == src) mine++;
        for (var f : flows.values()) if (f.ccIp == src) mine++;
        if (mine >= cfg.maxPerComputer() || tcbs.size() + flows.size() >= cfg.maxTotal()) {
            refused++;
            return false;
        }
        return true;
    }

    /** Every 250 ms or so: TCP's timers, idle connections and flows. */
    public void tick() {
        long now = now();
        for (var c : List.copyOf(tcbs.values())) c.tick(now, cfg.tcpIdleMs());
        for (var f : List.copyOf(flows.values())) if (now - f.lastUsed >= cfg.udpIdleMs()) closeFlow(f);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Into CC
    // ---------------------------------------------------------------------------------------------------------------

    void send(long src, long dst, String proto, Map<String, Object> data) {
        Map<String, Object> m = new HashMap<>();
        m.put("src", src);
        m.put("dst", dst);
        m.put("ttl", TTL);
        m.put("id", nextId = (nextId + 1) & 0xFFFF);
        m.put("proto", proto);
        m.put("data", data);
        var p = Ipv4.fromCc(m);
        if (p != null) toCc.accept(p);
    }

    /** An ICMP error about packet m (a CC packet table), from the gateway to its sender. */
    @SuppressWarnings("unchecked")
    private void icmpError(Map<String, Object> m, String type, String code) {
        var d = m.get("data") instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.<String, Object>of();
        Map<String, Object> orig = new HashMap<>();
        orig.put("src", m.get("src"));
        orig.put("dst", m.get("dst"));
        orig.put("proto", m.get("proto"));
        for (var k : List.of("sport", "dport", "id", "seq")) if (d.get(k) != null) orig.put(k, d.get(k));
        Map<String, Object> e = new HashMap<>();
        e.put("type", type);
        e.put("code", code);
        e.put("orig", orig);
        send(gw.ip(), Ipv4.u32(m.get("src")), "icmp", e);
    }

    /** What /ccnix shows. */
    public List<String> status() {
        var out = new ArrayList<String>();
        out.add("NAT: " + tcbs.size() + " TCP connections, " + flows.size() + " UDP flows; " + opened + " opened, "
            + refused + " refused by policy or limits, " + dropped + " dropped");
        out.add("DNS at " + Ipv4.ipString(gw.ip()) + ":53: " + cfg.dns());
        for (var f : cfg.forwards()) {
            var problem = forwardProblems.get(f.key());
            out.add("forward " + f + (problem == null ? "" : " (not listening: " + problem + ")"));
        }
        return out;
    }
}
