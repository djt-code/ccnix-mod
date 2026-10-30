package ccnix;

import ccnix.gateway.Service;
import ccnix.gateway.core.OpRoutes;
import ccnix.gateway.core.Settings;
import ccnix.rom.Rom;
import ccnix.rom.RomPack;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /ccnix: what the ROM and the gateway are doing (permission level 2), and for ops (level 3): the gateway's routes and
 * a ROM update.
 * <pre>
 *   /ccnix                                  the ROM, then the gateway
 *   /ccnix route                            the gateway's routes, and the server's into the TUN device
 *   /ccnix route add &lt;net/len&gt; via &lt;router&gt;  (gateway.opRoutes, and no larger than gateway.opMaxSubnet)
 *   /ccnix route del &lt;net/len&gt;
 *   /ccnix rom update                       git pull (or clone) ccnix-rom/ in the server's folder, then reload
 * </pre>
 * Route changes are saved in config/ccnix-server.toml and apply at once.
 */
final class CcnixCommand {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int OPS = 3;

    private CcnixCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("ccnix").requires(s -> s.hasPermission(2))
            .executes(ctx -> say(ctx, status()))
            .then(literal("route")
                .executes(ctx -> say(ctx, routes()))
                .then(literal("add").requires(s -> s.hasPermission(OPS))
                    .then(argument("route", greedyString()).executes(ctx -> addRoute(ctx.getSource(), getString(ctx, "route")))))
                .then(literal("del").requires(s -> s.hasPermission(OPS))
                    .then(argument("network", greedyString()).executes(ctx -> delRoute(ctx.getSource(), getString(ctx, "network"))))))
            .then(literal("rom")
                .executes(ctx -> say(ctx, romStatus()))
                .then(literal("update").requires(s -> s.hasPermission(OPS)).executes(ctx -> updateRom(ctx.getSource())))));
    }

    private static int say(CommandContext<CommandSourceStack> ctx, List<String> lines) {
        for (var line : lines) ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int fail(CommandSourceStack src, String why) {
        src.sendFailure(Component.literal(why));
        return 0;
    }

    /** What /ccnix shows: the ROM, then the gateway. */
    private static List<String> status() {
        var out = romStatus();
        for (var line : Service.status()) out.add("gateway: " + line);
        return out;
    }

    private static List<String> romStatus() {
        var out = new ArrayList<String>();
        Rom rom = RomPack.current();
        if (rom == null) {
            out.add("ROM: not loaded");
        } else {
            out.add("ROM: ccnix " + rom.commit.substring(0, Math.min(7, rom.commit.length()))
                + (rom.date > 0 ? " (" + Instant.ofEpochSecond(rom.date) + ")" : "") + ", " + rom.files.size()
                + " files, from " + rom.source);
            out.add("  computers update from " + rom.url + " " + rom.branch + "; /ccnix rom update pulls "
                + Rom.CHECKOUT + "/ in the server's folder (git) and reloads");
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Routes
    // ---------------------------------------------------------------------------------------------------------------

    private static List<String> routes() {
        var out = new ArrayList<String>();
        var s = Service.settings();
        out.add("gateway " + s.address() + ": " + (s.routes().isEmpty() ? "no routes" : s.routes().size() + " route"
            + (s.routes().size() == 1 ? "" : "s")) + (Config.OP_ROUTES.get()
            ? " (ops may change them, a /" + Config.OP_MAX_SUBNET.get() + " at most: /ccnix route add|del)"
            : " (ops can't change them: gateway.opRoutes)"));
        for (var r : s.routes()) out.add("  " + r);
        try {
            var host = Service.hostRoutes();
            out.add("the server routes into " + Config.DEVICE.get() + ": "
                + (host.isEmpty() ? "nothing" : String.join(", ", host.stream().map(OpRoutes.HostRoute::toString).toList())));
        } catch (IOException e) {
            out.add("the server's routes into " + Config.DEVICE.get() + " can't be read: " + e.getMessage());
        }
        return out;
    }

    private static int addRoute(CommandSourceStack src, String line) {
        if (!Config.OP_ROUTES.get()) return fail(src, "Ops can't change the gateway's routes on this server (gateway.opRoutes)");
        List<OpRoutes.HostRoute> host;
        try {
            host = Service.hostRoutes();
        } catch (IOException e) {
            return fail(src, "Can't read the server's routes into " + Config.DEVICE.get() + ": " + e.getMessage());
        }
        var check = OpRoutes.add(line, Service.settings(), Config.OP_MAX_SUBNET.get(), host);
        if (check.problem() != null) return fail(src, "Not added: " + check.problem());
        var list = new ArrayList<String>(Config.ROUTES.get());
        list.add(check.route().toString());
        save(list);
        LOG.info("ccnix: {} added the route {}", src.getTextName(), check.route());
        src.sendSuccess(() -> Component.literal("Added the route " + check.route()), true);
        return 1;
    }

    private static int delRoute(CommandSourceStack src, String network) {
        if (!Config.OP_ROUTES.get()) return fail(src, "Ops can't change the gateway's routes on this server (gateway.opRoutes)");
        var net = OpRoutes.network(network.trim());
        if (net.problem() != null) return fail(src, net.problem());
        var list = new ArrayList<String>();
        String gone = null;
        for (var line : Config.ROUTES.get()) {
            var n = OpRoutes.network(line.trim().split("\\s+")[0]);
            if (gone == null && n.problem() == null && n.ip() == net.ip() && n.len() == net.len()) gone = line;
            else list.add(line);
        }
        if (gone == null) return fail(src, "No route for " + network.trim() + " (/ccnix route lists them)");
        save(list);
        var what = gone;
        LOG.info("ccnix: {} removed the route {}", src.getTextName(), what);
        src.sendSuccess(() -> Component.literal("Removed the route " + what), true);
        return 1;
    }

    private static void save(List<String> routes) {
        Config.ROUTES.set(routes);
        Config.ROUTES.save();
        Service.reconfigure();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The ROM
    // ---------------------------------------------------------------------------------------------------------------

    private static int updateRom(CommandSourceStack src) {
        Rom rom = RomPack.current();
        var server = src.getServer();
        Path dir = FMLPaths.GAMEDIR.get().resolve(Rom.CHECKOUT);
        boolean clone = !Files.isDirectory(dir.resolve(".git"));
        if (clone && Files.exists(dir)) return fail(src, dir + " is there but isn't a git checkout: move it out of the way");
        if (clone && (rom == null || rom.url == null)) return fail(src, "No repo to clone: the mod's ROM didn't load");
        var was = rom == null ? "none" : shortCommit(rom.commit);
        src.sendSuccess(() -> Component.literal(clone ? "Cloning " + rom.url + " " + rom.branch + " into " + Rom.CHECKOUT + "/..."
            : "Pulling " + Rom.CHECKOUT + "/..."), true);
        CompletableFuture.supplyAsync(() -> {
            var got = clone ? git(dir.getParent(), "clone", "--depth", "1", "--branch", rom.branch, rom.url, Rom.CHECKOUT)
                : git(dir, "pull", "--ff-only");
            return got.ok() ? git(dir, "rev-parse", "HEAD") : got;
        }).thenAccept(got -> server.execute(() -> {
            if (!got.ok()) {
                src.sendFailure(Component.literal("ROM update failed: " + got.out()));
                return;
            }
            var is = shortCommit(got.out());
            server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((v, e) -> {
                if (e != null) {
                    src.sendFailure(Component.literal("Reloading failed: " + e.getMessage()));
                    return;
                }
                LOG.info("ccnix: {} updated the ROM: {} -> {}", src.getTextName(), was, is);
                src.sendSuccess(() -> Component.literal(was.equals(is) ? "The ROM is up to date (ccnix " + is + ")"
                    : "The ROM is ccnix " + is + " now (was " + was + "): computers boot it from their next reboot"), true);
            });
        }));
        return 1;
    }

    private static String shortCommit(String commit) {
        return commit.substring(0, Math.min(7, commit.length()));
    }

    /** What git said: its output if it worked, else its last line (or why it couldn't run). */
    private record Git(boolean ok, String out) {
    }

    /** Run git in dir, giving up on a transfer that stalls for a minute. */
    private static Git git(Path dir, String... args) {
        var cmd = new ArrayList<>(List.of("git", "-c", "http.lowSpeedLimit=1", "-c", "http.lowSpeedTime=60", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        try {
            var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            var p = pb.start();
            p.getOutputStream().close();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroy();
                return new Git(false, "git didn't finish");
            }
            if (p.exitValue() == 0) return new Git(true, out);
            var lines = out.split("\n");
            return new Git(false, out.isEmpty() ? "git exited with " + p.exitValue() : lines[lines.length - 1].trim());
        } catch (IOException e) {
            return new Git(false, "can't run git (is it installed?): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Git(false, "interrupted");
        }
    }
}
