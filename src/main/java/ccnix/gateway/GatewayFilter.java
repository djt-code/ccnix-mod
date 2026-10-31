package ccnix.gateway;

import ccnix.Ccnix;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: a gateway block's new VLANs and native VLAN, from its dialog. Its owner's to change, or an op's. */
public record GatewayFilter(BlockPos pos, String vlans, String nativeVlan) implements CustomPacketPayload {
    public static final Type<GatewayFilter> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Ccnix.ID, "gateway_filter"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GatewayFilter> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, GatewayFilter::pos,
        ByteBufCodecs.STRING_UTF8, GatewayFilter::vlans,
        ByteBufCodecs.STRING_UTF8, GatewayFilter::nativeVlan,
        GatewayFilter::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GatewayFilter f, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (player.distanceToSqr(Vec3.atCenterOf(f.pos())) > 64 || !player.level().isLoaded(f.pos())
            || !(player.level().getBlockEntity(f.pos()) instanceof GatewayBlockEntity gateway)) {
            return;
        }
        String message;
        if (!gateway.mayChange(player)) {
            message = "Only its owner, or an op, can change this block's VLANs";
        } else {
            var problem = gateway.filter(f.vlans().length() > 200 ? f.vlans().substring(0, 200) : f.vlans(),
                f.nativeVlan().length() > 8 ? f.nativeVlan().substring(0, 8) : f.nativeVlan());
            message = problem == null ? "Saved" : problem;
        }
        gateway.openFor(player, message);
    }
}
