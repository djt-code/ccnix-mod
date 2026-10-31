package ccnix.gateway;

import com.mojang.logging.LogUtils;
import ccnix.Config;
import ccnix.gateway.core.Gateway;
import ccnix.gateway.core.Ipv4;
import ccnix.gateway.core.Nat;
import ccnix.gateway.core.NatConfig;
import ccnix.gateway.core.OpRoutes;
import ccnix.gateway.core.Settings;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The running gateway: one per server, whatever number of gateway blocks there are (each is a port). Packets come
 * from computer threads (modems transmit there) and from the other side; the gateway runs on a thread of its own.
 * The other side is the mode's (gateway.mode): in NAT mode {@link Nat} with the server's own sockets, in TUN mode the
 * helper holding the TUN device, which starts with the first block, so a world without one runs none.
 */
public final class Service {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Set<Gateway.Port> PORTS = ConcurrentHashMap.newKeySet();
    private static volatile int channel = -1;
    private static volatile ScheduledExecutorService exec;
    private static volatile Gateway gateway;
    private static volatile TunHelper tun;
    private static volatile Nat nat;
    private static volatile String mode = "nat";

    private Service() {
    }

    public static synchronized void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "ccnix-gateway");
            t.setDaemon(true);
            return t;
        });
        var s = settings();
        channel = s.channel();
        mode = Config.MODE.get();
        long base = System.nanoTime();
        LongSupplier clock = () -> (System.nanoTime() - base) / 1_000_000;
        if (mode.equals("nat")) {
            var n = new Nat(s, natConfig(), new JavaOutside(task -> run(g -> task.run())), clock);
            nat = n;
            gateway = new Gateway(s, n, clock);
            run(g -> n.attach(packet -> run(x -> x.fromTun(packet))));
            exec.scheduleAtFixedRate(() -> run(g -> n.tick()), 250, 250, TimeUnit.MILLISECONDS);
        } else {
            gateway = new Gateway(s, packet -> {
                var t = tun;
                if (t != null) t.write(packet);
            }, clock);
        }
        var carried = vlans();
        run(g -> g.configureVlans(carried));
        for (var p : PORTS) run(g -> g.addPort(p));
        exec.scheduleAtFixedRate(() -> run(Gateway::tick), 250, 250, TimeUnit.MILLISECONDS);
        if (!PORTS.isEmpty() && mode.equals("tun")) startTun();
        LOG.info("ccnix: the gateway runs in {} mode", mode);
    }

    public static synchronized void stop() {
        if (tun != null) tun.stop();
        tun = null;
        var n = nat;
        var e = exec;
        if (n != null && e != null) {
            try {
                e.submit(n::stop).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // (stopping anyway)
            }
        }
        nat = null;
        if (exec != null) exec.shutdownNow();
        exec = null;
        gateway = null;
        channel = -1;
    }

    /** The config changed. A new mode starts the gateway again; else it and NAT take the new settings. */
    public static synchronized void reconfigure() {
        if (exec == null) return;
        if (!Config.MODE.get().equals(mode)) {
            stop();
            start();
            return;
        }
        var s = settings();
        channel = s.channel();
        run(g -> g.configure(s));
        var carried = vlans();
        run(g -> g.configureVlans(carried));
        var n = nat;
        if (n != null) {
            var c = natConfig();
            run(g -> n.configure(s, c));
        }
        if (tun != null && !tun.command().equals(TunHelper.command(Config.HELPER.get(), Config.DEVICE.get()))) {
            tun.stop();
            tun = null;
            startTun();
        }
    }

    private static void startTun() {
        if (tun != null) return;
        tun = new TunHelper(Config.HELPER.get(), Config.DEVICE.get(), packet -> run(x -> x.fromTun(packet)));
        tun.start();
    }

    /** The running mode: "nat" or "tun". */
    public static String mode() {
        return mode;
    }

    /** NAT mode's settings from the config (what can't be used is logged and left out). */
    public static NatConfig natConfig() {
        var problems = new ArrayList<String>();
        var c = NatConfig.parse(Config.NETWORKS.get(), Config.DENY.get(), Config.ALLOW.get(), Config.MAX_PER_COMPUTER.get(),
            Config.MAX_TOTAL.get(), Config.TCP_IDLE.get(), Config.UDP_IDLE.get(), dns(Config.DNS_RELAY.get()),
            Config.FORWARDS.get(), problems);
        for (var p : problems) LOG.warn("ccnix config: {}", p);
        return c;
    }

    /** What nat.dnsRelay says answers at the gateway's address on port 53. */
    static NatConfig.Dns dns(String setting) {
        var v = setting.trim();
        if (v.isEmpty()) return NatConfig.Dns.OFF;
        if (!v.equals("system")) {
            long ip = Ipv4.parseIp(v);
            if (ip < 0) {
                LOG.warn("ccnix config: nat.dnsRelay \"{}\" isn't system, an address or empty: off", v);
                return NatConfig.Dns.OFF;
            }
            return new NatConfig.Dns(NatConfig.Dns.Kind.RELAY, ip, null);
        }
        var conf = Path.of("/etc/resolv.conf");
        if (Files.isReadable(conf)) {
            try {
                for (var line : Files.readAllLines(conf)) {
                    var w = line.trim().split("\\s+");
                    long ip = w.length >= 2 && w[0].equals("nameserver") ? Ipv4.parseIp(w[1]) : -1;
                    if (ip >= 0) return new NatConfig.Dns(NatConfig.Dns.Kind.RELAY, ip, null);
                }
            } catch (IOException e) {
                LOG.warn("ccnix: can't read {}: {}", conf, e.toString());
            }
            LOG.warn("ccnix: no IPv4 nameserver in {}: the DNS relay is off", conf);
            return NatConfig.Dns.OFF;
        }
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            return new NatConfig.Dns(NatConfig.Dns.Kind.ANSWER, 0, JavaOutside.SYSTEM_RESOLVER);
        }
        return NatConfig.Dns.OFF;
    }

    /** The VLANs the config says the blocks carry. */
    static java.util.BitSet vlans() {
        var problems = new ArrayList<String>();
        var v = Gateway.vlans(Config.VLANS.get(), problems);
        for (var p : problems) LOG.warn("ccnix config: gateway.vlans: {}", p);
        return v;
    }

    /** The settings the config gives (what can't be used is logged and left out). */
    public static Settings settings() {
        var problems = new ArrayList<String>();
        var s = Settings.parse(Config.ADDRESS.get(), Config.CHANNEL.get(), Config.MAC.get(), Config.ROUTES.get(), problems);
        for (var p : problems) LOG.warn("ccnix config: {}", p);
        return s;
    }

    /**
     * The routes the server has into the TUN device, in any table, as {@code ip} shows them (no privileges needed).
     * Throws if they can't be read: no {@code ip} (not Linux), no such device.
     */
    public static List<OpRoutes.HostRoute> hostRoutes() throws IOException {
        var device = Config.DEVICE.get();
        var p = new ProcessBuilder("ip", "-4", "route", "show", "table", "all", "dev", device).redirectErrorStream(true).start();
        p.getOutputStream().close();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroy();
                throw new IOException("ip took too long");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (p.exitValue() != 0) throw new IOException(out.isBlank() ? "ip failed" : out.trim());
        return OpRoutes.parseIpRoutes(out);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From the blocks
    // ---------------------------------------------------------------------------------------------------------------

    static void addPort(Gateway.Port p) {
        PORTS.add(p);
        synchronized (Service.class) {
            if (exec == null) return;
            run(g -> g.addPort(p));
            if (mode.equals("tun")) startTun();
        }
    }

    static void removePort(Gateway.Port p) {
        PORTS.remove(p);
        run(g -> g.removePort(p));
    }

    /** Block a heard block b's beacon. */
    static void heard(Gateway.Port a, Gateway.Port b) {
        run(g -> g.heard(a, b));
    }

    /** The channel the gateway listens on (-1: not running): a block passes on only what's on it and its VLANs'. */
    static int channel() {
        return channel;
    }

    static void receive(Gateway.Port p, int channel, Object payload) {
        run(g -> g.fromCc(p, channel, payload));
    }

    /** What /gateway shows. */
    public static List<String> status() {
        ScheduledExecutorService e;
        Gateway g;
        TunHelper t;
        synchronized (Service.class) {
            e = exec;
            g = gateway;
            t = tun;
        }
        if (e == null || g == null) return List.of("not running");
        var out = new ArrayList<String>();
        out.add("mode: " + mode);
        var n = nat;
        try {
            out.addAll(e.submit(g::status).get(2, TimeUnit.SECONDS));
            if (n != null) out.addAll(e.submit(n::status).get(2, TimeUnit.SECONDS));
        } catch (Exception ex) {
            out.add("the gateway didn't answer: " + ex);
        }
        if (mode.equals("tun")) out.add("TUN helper: " + (t == null ? "not started (no gateway block loaded)" : t.state()));
        return out;
    }

    /** One block's part, for its right-click: the gateway, then the block. */
    static List<String> portStatus(Gateway.Port p) {
        ScheduledExecutorService e;
        Gateway g;
        synchronized (Service.class) {
            e = exec;
            g = gateway;
        }
        if (e == null || g == null) return List.of("the gateway isn't running");
        var s = settings();
        var out = new ArrayList<String>();
        out.add("on the exchange: the gateway is " + s.address() + " (" + mode + " mode), channel " + s.channel()
            + "; VLANs carried: " + Gateway.says(vlans()));
        try {
            out.addAll(e.submit(() -> g.portStatus(p)).get(2, TimeUnit.SECONDS));
        } catch (Exception ex) {
            out.add("the gateway didn't answer: " + ex);
        }
        return out;
    }

    private static void run(Consumer<Gateway> task) {
        var e = exec;
        var g = gateway;
        if (e == null || g == null) return;
        try {
            e.execute(() -> {
                try {
                    task.accept(g);
                } catch (RuntimeException ex) {
                    LOG.error("ccnix: ", ex);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // stopping
        }
    }
}
