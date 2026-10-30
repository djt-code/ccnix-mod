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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;

import java.util.Map;

/**
 * A gateway block: a node on CC's wired network (cables join it from any side, as with a full-block modem) that
 * hears every packet on it and passes those on the gateway's channel to the gateway, and sends the gateway's frames
 * into it.
 */
public final class GatewayBlockEntity extends BlockEntity implements Gateway.Port {
    private final Element element = new Element();
    private final WiredNode node = ComputerCraftAPI.createWiredNodeForElement(element);
    @SuppressWarnings("unchecked")
    private final BlockCapabilityCache<WiredElement, Direction>[] neighbours = new BlockCapabilityCache[6];

    public GatewayBlockEntity(BlockPos pos, BlockState state) {
        super(Ccnix.GATEWAY_ENTITY.get(), pos, state);
        node.addReceiver(new Receiver());
    }

    public WiredElement element() {
        return element;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            Service.addPort(this);
            scheduleConnect();
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

    private void scheduleConnect() {
        if (level != null && !isRemoved()) level.scheduleTick(worldPosition, getBlockState().getBlock(), 0);
    }

    /** Join the wired elements beside it (its block's tick, when one comes or goes). */
    void connect() {
        if (!(level instanceof ServerLevel server) || isRemoved()) return;
        for (var side : Direction.values()) {
            var pos = worldPosition.relative(side);
            var cache = neighbours[side.ordinal()];
            if (cache == null) {
                cache = neighbours[side.ordinal()] = BlockCapabilityCache.create(WiredElementCapability.get(), server,
                    pos, side.getOpposite(), () -> !isRemoved(), this::scheduleConnect);
            }
            if (!server.isLoaded(pos)) continue;
            var other = cache.getCapability();
            if (other != null) node.connectTo(other.getNode());
        }
    }

    /** A frame from the gateway (on its thread: CC's wired network takes packets from any thread). */
    @Override
    public void send(int channel, Map<String, Object> frame) {
        if (isRemoved() || level == null) return;
        node.transmitInterdimensional(new Packet(channel, channel, frame, element));
    }

    private void receive(Packet packet) {
        if (packet.sender() == element || packet.channel() != Service.channel()) return;
        Service.receive(this, packet.channel(), packet.payload());
    }

    private Vec3 centre() {
        return Vec3.atCenterOf(worldPosition);
    }

    private final class Element implements WiredElement {
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
