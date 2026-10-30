package ccnix.gateway;

import com.mojang.logging.LogUtils;
import ccnix.Config;
import ccnix.gateway.core.Gateway;
import ccnix.gateway.core.Settings;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The running gateway: one per server, whatever number of gateway blocks there are (each is a port). Packets come
 * from computer threads (modems transmit there) and the helper's thread; the gateway runs on a thread of its own.
 * The helper starts with the first block, so a world without one runs none.
 */
public final class Service {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Set<Gateway.Port> PORTS = ConcurrentHashMap.newKeySet();
    private static volatile int channel = -1;
    private static volatile ScheduledExecutorService exec;
    private static volatile Gateway gateway;
    private static volatile TunHelper tun;

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
        long base = System.nanoTime();
        gateway = new Gateway(s, packet -> {
            var t = tun;
            if (t != null) t.write(packet);
        }, () -> (System.nanoTime() - base) / 1_000_000);
        for (var p : PORTS) run(g -> g.addPort(p));
        exec.scheduleAtFixedRate(() -> run(Gateway::tick), 250, 250, TimeUnit.MILLISECONDS);
        if (!PORTS.isEmpty()) startTun();
    }

    public static synchronized void stop() {
        if (tun != null) tun.stop();
        tun = null;
        if (exec != null) exec.shutdownNow();
        exec = null;
        gateway = null;
        channel = -1;
    }

    /** The config changed. */
    public static synchronized void reconfigure() {
        if (exec == null) return;
        var s = settings();
        channel = s.channel();
        run(g -> g.configure(s));
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

    private static Settings settings() {
        var problems = new ArrayList<String>();
        var s = Settings.parse(Config.ADDRESS.get(), Config.CHANNEL.get(), Config.MAC.get(), Config.ROUTES.get(), problems);
        for (var p : problems) LOG.warn("ccnix config: {}", p);
        return s;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From the blocks
    // ---------------------------------------------------------------------------------------------------------------

    static void addPort(Gateway.Port p) {
        PORTS.add(p);
        synchronized (Service.class) {
            if (exec == null) return;
            run(g -> g.addPort(p));
            startTun();
        }
    }

    static void removePort(Gateway.Port p) {
        PORTS.remove(p);
        run(g -> g.removePort(p));
    }

    /** The channel the gateway listens on (-1: not running): a block passes on only what's on it. */
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
        try {
            out.addAll(e.submit(g::status).get(2, TimeUnit.SECONDS));
        } catch (Exception ex) {
            out.add("the gateway didn't answer: " + ex);
        }
        out.add("TUN helper: " + (t == null ? "not started (no gateway block loaded)" : t.state()));
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
