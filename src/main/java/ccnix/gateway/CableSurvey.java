package ccnix.gateway;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.network.wired.WiredElement;
import dan200.computercraft.api.network.wired.WiredElementCapability;
import dan200.computercraft.api.network.wired.WiredNetworkChange;
import dan200.computercraft.api.network.wired.WiredNode;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a Network Configurator shows of a CC cable network, clicked on one of its cables or wired modems: the
 * peripherals its modems share, by the names computers on it use (drive_0, computer_12), and what sits beside each
 * modem without being shared (a computer using the modem). The names come from CC itself: a node of our own joins
 * the network for a moment, and CC tells it, as it tells a wired modem joining, every peripheral on it. The rest is
 * a walk along the cables in the world.
 */
final class CableSurvey {
    /** As far as the walk goes, in blocks: a network bigger than that is shown in part. */
    private static final int MAX_BLOCKS = 4096;

    private CableSurvey() {
    }

    /** Whether a block is one this looks at: CC's cable (with or without a modem on it) and its full-block modem. */
    static boolean isCable(BlockState state) {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key.getNamespace().equals("computercraft")
            && (key.getPath().equals("cable") || key.getPath().equals("wired_modem_full"));
    }

    /** Tell player what's on the network at pos (clicked on face). */
    static void show(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face) {
        var start = elementAt(level, pos, face);
        if (start == null) {
            player.sendSystemMessage(Component.literal("That isn't on a cable network").withStyle(ChatFormatting.GRAY));
            return;
        }
        var shared = sharedOn(level, pos, start);
        var walk = walk(level, pos);

        var out = new ArrayList<Component>();
        out.add(Component.literal("Cable network at " + where(pos) + ": " + count(walk.cables, "cable") + ", "
            + count(walk.modems, "modem") + (walk.gateways.isEmpty() ? "" : ", gateway " + String.join(", ", walk.gateways))
            + (walk.cut ? " (more than " + MAX_BLOCKS + " blocks: shown in part)" : "")).withStyle(ChatFormatting.YELLOW));

        out.add(Component.literal("Shared on it, by the names its computers use:").withStyle(ChatFormatting.GRAY));
        var sharedAt = new HashSet<BlockPos>();
        if (shared.isEmpty()) out.add(Component.literal("  nothing (a modem shares what's beside it once it's turned on)"));
        for (var e : shared.entrySet()) {
            var at = e.getValue().getTarget() instanceof BlockEntity be ? be.getBlockPos() : null;
            if (at != null) sharedAt.add(at);
            out.add(Component.literal("  " + e.getKey() + "  " + e.getValue().getType()
                + (at != null ? "  " + describe(level, at) : "")));
        }

        var beside = new ArrayList<String>();
        for (var at : walk.beside) if (!sharedAt.contains(at)) beside.add("  " + describe(level, at));
        if (!beside.isEmpty()) {
            out.add(Component.literal("Beside its modems, not shared (a computer there uses the modem):")
                .withStyle(ChatFormatting.GRAY));
            for (var line : beside) out.add(Component.literal(line));
        }

        if (!walk.unloaded.isEmpty()) {
            out.add(Component.literal("It goes on into chunks that aren't loaded, so what's beyond isn't shown, and"
                + " isn't on the network until they load:").withStyle(ChatFormatting.GOLD));
            for (var e : walk.unloaded.entrySet()) {
                out.add(Component.literal("  chunk " + e.getKey().x + ", " + e.getKey().z + " (the cable at "
                    + where(e.getValue()) + ")"));
            }
        }
        for (var line : out) player.sendSystemMessage(line);
    }

    /** CC's wired element at pos: on the clicked face first, then any side. */
    private static WiredElement elementAt(Level level, BlockPos pos, Direction face) {
        var e = level.getCapability(WiredElementCapability.get(), pos, face);
        if (e != null) return e;
        for (var d : Direction.values()) {
            e = level.getCapability(WiredElementCapability.get(), pos, d);
            if (e != null) return e;
        }
        return level.getCapability(WiredElementCapability.get(), pos, null);
    }

    /** The peripherals shared on start's network, by name: what CC tells a node joining it. */
    private static Map<String, IPeripheral> sharedOn(Level level, BlockPos pos, WiredElement start) {
        var heard = new TreeMap<String, IPeripheral>();
        var probe = new Probe(level, Vec3.atCenterOf(pos), heard);
        var node = ComputerCraftAPI.createWiredNodeForElement(probe);
        probe.node = node;
        try {
            node.connectTo(start.getNode());
        } finally {
            node.remove();
        }
        return heard;
    }

    private record Walk(int cables, int modems, List<String> gateways, Set<BlockPos> beside, Map<ChunkPos, BlockPos> unloaded,
                        boolean cut) {
    }

    /**
     * Along the cables from pos: a step goes to a neighbour when both sides have a wired element there. A modem's
     * neighbours that aren't on the network themselves are what it sits beside: the one it faces, for a modem on a
     * cable; all of them, for a full-block modem. It never loads a chunk: a cable leading into one that isn't loaded is
     * noted (the chunk, and the first block it would go to there), and the walk goes no further that way.
     */
    private static Walk walk(Level level, BlockPos pos) {
        var seen = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        var beside = new LinkedHashMap<BlockPos, Boolean>();
        var unloaded = new LinkedHashMap<ChunkPos, BlockPos>();
        var gateways = new ArrayList<String>();
        int cables = 0, modems = 0;
        seen.add(pos);
        queue.add(pos);
        while (!queue.isEmpty() && seen.size() <= MAX_BLOCKS) {
            var at = queue.poll();
            var state = level.getBlockState(at);
            if (level.getBlockEntity(at) instanceof GatewayBlockEntity g) gateways.add("#" + g.id());
            else if (isCable(state)) cables++;
            var faces = modemFaces(state);
            if (!faces.isEmpty()) modems++;
            for (var d : Direction.values()) {
                var next = at.relative(d);
                if (!level.isLoaded(next)) {
                    if (leadsToward(level, at, state, d)) unloaded.putIfAbsent(new ChunkPos(next), next);
                    continue;
                }
                boolean joined = level.getCapability(WiredElementCapability.get(), at, d) != null
                    && level.getCapability(WiredElementCapability.get(), next, d.getOpposite()) != null;
                if (joined) {
                    if (seen.add(next)) queue.add(next);
                } else if (faces.contains(d) && !level.getBlockState(next).isAir()) {
                    beside.put(next, true);
                }
            }
        }
        return new Walk(cables, modems, gateways, beside.keySet(), unloaded, !queue.isEmpty());
    }

    /**
     * Whether the block at goes on toward side d: a cable says so in its state (its "north", "up" ... are true where
     * it joins a neighbour, kept from when that neighbour was loaded); anything else, if it has a wired element there.
     */
    private static boolean leadsToward(Level level, BlockPos at, BlockState state, Direction d) {
        for (var p : state.getProperties()) {
            if (p.getName().equals(d.getName())) return valueName(state, p).equals("true");
        }
        return level.getCapability(WiredElementCapability.get(), at, d) != null;
    }

    /**
     * The sides a modem on this block faces: a cable's modem says it in its "modem" state ("down_on", "north_off"),
     * a full-block modem faces every side; none for a bare cable or anything else.
     */
    private static Set<Direction> modemFaces(BlockState state) {
        var faces = new HashSet<Direction>();
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key.getNamespace().equals("computercraft") && key.getPath().equals("wired_modem_full")) {
            faces.addAll(List.of(Direction.values()));
            return faces;
        }
        for (var p : state.getProperties()) {
            if (!p.getName().equals("modem")) continue;
            var value = valueName(state, p);
            int cut = value.indexOf('_');
            var d = cut > 0 ? Direction.byName(value.substring(0, cut)) : null;
            if (d != null) faces.add(d);
        }
        return faces;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    /** A block for a person: its name, a computer's label, where it is. */
    private static String describe(Level level, BlockPos at) {
        var state = level.getBlockState(at);
        var name = state.getBlock().getName().getString();
        if (level.getBlockEntity(at) instanceof Nameable n && n.hasCustomName() && n.getCustomName() != null) {
            name += " \"" + n.getCustomName().getString() + "\"";
        }
        return name + " at " + where(at);
    }

    private static String where(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    /** Our node's element, for the moment it's on the network: it keeps what CC says was added. */
    private static final class Probe implements WiredElement {
        private final Level level;
        private final Vec3 position;
        private final Map<String, IPeripheral> heard;
        WiredNode node;

        Probe(Level level, Vec3 position, Map<String, IPeripheral> heard) {
            this.level = level;
            this.position = position;
            this.heard = heard;
        }

        @Override
        public WiredNode getNode() {
            return node;
        }

        @Override
        public String getSenderID() {
            return "ccnix-survey";
        }

        @Override
        public Level getLevel() {
            return level;
        }

        @Override
        public Vec3 getPosition() {
            return position;
        }

        @Override
        public void networkChanged(WiredNetworkChange change) {
            heard.putAll(change.peripheralsAdded());
            for (var name : change.peripheralsRemoved().keySet()) heard.remove(name);
        }
    }
}
