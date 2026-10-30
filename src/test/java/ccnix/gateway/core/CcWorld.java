package ccnix.gateway.core;

import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.function.LuaFunction;
import org.squiddev.cobalt.lib.CoreLibraries;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CC computers running ccnix's lib/net in Cobalt (CC's Lua VM), cables between them, a gateway on one cable,
 * and a kernel behind its TUN device: as in game, but on a clock the test moves.
 * <p>
 * Frames cross between computers as CC carries modem messages: converted to Java (CobaltLuaMachine.toObject's
 * rules) once, and to a fresh Lua value for each computer that hears them (toValue's).
 * <p>
 * The kernel owns the gateway's address (it answers ping there), sends anything else in 10.0.0.0/8 back into the
 * TUN device (a server routing CC's networks to each other), and keeps the rest in {@link #outside} for the test
 * to look at, as if it went out to a real network.
 */
final class CcWorld {
    static final long GATEWAY = Ipv4.parseIp("10.0.0.1");
    static final long CC_NETS = Ipv4.parseIp("10.0.0.0"), CC_MASK = Settings.mask(8);

    final LuaState state = new LuaState();
    final LuaTable harness;
    final Gateway gateway;
    final List<byte[]> outside = new ArrayList<>();
    final List<byte[]> intoTun = new ArrayList<>();
    double now;

    private record Endpoint(int id, String modem) {
    }

    private record Sent(Gateway.Port port, int channel, Map<String, Object> frame) {
    }

    private final Map<String, List<Object>> wires = new HashMap<>();   // Endpoints and Ports
    private final Map<Object, String> wireOf = new HashMap<>();
    private final ArrayDeque<Sent> fromGateway = new ArrayDeque<>();
    private final ArrayDeque<byte[]> toKernel = new ArrayDeque<>();

    CcWorld(Settings settings) throws Exception {
        CoreLibraries.standardGlobals(state);
        var netPath = System.getProperty("ccnix.net");
        if (netPath == null || !Files.exists(Path.of(netPath))) {
            throw new IllegalStateException("no lib/net.lua (ccnix.net = " + netPath + "): is the ccnix/ submodule checked out?");
        }
        var net = LuaThread.runMain(state, load(Files.newInputStream(Path.of(netPath)), "@lib/net.lua")).first();
        state.globals().rawset("N", net);
        harness = (LuaTable) LuaThread.runMain(state, load(getClass().getResourceAsStream("harness.lua"), "@harness.lua"),
            net).first();
        state.globals().rawset("H", harness);
        gateway = new Gateway(settings, toKernel::add, () -> Math.round(now * 1000));
    }

    private LuaFunction load(InputStream in, String name) throws Exception {
        try (in) {
            return LoadState.load(state, in, name, state.globals());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Building it
    // ---------------------------------------------------------------------------------------------------------------

    /** Computer id's modem on a cable. */
    CcWorld wire(String cable, int id, String modem) {
        attach(cable, new Endpoint(id, modem));
        return this;
    }

    /** A gateway block on a cable. */
    Gateway.Port gatewayOn(String cable) {
        var port = new Gateway.Port() {
            @Override
            public void send(int channel, Map<String, Object> frame) {
                fromGateway.add(new Sent(this, channel, frame));
            }
        };
        attach(cable, port);
        gateway.addPort(port);
        return port;
    }

    private void attach(String cable, Object thing) {
        wires.computeIfAbsent(cable, x -> new ArrayList<>()).add(thing);
        wireOf.put(thing, cable);
    }

    /** Run Lua (with N, H and the globals earlier chunks set). */
    LuaValue lua(String code) {
        try {
            var fn = load(new ByteArrayInputStream(code.getBytes(StandardCharsets.ISO_8859_1)), "=test");
            return LuaThread.runMain(state, fn).first();
        } catch (Exception e) {
            throw new AssertionError("Lua: " + code + "\n  " + e, e);
        }
    }

    String luaString(String code) {
        var v = lua(code);
        return v.isNil() ? null : v.toString();
    }

    byte[] luaBytes(String code) {
        return lua(code).toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Another of ccnix's libraries (lib/name.lua, beside net.lua) as the global name; its require("net") is N. */
    CcWorld library(String name) throws Exception {
        var path = Path.of(System.getProperty("ccnix.net")).resolveSibling(name + ".lua");
        lua("require = function(n) assert(n == 'net', n) return N end");
        state.globals().rawset(name, LuaThread.runMain(state, load(Files.newInputStream(path), "@lib/" + name + ".lua")).first());
        return this;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Running it
    // ---------------------------------------------------------------------------------------------------------------

    /** A packet from the real network, routed by the kernel into the TUN device. */
    void fromOutside(byte[] packet) {
        gateway.fromTun(packet);
        settle();
    }

    /** Let time pass: frames and packets carried, stacks and the gateway ticking every 50 ms. */
    void pump(double seconds) {
        double end = now + seconds;
        settle();
        while (now < end - 1e-9) {
            now = Math.min(end, now + 0.05);
            call("tick", ValueFactory.valueOf(now));
            gateway.tick();
            settle();
        }
    }

    /** Carry everything waiting, until nothing is. */
    void settle() {
        for (int round = 0; round < 10_000; round++) {
            var sent = (LuaTable) call("take").first();
            boolean any = sent.length() > 0 || !fromGateway.isEmpty() || !toKernel.isEmpty();
            if (!any) return;
            for (int i = 1; i <= sent.length(); i++) {
                var s = (LuaTable) sent.rawget(i);
                var from = new Endpoint(s.rawget(1).toInteger(), s.rawget(2).toString());
                int channel = s.rawget(3).toInteger();
                carry(from, channel, toObject(s.rawget(4)));
            }
            while (!fromGateway.isEmpty()) {
                var s = fromGateway.poll();
                carry(s.port(), s.channel(), s.frame());
            }
            while (!toKernel.isEmpty()) kernel(toKernel.poll());
        }
        throw new AssertionError("the network never settled");
    }

    private void carry(Object from, int channel, Object payload) {
        var cable = wireOf.get(from);
        if (cable == null) return;                      // (a modem on no cable)
        for (var to : wires.get(cable)) {
            if (to == from) continue;
            if (to instanceof Endpoint e) {
                call("deliver", ValueFactory.valueOf(e.id()), ValueFactory.valueOf(e.modem()),
                    ValueFactory.valueOf(channel), toValue(payload));
            } else {
                gateway.fromCc((Gateway.Port) to, channel, payload);
            }
        }
    }

    private void kernel(byte[] p) {
        intoTun.add(p);
        long dst = Ipv4.dst(p);
        if (dst == GATEWAY) {
            if (p[9] == Ipv4.ICMP && p[20] == 8) gateway.fromTun(echoReply(p));
        } else if ((dst & CC_MASK) == CC_NETS) {
            var copy = Arrays.copyOf(p, p.length);   // (forwarded: one less TTL)
            copy[8]--;
            Ipv4.put16(copy, 10, 0);
            Ipv4.put16(copy, 10, Ipv4.checksum(copy, 0, 20, 0));
            gateway.fromTun(copy);
        } else {
            outside.add(p);
        }
    }

    private static byte[] echoReply(byte[] p) {
        var r = Arrays.copyOf(p, p.length);
        System.arraycopy(p, 12, r, 16, 4);
        System.arraycopy(p, 16, r, 12, 4);
        r[8] = 64;
        Ipv4.put16(r, 10, 0);
        Ipv4.put16(r, 10, Ipv4.checksum(r, 0, 20, 0));
        r[20] = 0;
        Ipv4.put16(r, 22, 0);
        Ipv4.put16(r, 22, Ipv4.checksum(r, 20, r.length - 20, 0));
        return r;
    }

    private Varargs call(String name, LuaValue... args) {
        try {
            return LuaThread.runMain(state, (LuaFunction) harness.rawget(name), ValueFactory.varargsOf(args));
        } catch (LuaError e) {
            throw new AssertionError("Lua: H." + name + ": " + e, e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CC's conversions (CobaltLuaMachine.toObject and toValue)
    // ---------------------------------------------------------------------------------------------------------------

    static Object toObject(LuaValue v) {
        int t = v.type();
        if (t == Constants.TNIL) return null;
        if (t == Constants.TINT || t == Constants.TNUMBER) return v.toDouble();
        if (t == Constants.TBOOLEAN) return v.toBoolean();
        if (t == Constants.TSTRING) return v.toString();
        if (t != Constants.TTABLE) return null;
        Map<Object, Object> m = new HashMap<>();
        var table = (LuaTable) v;
        LuaValue k = Constants.NIL;
        while (true) {
            Varargs kv;
            try {
                kv = table.next(k);
            } catch (LuaError e) {
                break;
            }
            k = kv.first();
            if (k.isNil()) break;
            var key = toObject(k);
            var value = toObject(kv.arg(2));
            if (key != null && value != null) m.put(key, value);
        }
        return m;
    }

    static LuaValue toValue(Object o) {
        if (o == null) return Constants.NIL;
        if (o instanceof Number n) return ValueFactory.valueOf(n.doubleValue());
        if (o instanceof Boolean b) return ValueFactory.valueOf(b);
        if (o instanceof String s) return ValueFactory.valueOf(s);
        if (o instanceof byte[] b) return ValueFactory.valueOf(Arrays.copyOf(b, b.length));
        if (o instanceof Map<?, ?> m) {
            var table = new LuaTable();
            for (var e : m.entrySet()) {
                var k = toValue(e.getKey());
                var v = toValue(e.getValue());
                if (!k.isNil() && !v.isNil()) {
                    try {
                        table.rawset(k, v);
                    } catch (LuaError ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
            return table;
        }
        throw new IllegalArgumentException("CC can't carry a " + o.getClass().getName());
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
