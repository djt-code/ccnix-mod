package ccnix.gateway;

import ccnix.Ccnix;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server, each second a gateway block's dialog is open: its stats again, so they stay live. */
public record GatewayPoll(BlockPos pos) implements CustomPacketPayload {
    public static final Type<GatewayPoll> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Ccnix.ID, "gateway_poll"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GatewayPoll> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, GatewayPoll::pos,
        GatewayPoll::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GatewayPoll poll, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            var gateway = GatewayBlockEntity.near(player, poll.pos());
            if (gateway != null) gateway.refreshFor(player);
        }
    }
}
