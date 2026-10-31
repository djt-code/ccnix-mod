package ccnix.gateway;

import ccnix.Ccnix;
import ccnix.gateway.core.Gateway;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.network.Packet;
import dan200.computercraft.api.network.PacketReceiver;
import dan200.computercraft.api.network.wired.WiredElement;
import dan200.computercraft.api.network.wired.WiredElementCapability;
import dan200.computercraft.api.network.wired.WiredNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A gateway block: a node on CC's wired network (a cable joins it at its port, the side it faces) that
 * hears every packet on it and passes those on the gateway's channel and its VLANs' to the gateway, and sends the
 * gateway's frames (its own, and other blocks' it carries) into it. Each has an ID and the player who placed it,
 * and its own filter: whether it passes untagged frames (the exchange) and which VLANs, both ways.
 */
public final class GatewayBlockEntity extends BlockEntity implements Gateway.Port {
    /** The blocks' beacon: on a channel no modem can open, so only blocks hear it. */
    static final int HELLO = -1;
    static final String ALL_VLANS = "1-" + Gateway.VLAN_MAX;

    private final Element element = new Element();
    private final WiredNode node = ComputerCraftAPI.createWiredNodeForElement(element);
    @SuppressWarnings("unchecked")
    private final BlockCapabilityCache<WiredElement, Direction>[] neighbours = new BlockCapabilityCache[6];
    private volatile int id;
    private UUID owner;
    private String ownerName = "";
    private boolean warned;
    private volatile boolean untagged = true;
    private volatile String vlanSpec = ALL_VLANS;
    private volatile java.util.BitSet vlanSet = Gateway.vlans(ALL_VLANS, new ArrayList<>());

    public GatewayBlockEntity(BlockPos pos, BlockState state) {
        super(Ccnix.GATEWAY_ENTITY.get(), pos, state);
        node.addReceiver(new Receiver());
    }

    public WiredElement element() {
        return element;
    }

    /** The side its port is on: the only one a cable joins. */
    public Direction port() {
        return getBlockState().getValue(GatewayBlock.FACING);
    }

    /** CC's wired element on its port's side (for the capability), none on the others. */
    public WiredElement element(Direction side) {
        return side == null || side == port() ? element : null;
    }

    /** Its ID (0 until it's on a server). */
    public int id() {
        return id;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            var registry = GatewayRegistry.get(server.getServer());
            if (id == 0) {
                id = registry.add(level, worldPosition);
                setChanged();
            } else {
                registry.put(id, owner, ownerName, level, worldPosition);
            }
            Service.addPort(this);
            scheduleConnect();
        }
    }

    /** Placed by player: theirs, and counted against their limit. */
    void placedBy(ServerPlayer player) {
        owner = player.getUUID();
        ownerName = player.getGameProfile().getName();
        setChanged();
        if (level instanceof ServerLevel server && id > 0) {
            GatewayRegistry.get(server.getServer()).put(id, owner, ownerName, level, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level == null || !level.isClientSide) {
            Service.removePort(this);
            node.remove();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (id > 0) tag.putInt("id", id);
        if (owner != null) tag.putUUID("owner", owner);
        if (!ownerName.isEmpty()) tag.putString("ownerName", ownerName);
        if (warned) tag.putBoolean("warned", true);
        if (!untagged) tag.putBoolean("noUntagged", true);
        if (!vlanSpec.equals(ALL_VLANS)) tag.putString("vlans", vlanSpec);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        id = tag.getInt("id");
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = tag.getString("ownerName");
        warned = tag.getBoolean("warned");
        untagged = !tag.getBoolean("noUntagged");
        vlanSpec = tag.contains("vlans") ? tag.getString("vlans") : ALL_VLANS;
        vlanSet = Gateway.vlans(vlanSpec, new ArrayList<>());
    }

    @Override
    public boolean carries(int vlan) {
        return vlan == 0 ? untagged : vlanSet.get(vlan);
    }

    /** Its owner may change its filter, and an op may change any block's. */
    boolean mayChange(ServerPlayer player) {
        return player.getUUID().equals(owner) || player.hasPermissions(2);
    }

    /** A new filter, or what's wrong with it (nothing changed then). */
    String filter(boolean untagged, String vlans) {
        var problems = new ArrayList<String>();
        var set = Gateway.vlans(vlans, problems);
        if (!problems.isEmpty()) return problems.get(0);
        this.untagged = untagged;
        vlanSpec = vlans.trim();
        vlanSet = set;
        setChanged();
        return null;
    }

    /** Its dialog, on player's screen, with what the last change said. */
    void openFor(ServerPlayer player, String message) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new GatewayScreenData(worldPosition, stats(), untagged, vlanSpec, mayChange(player), message));
    }

    private void scheduleConnect() {
        if (level != null && !isRemoved()) level.scheduleTick(worldPosition, getBlockState().getBlock(), 0);
    }

    /**
     * Join the wired element at its port, and let go of any on another side (its block's tick, when one comes or
     * goes). The port shows a plug while something is joined there, and the cables around are told to look again: one
     * beside the block draws no arm to it, whatever it drew before (a block from before ports, a client's guess).
     */
    void connect() {
        if (!(level instanceof ServerLevel server) || isRemoved()) return;
        var port = port();
        boolean plugged = false;
        for (var side : Direction.values()) {
            var pos = worldPosition.relative(side);
            var cache = neighbours[side.ordinal()];
            if (cache == null) {
                cache = neighbours[side.ordinal()] = BlockCapabilityCache.create(WiredElementCapability.get(), server,
                    pos, side.getOpposite(), () -> !isRemoved(), this::scheduleConnect);
            }
            if (!server.isLoaded(pos)) continue;
            var other = cache.getCapability();
            if (other == null) continue;
            if (side == port) {
                node.connectTo(other.getNode());
                plugged = true;
            } else {
                node.disconnectFrom(other.getNode());
            }
        }
        var state = getBlockState();
        if (state.getValue(GatewayBlock.PLUGGED) != plugged) {
            server.setBlock(worldPosition, state.setValue(GatewayBlock.PLUGGED, plugged), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        getBlockState().updateNeighbourShapes(server, worldPosition, net.minecraft.world.level.block.Block.UPDATE_ALL);
        for (var side : Direction.values()) server.getChunkSource().blockChanged(worldPosition.relative(side));
    }

    /** A frame from the gateway (on its thread: CC's wired network takes packets from any thread). */
    @Override
    public void send(int channel, Map<String, Object> frame) {
        if (isRemoved() || level == null) return;
        node.transmitInterdimensional(new Packet(channel, channel, frame, element));
    }

    @Override
    public void hello() {
        if (isRemoved() || level == null) return;
        node.transmitInterdimensional(new Packet(HELLO, HELLO, "ccnix gateway", element));
    }

    /** The first placed works; later ones on its network stand by (no ID yet: last). */
    @Override
    public long rank() {
        return id > 0 ? id : Long.MAX_VALUE;
    }

    /** Standing by: the owner is told, the first time. */
    @Override
    public void standby(Gateway.Port active) {
        if (active == null || warned || !(level instanceof ServerLevel server)) return;
        int other = active instanceof GatewayBlockEntity b ? b.id : 0;
        server.getServer().execute(() -> {
            warned = true;
            setChanged();
            var player = owner == null ? null : server.getServer().getPlayerList().getPlayer(owner);
            if (player != null) {
                player.displayClientMessage(Component.literal(name() + " at " + where() + " is standing by: Network"
                    + " Gateway #" + other + " is already on its cable network, and only one works on each. It takes"
                    + " over if #" + other + " goes (its chunk unloads, or it's broken)."), false);
            }
        });
    }

    /** What a right-click shows. */
    List<String> stats() {
        var out = new ArrayList<String>();
        out.add(name() + (owner == null ? "" : ", placed by " + ownerName) + ", at " + where());
        out.addAll(Service.portStatus(this));
        out.add("its filter: " + (untagged ? "untagged" : "no untagged") + ", VLANs "
            + (vlanSet.isEmpty() ? "none" : Gateway.says(vlanSet)));
        return out;
    }

    private String name() {
        return "Network Gateway #" + id;
    }

    private String where() {
        return worldPosition.getX() + " " + worldPosition.getY() + " " + worldPosition.getZ();
    }

    private void receive(Packet packet) {
        if (packet.sender() == element) return;
        if (packet.sender() instanceof Element other) {   // another block on this network
            if (packet.channel() == HELLO) Service.heard(this, other.block());
            return;                                     // (what a block sent is never carried again: no loops)
        }
        int base = Service.channel(), channel = packet.channel();
        if (base < 0 || channel < base || channel > base + Gateway.VLAN_MAX) return;
        Service.receive(this, channel, packet.payload());
    }

    private Vec3 centre() {
        return Vec3.atCenterOf(worldPosition);
    }

    private final class Element implements WiredElement {
        GatewayBlockEntity block() {
            return GatewayBlockEntity.this;
        }

        @Override
        public WiredNode getNode() {
            return node;
        }

        @Override
        public String getSenderID() {
            return "ccnix";
        }

        @Override
        public Level getLevel() {
            return GatewayBlockEntity.this.getLevel();
        }

        @Override
        public Vec3 getPosition() {
            return centre();
        }
    }

    /** Hears everything on the network, as far as it reaches and in every dimension, as a wired modem does. */
    private final class Receiver implements PacketReceiver {
        @Override
        public Level getLevel() {
            return GatewayBlockEntity.this.getLevel();
        }

        @Override
        public Vec3 getPosition() {
            return centre();
        }

        @Override
        public double getRange() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isInterdimensional() {
            return true;
        }

        @Override
        public void receiveSameDimension(Packet packet, double distance) {
            receive(packet);
        }

        @Override
        public void receiveDifferentDimension(Packet packet) {
            receive(packet);
        }
    }
}
